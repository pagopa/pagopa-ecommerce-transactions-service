package it.pagopa.transactions.projections.handlers.v2;

import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.ProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component(ClosureErrorProjectionHandler.QUALIFIER_NAME)
@Slf4j
public class ClosureErrorProjectionHandler
        implements
        ProjectionHandler<it.pagopa.ecommerce.commons.documents.v2.TransactionClosureErrorEvent, Mono<it.pagopa.ecommerce.commons.documents.v2.Transaction>> {

    public static final String QUALIFIER_NAME = "ClosureErrorProjectionHandlerV2";
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Override
    public Mono<it.pagopa.ecommerce.commons.documents.v2.Transaction> handle(
                                                                             it.pagopa.ecommerce.commons.documents.v2.TransactionClosureErrorEvent event
    ) {
        return transactionsViewRepository.findById(event.getTransactionId())
                .switchIfEmpty(
                        Mono.error(new TransactionNotFoundException(event.getTransactionId()))
                )
                .cast(it.pagopa.ecommerce.commons.documents.v2.Transaction.class)
                .flatMap(transactionDocument -> {
                    transactionDocument.setStatus(TransactionStatusDto.CLOSURE_ERROR);
                    return transactionsViewRepository.save(transactionDocument);
                });
    }
}
