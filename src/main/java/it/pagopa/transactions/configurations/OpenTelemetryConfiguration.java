package it.pagopa.transactions.configurations;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
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
}
