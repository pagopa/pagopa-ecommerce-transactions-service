package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.TransactionClosedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionClosureFailedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionEvent;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class ClosureSendProjectionHandler implements ProjectionHandler<TransactionEvent<Void>, Mono<Transaction>> {
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<Transaction> handle(TransactionEvent<Void> event) {
        return transactionsViewRepository.findById(event.getTransactionId())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(event.getTransactionId())))
                .flatMap(transactionDocument -> {
                    TransactionStatusDto newStatus =
                            switch (event) {
                                case TransactionClosedEvent e -> TransactionStatusDto.CLOSED;
                                case TransactionClosureFailedEvent e -> TransactionStatusDto.UNAUTHORIZED;
                                case default -> throw new IllegalArgumentException("Unexpected event: " + event);
                            };

                    transactionDocument.setStatus(newStatus);
                    return transactionsViewRepository.save(transactionDocument);
                });
    }
}
