package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;

public record UserCancelData(
        BaseTransaction transaction
) {
}
