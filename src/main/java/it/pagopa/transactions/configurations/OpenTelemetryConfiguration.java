package it.pagopa.transactions.configurations;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenTelemetryConfiguration {
    @Bean
    public OpenTelemetry agentOpenTelemetrySDKInstance() {
        return GlobalOpenTelemetry.get();
    }

    @Bean
    public Tracer openTelemetryTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("pagopa-ecommerce-transactions-service");
    }

    @Bean
    public TracingUtils tracingUtils(
                                     OpenTelemetry openTelemetry,
                                     Tracer tracer
    ) {
        return new TracingUtils(openTelemetry, tracer);
    }

    @Bean
    public OpenTelemetryUtils openTelemetryUtils(
                                                 Tracer openTelemetryTracer
    ) {
        return new OpenTelemetryUtils(openTelemetryTracer);
    }
}
