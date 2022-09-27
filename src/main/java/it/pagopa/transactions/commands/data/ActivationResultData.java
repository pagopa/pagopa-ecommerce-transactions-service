package it.pagopa.transactions.commands.data;

import it.pagopa.generated.transactions.server.model.ActivationResultRequestDto;
import it.pagopa.transactions.domain.TransactionActivated;

public record ActivationResultData(
		TransactionActivated transactionInitialized,
		ActivationResultRequestDto activationResultData
) {
}