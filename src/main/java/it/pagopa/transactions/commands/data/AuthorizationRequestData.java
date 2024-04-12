package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.Email;
import it.pagopa.ecommerce.commons.domain.PaymentNotice;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationRequestDetailsDto;
import it.pagopa.transactions.utils.PaymentSessionData;

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
        PaymentSessionData paymentSessionData,
        RequestAuthorizationRequestDetailsDto authDetails,

        String asset,

        Optional<Map<String, String>> brandAssets
) {
}
