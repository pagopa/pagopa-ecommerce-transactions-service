package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.domain.TransactionActivated;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationRequestDetailsDto;

public record AuthorizationRequestData(
		TransactionActivated transaction,
        int fee,
        String paymentInstrumentId,
        String pspId,
        String paymentTypeCode,
        String brokerName,
        String pspChannelCode,
        String paymentMethodName,
        String pspBusinessName,
        String paymentGatewayId,
		RequestAuthorizationRequestDetailsDto authDetails
) {}
