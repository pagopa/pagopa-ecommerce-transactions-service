package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.domain.v2.PaymentNotice;
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

    private static Optional<String> extractCreditorReferenceId(
                                                               Transaction.ClientId clientId,
                                                               String creditorReferenceId
    ) {
        return switch (clientId) {
            case WISP_REDIRECT -> Optional.ofNullable(creditorReferenceId);
            case CHECKOUT, CHECKOUT_CART, IO -> Optional.empty();
        };
    }
}
