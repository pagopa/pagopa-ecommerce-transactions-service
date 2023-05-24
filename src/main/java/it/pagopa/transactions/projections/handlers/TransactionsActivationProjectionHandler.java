package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
@Slf4j
public class TransactionsActivationProjectionHandler
        implements ProjectionHandler<TransactionActivatedEvent, Mono<TransactionActivated>> {

    @Autowired
    private TransactionsViewRepository viewEventStoreRepository;

    @Override
    public Mono<TransactionActivated> handle(TransactionActivatedEvent event) {
        TransactionActivatedData data = event.getData();
        TransactionId transactionId = new TransactionId(event.getTransactionId());
        List<PaymentNotice> paymentNoticeList = data.getPaymentNotices().stream().map(
                paymentNoticeData -> new PaymentNotice(
                        new PaymentToken(paymentNoticeData.getPaymentToken()),
                        new RptId(paymentNoticeData.getRptId()),
                        new TransactionAmount(paymentNoticeData.getAmount()),
                        new TransactionDescription(paymentNoticeData.getDescription()),
                        new PaymentContextCode(paymentNoticeData.getPaymentContextCode()),
                        paymentNoticeData.getTransferList().stream().map(
                                transfer -> new PaymentTransferInfo(
                                        transfer.getPaFiscalCode(),
                                        transfer.getDigitalStamp(),
                                        transfer.getTransferAmount(),
                                        transfer.getTransferCategory()
                                )
                        ).toList()
                )
        ).toList();
        Confidential<Email> email = event.getData().getEmail();
        String faultCode = event.getData().getFaultCode();
        String faultCodeString = event.getData().getFaultCodeString();
        ClientId clientId = event.getData().getClientId();
        String idCart = event.getData().getIdCart();
        int paymentTokenValiditySeconds = event.getData().getPaymentTokenValiditySeconds();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                paymentNoticeList,
                email,
                faultCode,
                faultCodeString,
                clientId,
                idCart,
                paymentTokenValiditySeconds
        );

        it.pagopa.ecommerce.commons.documents.v1.Transaction transactionDocument = it.pagopa.ecommerce.commons.documents.v1.Transaction
                .from(transaction);

        return viewEventStoreRepository
                .save(transactionDocument)
                .doOnNext(t -> log.info("Transactions update view for transactionId: {}", t.getTransactionId()))
                .thenReturn(transaction);
    }
}
