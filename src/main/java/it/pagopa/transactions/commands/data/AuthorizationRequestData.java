package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationRequestDetailsDto;

import java.util.Optional;

public record AuthorizationRequestData(
        TransactionId transactionId,
        int fee,
        String paymentInstrumentId,
        String pspId,
        String paymentTypeCode,
        String brokerName,
        String pspChannelCode,
        String paymentMethodName,
        String paymentMethodDescription,
        String pspBusinessName,
        Boolean pspOnUs,
        String paymentGatewayId,

        Optional<String> sessionId,
        RequestAuthorizationRequestDetailsDto authDetails
) {
}
