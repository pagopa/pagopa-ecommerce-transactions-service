package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;

public record UpdateAuthorizationStatusData(
        BaseTransaction transaction,
        UpdateAuthorizationRequestDto updateAuthorizationRequest
) {
}
