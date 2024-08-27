package it.pagopa.transactions.configurations;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "session-url")
public record NpgSessionUrlConfig(
        String basePath,
        String outcomeSuffix,
        String notificationUrl
) {
}
