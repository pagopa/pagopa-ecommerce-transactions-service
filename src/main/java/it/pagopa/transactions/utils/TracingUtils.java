package it.pagopa.transactions.utils;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import it.pagopa.ecommerce.commons.queues.TracingInfo;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.function.Function;

@Component
public class TracingUtils {
    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    public TracingUtils(
            OpenTelemetry openTelemetry,
            Tracer tracer
    ) {
        this.tracer = tracer;
        this.openTelemetry = openTelemetry;
    }

    public record TracingContext(
            @NonNull Span span,
            @NonNull TracingInfo tracingInfo
    ) {
    }

    public <T> Mono<T> traceMono(
                                 String spanName,
                                 Function<TracingInfo, Mono<T>> traced
    ) {
        return Mono.using(
                () -> {
                    Span span = tracer.spanBuilder(spanName)
                            .setSpanKind(SpanKind.PRODUCER)
                            .setParent(Context.current().with(Span.current()))
                            .startSpan();

                    HashMap<String, String> rawTracingInfo = new HashMap<>();
                    openTelemetry.getPropagators().getTextMapPropagator().inject(
                            Context.current(),
                            rawTracingInfo,
                            (
                             map,
                             header,
                             value
                            ) -> map.put(header, value)
                    );

                    TracingInfo tracingInfo = new TracingInfo(
                            rawTracingInfo.get("traceparent"),
                            rawTracingInfo.get("tracestate"),
                            rawTracingInfo.get("baggage")
                    );

                    return new TracingContext(span, tracingInfo);
                },
                tracingContext -> traced.apply(tracingContext.tracingInfo),
                tracingContext -> tracingContext.span.end()
        );
    }
}
