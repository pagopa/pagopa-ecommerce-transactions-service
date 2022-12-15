package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

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
        PaymentToken paymentToken = new PaymentToken(event.getPaymentToken());
        RptId rptId = new RptId(event.getRptId());
        TransactionDescription description = new TransactionDescription(data.getDescription());
        TransactionAmount amount = new TransactionAmount(data.getAmount());
        Email email = new Email(event.getData().getEmail());
        String faultCode = event.getData().getFaultCode();
        String faultCodeString = event.getData().getFaultCodeString();

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                email,
                faultCode,
                faultCodeString,
                TransactionStatusDto.ACTIVATED
        );

        it.pagopa.ecommerce.commons.documents.Transaction transactionDocument = it.pagopa.ecommerce.commons.documents.Transaction
                .from(transaction);

        return viewEventStoreRepository
                .save(transactionDocument)
                .doOnNext(t -> log.info("Transactions update view for rptId: {}", t.getRptId()))
                .thenReturn(transaction);
    }
}
