package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.generated.transactions.server.model.ActivationResultRequestDto;

public record ActivationResultData(
        TransactionActivated transactionActivated,
        ActivationResultRequestDto activationResultData
) {
}
