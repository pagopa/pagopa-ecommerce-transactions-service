package it.pagopa.transactions.configurations;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wallet")
public record WalletConfig(
        String uri,
        int readTimeout,
        int connectionTimeout,
        String apiKey,
        NotificationConf notification
) {
    public record NotificationConf(
            int maxRetryAttempts,
            int exponentialBackoffRetryOffsetSeconds
    ) {
    }
}
