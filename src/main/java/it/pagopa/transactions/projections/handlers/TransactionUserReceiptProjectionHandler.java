package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserReceiptRequestedEvent;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class TransactionUserReceiptProjectionHandler
        implements ProjectionHandler<TransactionUserReceiptRequestedEvent, Mono<Transaction>> {
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<Transaction> handle(TransactionUserReceiptRequestedEvent data) {
        return transactionsViewRepository.findById(data.getTransactionId())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(data.getTransactionId())))
                .cast(it.pagopa.ecommerce.commons.documents.v1.Transaction.class)
                .flatMap(transactionDocument -> {
                    TransactionStatusDto newStatus = TransactionStatusDto.NOTIFICATION_REQUESTED;
                    transactionDocument.setStatus(newStatus);
                    transactionDocument.setSendPaymentResultOutcome(data.getData().getResponseOutcome());
                    return transactionsViewRepository.save(transactionDocument);
                });
    }
}
