package it.pagopa.transactions.commands.data;

import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.domain.TransactionActivated;

public record UpdateAuthorizationStatusData(
		TransactionActivated transaction,
        UpdateAuthorizationRequestDto updateAuthorizationRequest
) {}
