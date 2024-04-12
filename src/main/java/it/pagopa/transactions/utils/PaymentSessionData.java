package it.pagopa.transactions.utils;

public record PaymentSessionData(
        String cardBin,
        String lastFourDigits,
        String sessionId,
        String brand,
        String contractId,
        String paypalMaskedEmail
) {
}
