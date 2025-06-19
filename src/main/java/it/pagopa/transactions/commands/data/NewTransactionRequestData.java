package it.pagopa.transactions.commands.data;

import it.pagopa.ecommerce.commons.annotations.ValueObject;
import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v2.Email;
import it.pagopa.ecommerce.commons.domain.v2.PaymentNotice;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@ValueObject
public record NewTransactionRequestData(
        String idCard,
        Mono<Confidential<Email>> email,
        String orderId,
        UUID correlationId,
        List<PaymentNotice> paymentNoticeList
) {

}
