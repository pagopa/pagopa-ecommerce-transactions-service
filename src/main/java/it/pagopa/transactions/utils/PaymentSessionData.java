package it.pagopa.transactions.utils;

import it.pagopa.generated.wallet.v1.dto.ContextualOnboardDetailsDto;

import java.util.Optional;

public record PaymentSessionData(
        String cardBin,
        String sessionId,
        String brand,
        String contractId,
        ContextualOnboardDetails contextualOnboardDetails
) {
    public record ContextualOnboardDetails(
            String transactionId,
            Long amount,
            String orderId
    ) {
    }

    public static PaymentSessionData create(
                                            String cardBin,
                                            String sessionId,
                                            String brand,
                                            String contractId,
                                            ContextualOnboardDetailsDto source
    ) {
        Optional<ContextualOnboardDetails> details = Optional
                .ofNullable(source)
                .map(s -> new ContextualOnboardDetails(s.getTransactionId(), s.getAmount(), s.getOrderId()));
        return new PaymentSessionData(cardBin, sessionId, brand, contractId, details.orElse(null));
    }
}
