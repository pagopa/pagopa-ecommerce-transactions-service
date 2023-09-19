package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.TransactionRefundRequestedEvent;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class RefundRequestProjectionHandler
        implements ProjectionHandler<TransactionRefundRequestedEvent, Mono<Transaction>> {

    private final TransactionsViewRepository transactionsViewRepository;

    @Autowired
    public RefundRequestProjectionHandler(
            TransactionsViewRepository transactionsViewRepository
    ) {
        this.transactionsViewRepository = transactionsViewRepository;
    }

    @Override
    public Mono<Transaction> handle(TransactionRefundRequestedEvent transactionRefundRequestedEvent) {
        return transactionsViewRepository.findById(transactionRefundRequestedEvent.getTransactionId())
                .switchIfEmpty(
                        Mono.error(
                                new TransactionNotFoundException(
                                        transactionRefundRequestedEvent.getTransactionId()
                                )
                        )
                )
                .cast(it.pagopa.ecommerce.commons.documents.v1.Transaction.class)
                .flatMap(transactionDocument -> {
                    transactionDocument.setStatus(TransactionStatusDto.REFUND_REQUESTED);
                    return transactionsViewRepository.save(transactionDocument);
                });
    }
}
