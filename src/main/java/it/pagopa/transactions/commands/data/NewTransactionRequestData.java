package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.annotations.ValueObject;
import it.pagopa.ecommerce.commons.domain.PaymentNotice;
import java.util.List;

@ValueObject
public record NewTransactionRequestData(
        String idCard,
        String email,
        String orderId,
        String correlationId,
        List<PaymentNotice> paymentNoticeList
) {

}
