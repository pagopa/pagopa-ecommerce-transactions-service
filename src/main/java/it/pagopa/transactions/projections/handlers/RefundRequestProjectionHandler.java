package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.TransactionRefundRequestedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserCanceledEvent;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

public class RefundRequestProjectionHandler
        implements ProjectionHandler<TransactionRefundRequestedEvent, Mono<Transaction>> {

    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

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
                .flatMap(transactionDocument -> {
                    transactionDocument.setStatus(TransactionStatusDto.REFUND_REQUESTED);
                    return transactionsViewRepository.save(transactionDocument);
                });
    }
}
