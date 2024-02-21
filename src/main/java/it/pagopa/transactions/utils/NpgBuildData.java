package it.pagopa.transactions.utils;

public record NpgBuildData(
        String orderId,
        String notificationJwtToken,
        String outcomeJwtToken
) {
}
