package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithPaymentToken;

public record UserCancelData(
        BaseTransactionWithPaymentToken transaction
) {
}
