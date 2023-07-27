package it.pagopa.transactions.utils;

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
    public static final String NODO_ACTIVATION_ERROR_SPAN_NAME = "Nodo activatePaymentNoticeV2 error";
    public static final String NODO_ACTIVATION_ERROR_FAULT_CODE_ATTRIBUTE_KEY = "faultCode";

    public static final String REPEATED_ACTIVATION_SPAN_NAME = "Transaction re-activated";

    public static final String REPEATED_ACTIVATION_PAYMENT_TOKEN_ATTRIBUTE_KEY = "paymentToken";

    public static final String REPEATED_ACTIVATION_PAYMENT_TOKEN_LEFT_TIME_ATTRIBUTE_KEY = "paymentTokenLeftTimeSec";

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
