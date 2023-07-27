package it.pagopa.transactions.utils;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;

class OpenTelemetryUtilsTest {

    private final Tracer openTelemetryTracer = Mockito.mock(Tracer.class);

    private final SpanBuilder spanBuilder = Mockito.mock(SpanBuilder.class);

    private final Span span = Mockito.mock(Span.class);

    private final OpenTelemetryUtils openTelemetryUtils = new OpenTelemetryUtils(openTelemetryTracer);

    @Test
    void shouldCreateSpanWithAttributes() {
        // prerequisite
        String spanName = "spanName";
        Attributes attributes = Attributes.of(AttributeKey.stringKey("key"), "value");
        Mockito.when(openTelemetryTracer.spanBuilder(spanName)).thenReturn(spanBuilder);
        Mockito.when(spanBuilder.startSpan()).thenReturn(span);
        Mockito.when(span.setAllAttributes(any())).thenReturn(span);
        // test
        openTelemetryUtils.addSpanWithAttributes(spanName, attributes);
        // assertions
        Mockito.verify(openTelemetryTracer, Mockito.times(1)).spanBuilder(spanName);
        Mockito.verify(span, Mockito.times(1)).setAllAttributes(attributes);
        Mockito.verify(span, Mockito.times(1)).end();
    }

    @Test
    void shouldCreateErrorSpanWithAttributes() {
        // prerequisite
        String spanName = "spanName";
        Attributes attributes = Attributes.of(AttributeKey.stringKey("key"), "value");
        Mockito.when(openTelemetryTracer.spanBuilder(spanName)).thenReturn(spanBuilder);
        Mockito.when(spanBuilder.startSpan()).thenReturn(span);
        Mockito.when(span.setAllAttributes(any())).thenReturn(span);
        // test
        openTelemetryUtils.addErrorSpanWithAttributes(spanName, attributes);
        // assertions
        Mockito.verify(openTelemetryTracer, Mockito.times(1)).spanBuilder(spanName);
        Mockito.verify(span, Mockito.times(1)).setAllAttributes(attributes);
        Mockito.verify(span, Mockito.times(1)).setStatus(StatusCode.ERROR);
        Mockito.verify(span, Mockito.times(1)).end();
    }

    @Test
    void shouldCreateErrorSpanWithThrowable() {
        // prerequisite
        String spanName = "spanName";
        Throwable throwable = new RuntimeException("test exception");
        Mockito.when(openTelemetryTracer.spanBuilder(spanName)).thenReturn(spanBuilder);
        Mockito.when(spanBuilder.startSpan()).thenReturn(span);
        Mockito.when(span.setAllAttributes(any())).thenReturn(span);
        // test
        openTelemetryUtils.addErrorSpanWithException(spanName, throwable);
        // assertions
        Mockito.verify(openTelemetryTracer, Mockito.times(1)).spanBuilder(spanName);
        Mockito.verify(span, Mockito.times(1)).setStatus(StatusCode.ERROR);
        Mockito.verify(span, Mockito.times(1)).recordException(throwable);
        Mockito.verify(span, Mockito.times(1)).end();
    }

}
