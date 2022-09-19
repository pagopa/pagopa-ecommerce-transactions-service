package it.pagopa.transactions.commands.data;

import it.pagopa.generated.transactions.server.model.ActivationResultRequestDto;
import it.pagopa.transactions.domain.Transaction;

public record ActivationResultData(
		Transaction transaction,
		ActivationResultRequestDto activationResultData
) {
}