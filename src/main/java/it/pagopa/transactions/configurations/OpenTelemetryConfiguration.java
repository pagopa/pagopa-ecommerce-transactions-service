package it.pagopa.transactions.configurations;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenTelemetryConfiguration {
    @Bean
    public OpenTelemetry agentOpenTelemetrySDKInstance() {
        return OpenTelemetry.noop();
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
}
