package it.pagopa.transactions.commands.data;

import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.domain.TransactionInitialized;
import it.pagopa.transactions.domain.pojos.BaseTransaction;

public record ClosureSendData(
        BaseTransaction transaction,
        UpdateAuthorizationRequestDto updateAuthorizationRequest
) {}
