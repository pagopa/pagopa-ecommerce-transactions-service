package it.pagopa.transactions.utils;

import it.pagopa.generated.wallet.v1.dto.ContextualOnboardDetailsDto;

public record PaymentSessionData(
        String cardBin,
        String sessionId,
        String brand,
        String contractId,
        ContextualOnboardDetails contextualOnboardDetails
) {
    public record ContextualOnboardDetails(
            String transactionId,
            Long amount
    ) {
    }

    public static PaymentSessionData create(
                                            String cardBin,
                                            String sessionId,
                                            String brand,
                                            String contractId,
                                            ContextualOnboardDetailsDto source
    ) {
        ContextualOnboardDetails details = source != null
                ? new ContextualOnboardDetails(source.getTransactionId(), source.getAmount())
                : null;

        return new PaymentSessionData(cardBin, sessionId, brand, contractId, details);
    }
}
