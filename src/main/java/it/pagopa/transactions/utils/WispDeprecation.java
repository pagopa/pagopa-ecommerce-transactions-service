package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.domain.PaymentNotice;
import it.pagopa.ecommerce.commons.domain.v2.TransactionActivated;

import java.util.Optional;

public final class WispDeprecation {

    private WispDeprecation() {
    }

    public static Optional<String> extractCreditorReferenceId(
                                                              TransactionActivated transaction,
                                                              PaymentNotice paymentNotice
    ) {
        return extractCreditorReferenceId(transaction.getClientId(), paymentNotice.creditorReferenceId());
    }

    public static Optional<String> extractCreditorReferenceId(
                                                              it.pagopa.ecommerce.commons.documents.v2.Transaction transaction,
                                                              it.pagopa.ecommerce.commons.documents.PaymentNotice paymentNotice
    ) {
        return extractCreditorReferenceId(
                transaction.getClientId(),
                paymentNotice.getCreditorReferenceId()
        );
    }

    private static Optional<String> extractCreditorReferenceId(
                                                               Transaction.ClientId clientId,
                                                               String creditorReferenceId
    ) {
        return switch (clientId) {
            case WISP_REDIRECT -> Optional.ofNullable(creditorReferenceId);
            case CHECKOUT, CHECKOUT_CART, IO -> Optional.empty();
        };
    }

    // TODO: WISP replace with effectiveClient method
    public static String adaptClientId(String clientId) {
        if (clientId.equals(Transaction.ClientId.WISP_REDIRECT.name())) {
            return Transaction.ClientId.CHECKOUT.name();
        } else {
            return clientId;
        }
    }
}
