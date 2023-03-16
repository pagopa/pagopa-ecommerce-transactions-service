package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;

public record UserCancellationRequestData(
        TransactionActivated transaction
) {
}
