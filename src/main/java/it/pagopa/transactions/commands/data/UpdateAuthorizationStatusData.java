package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.domain.TransactionActivated;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;

public record UpdateAuthorizationStatusData(
        TransactionActivated transaction,
        UpdateAuthorizationRequestDto updateAuthorizationRequest
) {
}
