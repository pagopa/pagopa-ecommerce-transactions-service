package it.pagopa.transactions.commands.data;

import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.domain.TransactionInitialized;
import it.pagopa.transactions.domain.TransactionWithRequestedAuthorization;

public record UpdateAuthorizationStatusData(
        TransactionInitialized transaction,
        UpdateAuthorizationRequestDto updateAuthorizationRequest
) {}
