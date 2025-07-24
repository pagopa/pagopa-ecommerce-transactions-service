package it.pagopa.transactions.projections.handlers.v2;

import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.ProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component(CancellationRequestProjectionHandler.QUALIFIER_NAME)
@Slf4j
public class CancellationRequestProjectionHandler
        implements
        ProjectionHandler<it.pagopa.ecommerce.commons.documents.v2.TransactionUserCanceledEvent, Mono<it.pagopa.ecommerce.commons.documents.v2.Transaction>> {

    public static final String QUALIFIER_NAME = "cancellationRequestProjectionHandlerV2";
    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Value("${transactionsview.update.enabled}")
    private boolean transactionsviewUpdateEnabled;

    @Override
    public Mono<it.pagopa.ecommerce.commons.documents.v2.Transaction> handle(
                                                                             it.pagopa.ecommerce.commons.documents.v2.TransactionUserCanceledEvent transactionUserCanceledEvent
    ) {

        Mono<it.pagopa.ecommerce.commons.documents.v2.Transaction> monoTransaction = transactionsViewRepository
                .findById(transactionUserCanceledEvent.getTransactionId())
                .cast(it.pagopa.ecommerce.commons.documents.v2.Transaction.class)
                .switchIfEmpty(
                        Mono.error(
                                new TransactionNotFoundException(
                                        transactionUserCanceledEvent.getTransactionId()
                                )
                        )
                );
        if (transactionsviewUpdateEnabled) {
            return monoTransaction.flatMap(transactionDocument -> {
                transactionDocument.setStatus(TransactionStatusDto.CANCELLATION_REQUESTED);
                return transactionsViewRepository.save(transactionDocument);
            });
        } else {
            return monoTransaction;
        }
    }
}
