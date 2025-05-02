package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v2.Email;
import it.pagopa.ecommerce.commons.domain.v2.PaymentNotice;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationRequestDetailsDto;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record AuthorizationRequestData(
        TransactionId transactionId,

        List<PaymentNotice> paymentNotices,

        Confidential<Email> email,

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
        Optional<String> contractId,
        String brand,
        RequestAuthorizationRequestDetailsDto authDetails,

        String asset,

        Optional<Map<String, String>> brandAssets,
        String idBundle
) {
}
