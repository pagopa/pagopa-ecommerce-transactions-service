package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;

public record ClosureSendData(

        TransactionId transactionId,
        UpdateAuthorizationRequestDto updateAuthorizationRequest
) {
}
