package it.pagopa.transactions.commands.data;

import it.pagopa.generated.transactions.server.model.ActivationResultRequestDto;
import it.pagopa.transactions.domain.Transaction;
import it.pagopa.transactions.domain.TransactionInitialized;

public record ActivationResultData(
		TransactionInitialized transactionInitialized,
		ActivationResultRequestDto activationResultData
) {
}