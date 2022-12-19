package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.Transaction;
import it.pagopa.ecommerce.commons.documents.TransactionClosureErrorEvent;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class ClosureErrorProjectionHandler implements ProjectionHandler<TransactionClosureErrorEvent, Mono<Transaction>> {
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<Transaction> handle(TransactionClosureErrorEvent event) {
        return transactionsViewRepository.findById(event.getTransactionId())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(event.getNoticeCodes().get(0).getPaymentToken())))
                .flatMap(transactionDocument -> {
                    transactionDocument.setStatus(TransactionStatusDto.CLOSURE_ERROR);
                    return transactionsViewRepository.save(transactionDocument);
                });
    }
}
