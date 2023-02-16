package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithPaymentToken;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;

public record ClosureSendData(
        BaseTransactionWithPaymentToken transaction,
        UpdateAuthorizationRequestDto updateAuthorizationRequest
) {
}
