package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.Transaction.OriginType;
import it.pagopa.ecommerce.commons.documents.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class TransactionsActivationProjectionHandler
        implements ProjectionHandler<TransactionActivatedEvent, Mono<TransactionActivated>> {

    @Autowired
    private TransactionsViewRepository viewEventStoreRepository;

    @Override
    public Mono<TransactionActivated> handle(TransactionActivatedEvent event) {
        TransactionActivatedData data = event.getData();
        TransactionId transactionId = new TransactionId(UUID.fromString(event.getTransactionId()));
        List<PaymentNotice> paymentNoticeList = data.getPaymentNotices().stream().map(
                paymentNoticeData -> new PaymentNotice(
                        new PaymentToken(paymentNoticeData.getPaymentToken()),
                        new RptId(paymentNoticeData.getRptId()),
                        new TransactionAmount(paymentNoticeData.getAmount()),
                        new TransactionDescription(paymentNoticeData.getDescription()),
                        new PaymentContextCode(paymentNoticeData.getPaymentContextCode())
                )
        ).toList();
        Email email = new Email(event.getData().getEmail());
        String faultCode = event.getData().getFaultCode();
        String faultCodeString = event.getData().getFaultCodeString();
        OriginType originType = event.getData().getOriginType();

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                paymentNoticeList,
                email,
                faultCode,
                faultCodeString,
                TransactionStatusDto.ACTIVATED,
                originType
        );

        it.pagopa.ecommerce.commons.documents.Transaction transactionDocument = it.pagopa.ecommerce.commons.documents.Transaction
                .from(transaction);

        return viewEventStoreRepository
                .save(transactionDocument)
                .doOnNext(t -> log.info("Transactions update view for transactionId: {}", t.getTransactionId()))
                .thenReturn(transaction);
    }
}
