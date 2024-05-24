package it.pagopa.transactions.utils;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Open telemetry utility class used to create spans inside the current
 * transaction with custom arguments
 *
 * @see Tracer
 * @see Span
 */
@Component
public class OpenTelemetryUtils {

    /**
     * Nodo activation
     */
    public static final String NODO_ACTIVATION_ERROR_SPAN_NAME = "ActivatePaymentNoticeV2 nodo error: [%s]";

    public static final String NODO_ACTIVATION_OK_SPAN_NAME = "ActivatePaymentNoticeV2 nodo ok";
    public static final AttributeKey<String> NODO_ACTIVATION_ERROR_FAULT_CODE_ATTRIBUTE_KEY = AttributeKey
            .stringKey("faultCode");

    public static final String REPEATED_ACTIVATION_SPAN_NAME = "Transaction re-activated";

    public static final AttributeKey<String> REPEATED_ACTIVATION_PAYMENT_TOKEN_ATTRIBUTE_KEY = AttributeKey
            .stringKey("paymentToken");

    public static final AttributeKey<Long> REPEATED_ACTIVATION_PAYMENT_TOKEN_LEFT_TIME_ATTRIBUTE_KEY = AttributeKey
            .longKey("paymentTokenLeftTimeSec");

    public static final String CIRCUIT_BREAKER_OPEN_SPAN_NAME = "Circuit Breaker [%s] open";
    public static final AttributeKey<String> CIRCUIT_BREAKER_OPEN_NAME_ATTRIBUTE_KEY = AttributeKey
            .stringKey("circuitBreakerName");

    private final Tracer openTelemetryTracer;

    /**
     * Default constructor
     *
     * @param openTelemetryTracer - open telemetry tracer instance
     */
    @Autowired
    public OpenTelemetryUtils(Tracer openTelemetryTracer) {
        this.openTelemetryTracer = openTelemetryTracer;
    }

    /**
     * Add span to the current transactions with the input arguments and name
     *
     * @param spanName   - span name
     * @param attributes - span attributes
     */
    public void addSpanWithAttributes(
                                      String spanName,
                                      Attributes attributes
    ) {

        Span span = openTelemetryTracer.spanBuilder(spanName).startSpan();
        span.setAllAttributes(attributes);
        span.end();

    }

    /**
     * Add an error span to the current transaction with the input arguments and
     * name
     *
     * @param spanName   - error span name
     * @param attributes - error span attributes
     */
    public void addErrorSpanWithAttributes(
                                           String spanName,
                                           Attributes attributes
    ) {

        Span span = openTelemetryTracer.spanBuilder(spanName).startSpan();
        span.setAllAttributes(attributes);
        span.setStatus(StatusCode.ERROR);
        span.end();

    }

    /**
     * Add an error span to the current transaction with the input name and
     * throwable stacktrace
     *
     * @param spanName  - error span name
     * @param throwable - error cause Throwable
     */
    public void addErrorSpanWithException(
                                          String spanName,
                                          Throwable throwable
    ) {
        Span span = openTelemetryTracer.spanBuilder(spanName).startSpan();
        span.setStatus(StatusCode.ERROR);
        span.recordException(throwable);
        span.end();
    }

}
