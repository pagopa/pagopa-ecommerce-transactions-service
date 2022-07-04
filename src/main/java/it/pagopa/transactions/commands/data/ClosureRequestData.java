package it.pagopa.transactions.commands.data;

import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.domain.Transaction;

public record ClosureRequestData(
        Transaction transaction,
        UpdateAuthorizationRequestDto updateAuthorizationRequest
) {}
