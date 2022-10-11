package it.pagopa.transactions.commands.data;

import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithPaymentToken;

public record ClosureSendData(
		BaseTransactionWithPaymentToken transaction,
        UpdateAuthorizationRequestDto updateAuthorizationRequest
) {}
