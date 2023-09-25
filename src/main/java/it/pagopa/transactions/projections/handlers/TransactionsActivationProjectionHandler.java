package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId;
import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.documents.v2.activation.EmptyTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.domain.v2.TransactionActivated;
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
                        ).toList(),
                        paymentNoticeData.isAllCCP()
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
                paymentTokenValiditySeconds,
                new EmptyTransactionGatewayActivationData()// TODO handle here NGP informations
        );

        it.pagopa.ecommerce.commons.documents.v2.Transaction transactionDocument = it.pagopa.ecommerce.commons.documents.v2.Transaction
                .from(transaction);

        return viewEventStoreRepository
                .save(transactionDocument)
                .doOnNext(t -> log.info("Transactions update view for transactionId: {}", t.getTransactionId()))
                .thenReturn(transaction);
    }
}
