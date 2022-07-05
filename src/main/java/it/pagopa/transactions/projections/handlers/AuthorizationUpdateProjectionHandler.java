package it.pagopa.transactions.projections.handlers;

import it.pagopa.transactions.documents.Transaction;
import it.pagopa.transactions.documents.TransactionAuthorizationStatusUpdatedEvent;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class AuthorizationUpdateProjectionHandler implements ProjectionHandler<TransactionAuthorizationStatusUpdatedEvent, Mono<Transaction>> {
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<Transaction> handle(TransactionAuthorizationStatusUpdatedEvent data) {
        return transactionsViewRepository.findByPaymentToken(data.getPaymentToken())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(data.getPaymentToken())))
                .flatMap(transactionDocument -> {
                    transactionDocument.setStatus(data.getData().getNewTransactionStatus());
                    return transactionsViewRepository.save(transactionDocument);
                });
    }
}
