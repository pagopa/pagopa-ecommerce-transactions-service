package it.pagopa.transactions.utils;

public record PaymentSessionData(
        String cardBin,
        String sessionId,
        String brand,
        String contractId
) {
}
