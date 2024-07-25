package it.pagopa.transactions.configurations;

import io.opentelemetry.api.trace.Tracer;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UpdateTransactionStatusTracerUtilsConfig {
    @Bean
    public UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils(
                                                                                 OpenTelemetryUtils openTelemetryUtils
    ) {
        return new UpdateTransactionStatusTracerUtils(openTelemetryUtils);
    }
}
