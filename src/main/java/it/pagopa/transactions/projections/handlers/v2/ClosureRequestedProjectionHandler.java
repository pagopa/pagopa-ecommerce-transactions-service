package it.pagopa.transactions.projections.handlers.v2;

import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.ProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import java.time.ZonedDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class ClosureRequestedProjectionHandler implements
        ProjectionHandler<it.pagopa.ecommerce.commons.documents.v2.TransactionClosureRequestedEvent, Mono<it.pagopa.ecommerce.commons.documents.v2.Transaction>> {

    private final TransactionsViewRepository transactionsViewRepository;

    private final boolean transactionsviewUpdateEnabled;

    @Autowired
    public ClosureRequestedProjectionHandler(
            TransactionsViewRepository transactionsViewRepository,
            @Value("${transactionsview.update.enabled}") boolean transactionsviewUpdateEnabled
    ) {
        this.transactionsViewRepository = transactionsViewRepository;
        this.transactionsviewUpdateEnabled = transactionsviewUpdateEnabled;
    }

    @Override
    public Mono<it.pagopa.ecommerce.commons.documents.v2.Transaction> handle(
                                                                             it.pagopa.ecommerce.commons.documents.v2.TransactionClosureRequestedEvent transactionClosureRequestedEvent
    ) {
        return transactionsViewRepository.findById(transactionClosureRequestedEvent.getTransactionId())
                .cast(it.pagopa.ecommerce.commons.documents.v2.Transaction.class)
                .switchIfEmpty(
                        Mono.error(
                                new TransactionNotFoundException(
                                        transactionClosureRequestedEvent.getTransactionId()
                                )
                        )
                )
                .flatMap(
                        transactionDocument -> conditionallySaveTransactionView(
                                transactionDocument,
                                transactionClosureRequestedEvent
                        )
                );
    }

    private Mono<it.pagopa.ecommerce.commons.documents.v2.Transaction> conditionallySaveTransactionView(
                                                                                                        it.pagopa.ecommerce.commons.documents.v2.Transaction transactionDocument,
                                                                                                        it.pagopa.ecommerce.commons.documents.v2.TransactionClosureRequestedEvent transactionClosureRequestedEvent
    ) {
        transactionDocument.setStatus(TransactionStatusDto.CLOSURE_REQUESTED);
        transactionDocument.setLastProcessedEventAt(
                ZonedDateTime.parse(transactionClosureRequestedEvent.getCreationDate()).toInstant()
                        .toEpochMilli()
        );
        if (transactionsviewUpdateEnabled) {
            return transactionsViewRepository.save(transactionDocument);
        } else {
            return Mono.just(transactionDocument);
        }
    }

}
