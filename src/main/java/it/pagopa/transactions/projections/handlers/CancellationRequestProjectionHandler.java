package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserCanceledEvent;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class CancellationRequestProjectionHandler
        implements ProjectionHandler<TransactionUserCanceledEvent, Mono<Transaction>> {

    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<Transaction> handle(TransactionUserCanceledEvent transactionUserCanceledEvent) {
        return transactionsViewRepository.findById(transactionUserCanceledEvent.getTransactionId())
                .switchIfEmpty(
                        Mono.error(
                                new TransactionNotFoundException(
                                        transactionUserCanceledEvent.getTransactionId()
                                )
                        )
                )
                .flatMap(transactionDocument -> {
                    transactionDocument.setStatus(TransactionStatusDto.CANCELLATION_REQUESTED);
                    return transactionsViewRepository.save(transactionDocument);
                });
    }
}
