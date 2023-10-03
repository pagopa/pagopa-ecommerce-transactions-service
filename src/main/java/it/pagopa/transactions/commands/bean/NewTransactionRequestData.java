package it.pagopa.transactions.commands.bean;

import it.pagopa.ecommerce.commons.annotations.ValueObject;
import it.pagopa.ecommerce.commons.domain.PaymentNotice;
import java.util.List;

@ValueObject
public record NewTransactionRequestData(
        String idCard,
        String email,
        String orderId,
        List<PaymentNotice> paymentNoticeList
) {

}
