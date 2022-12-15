package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.domain.TransactionActivationRequested;
import it.pagopa.generated.transactions.server.model.ActivationResultRequestDto;

public record ActivationResultData(
        TransactionActivationRequested transactionActivationRequested,
        ActivationResultRequestDto activationResultData
) {
}
