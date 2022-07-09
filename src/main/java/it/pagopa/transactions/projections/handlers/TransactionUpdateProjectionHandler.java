package it.pagopa.transactions.projections.handlers;

import it.pagopa.transactions.documents.TransactionStatusUpdatedEvent;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.ZonedDateTime;
import java.util.UUID;

@Component
@Slf4j
public class TransactionUpdateProjectionHandler implements ProjectionHandler<TransactionStatusUpdatedEvent, Mono<Transaction>> {
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<Transaction> handle(TransactionStatusUpdatedEvent data) {
        return transactionsViewRepository.findById(data.getTransactionId())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(data.getPaymentToken())))
                .flatMap(transactionDocument -> {
                    transactionDocument.setStatus(data.getData().getNewTransactionStatus());
                    return transactionsViewRepository.save(transactionDocument);
                })
                .map(transactionDocument -> new Transaction(
                        new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
                        new PaymentToken(transactionDocument.getPaymentToken()),
                        new RptId(transactionDocument.getRptId()),
                        new TransactionDescription(transactionDocument.getDescription()),
                        new TransactionAmount(transactionDocument.getAmount()),
                        ZonedDateTime.parse(transactionDocument.getCreationDate()),
                        transactionDocument.getStatus()
                ));
    }
}
