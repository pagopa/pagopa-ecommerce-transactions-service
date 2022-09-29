package it.pagopa.transactions.commands.data;

import it.pagopa.generated.transactions.server.model.ActivationResultRequestDto;
import it.pagopa.transactions.domain.TransactionActivated;
import it.pagopa.transactions.domain.TransactionActivationRequested;

public record ActivationResultData(
		TransactionActivationRequested transactionActivationRequested,
		ActivationResultRequestDto activationResultData
) {
}