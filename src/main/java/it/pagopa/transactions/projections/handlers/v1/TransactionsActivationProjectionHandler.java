package it.pagopa.transactions.projections.handlers.v1;

import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.transactions.projections.handlers.ProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component(it.pagopa.transactions.projections.handlers.v1.TransactionsActivationProjectionHandler.QUALIFIER_NAME)
@Slf4j
public class TransactionsActivationProjectionHandler
        implements
        ProjectionHandler<it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent, Mono<it.pagopa.ecommerce.commons.domain.v1.TransactionActivated>> {

    public static final String QUALIFIER_NAME = "TransactionsActivationProjectionHandlerV1";
    @Autowired
    private TransactionsViewRepository viewEventStoreRepository;

    @Override
    public Mono<it.pagopa.ecommerce.commons.domain.v1.TransactionActivated> handle(
                                                                                   it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent event
    ) {
        it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedData data = event.getData();
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
                        ).toList(),
                        paymentNoticeData.isAllCCP()
                )
        ).toList();
        Confidential<Email> email = event.getData().getEmail();
        String faultCode = event.getData().getFaultCode();
        String faultCodeString = event.getData().getFaultCodeString();
        it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId clientId = event.getData().getClientId();
        String idCart = event.getData().getIdCart();
        int paymentTokenValiditySeconds = event.getData().getPaymentTokenValiditySeconds();
        it.pagopa.ecommerce.commons.domain.v1.TransactionActivated transaction = new it.pagopa.ecommerce.commons.domain.v1.TransactionActivated(
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
