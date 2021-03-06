/*
 * Copyright 2016 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.remoting1.servers.jersey;

import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.palantir.remoting1.tracing.Span;
import com.palantir.remoting1.tracing.SpanType;
import com.palantir.remoting1.tracing.TraceHttpHeaders;
import com.palantir.remoting1.tracing.Tracer;
import com.palantir.remoting1.tracing.Tracers;
import java.io.IOException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.Provider;
import org.slf4j.MDC;

@Provider
public final class TraceEnrichingFilter implements ContainerRequestFilter, ContainerResponseFilter {
    public static final TraceEnrichingFilter INSTANCE = new TraceEnrichingFilter();

    /** The key under which trace ids are inserted into SLF4J {@link org.slf4j.MDC MDCs}. */
    static final String MDC_KEY = "traceId";

    // Handles incoming request
    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String operation = requestContext.getMethod() + " /" + requestContext.getUriInfo().getPath();
        // The following strings are all nullable
        String traceId = requestContext.getHeaderString(TraceHttpHeaders.TRACE_ID);
        String spanId = requestContext.getHeaderString(TraceHttpHeaders.SPAN_ID);

        // Set up thread-local span that inherits state from HTTP headers
        if (Strings.isNullOrEmpty(traceId)) {
            // HTTP request did not indicate a trace; initialize trace state and create a span.
            Tracer.initTrace(Optional.<Boolean>absent(), Tracers.randomId());
            Tracer.startSpan(operation, SpanType.SERVER_INCOMING);
        } else {
            Tracer.initTrace(hasSampledHeader(requestContext), traceId);
            if (spanId == null) {
                Tracer.startSpan(operation, SpanType.SERVER_INCOMING);
            } else {
                // caller's span is this span's parent.
                Tracer.startSpan(operation, spanId, SpanType.SERVER_INCOMING);
            }
        }

        // Give SLF4J appenders access to the trace id
        // TODO(rfink) We should use putCloseable; when and how can we remove it though? There is no filter chain.
        MDC.put(MDC_KEY, Tracer.getTraceId());

        // Give asynchronous downstream handlers access to the trace id
        requestContext.setProperty("com.palantir.remoting1.traceId", Tracer.getTraceId());
    }

    // Handles outgoing response
    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        MultivaluedMap<String, Object> headers = responseContext.getHeaders();
        Optional<Span> maybeSpan = Tracer.completeSpan();
        if (maybeSpan.isPresent()) {
            Span span = maybeSpan.get();
            headers.putSingle(TraceHttpHeaders.TRACE_ID, span.getTraceId());
        }
    }

    // Returns true iff the context contains a "1" X-B3-Sampled header, or absent if there is no such header.
    private static Optional<Boolean> hasSampledHeader(ContainerRequestContext context) {
        String header = context.getHeaderString(TraceHttpHeaders.IS_SAMPLED);
        if (header == null) {
            return Optional.absent();
        } else {
            return Optional.of(header.equals("1"));
        }
    }
}
