package it.pagopa.transactions.commands.data;

import it.pagopa.generated.transactions.server.model.ActivationResultDto;
import it.pagopa.transactions.domain.Transaction;

public record ActivationResultData(
		Transaction transaction,
		ActivationResultDto activationResultData
) {
}
