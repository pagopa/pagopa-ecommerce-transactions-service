package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;

public record UpdateAuthorizationStatusData(
        TransactionId transactionId,
        String transactionStatus,
        UpdateAuthorizationRequestDto updateAuthorizationRequest
) {
}
