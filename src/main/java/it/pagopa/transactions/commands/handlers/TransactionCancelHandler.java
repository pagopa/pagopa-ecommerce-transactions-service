package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserCanceledEvent;
import it.pagopa.ecommerce.commons.domain.v1.Transaction;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.commands.TransactionCancelCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
@Slf4j
public class TransactionCancelHandler extends
        BaseHandler<TransactionCancelCommand, Mono<TransactionUserCanceledEvent>> {

    private final TransactionsEventStoreRepository<Void> transactionEventUserCancelStoreRepository;
    private final QueueAsyncClient transactionClosureQueueAsyncClient;

    @Autowired
    public TransactionCancelHandler(
            TransactionsEventStoreRepository<Object> eventStoreRepository,
            TransactionsEventStoreRepository<Void> transactionEventUserCancelStoreRepository,
            @Qualifier("transactionClosureQueueAsyncClient") QueueAsyncClient transactionClosureQueueAsyncClient
    ) {
        super(eventStoreRepository);
        this.transactionEventUserCancelStoreRepository = transactionEventUserCancelStoreRepository;
        this.transactionClosureQueueAsyncClient = transactionClosureQueueAsyncClient;
    }

    @Override
    public Mono<TransactionUserCanceledEvent> handle(TransactionCancelCommand command) {
        Mono<Transaction> transaction = replayTransactionEvents(
                command.getData().transaction().getTransactionId().value()
        );
        Mono<? extends BaseTransaction> alreadyProcessedError = transaction
                .cast(BaseTransaction.class)
                .doOnNext(t -> log.error("Error: requesting cancel for transaction in state {}", t.getStatus()))
                .flatMap(t -> Mono.error(new AlreadyProcessedException(t.getTransactionId())));

        return transaction
                .cast(BaseTransaction.class)
                .filter(
                        t -> t.getStatus() == TransactionStatusDto.ACTIVATED
                )
                .switchIfEmpty(alreadyProcessedError)
                .flatMap(

                        t -> {
                            TransactionUserCanceledEvent userCanceledEvent = new TransactionUserCanceledEvent(
                                    t.getTransactionId().toString()
                            );
                            return transactionEventUserCancelStoreRepository.save(userCanceledEvent)
                                    .then(
                                            transactionClosureQueueAsyncClient.sendMessageWithResponse(
                                                    BinaryData.fromObject(userCanceledEvent),
                                                    Duration.ZERO,
                                                    null
                                            )
                                    )
                                    .then(Mono.just(userCanceledEvent))
                                    .doOnError(
                                            exception -> log.error(
                                                    "Error to generate event TRANSACTION_USER_CANCELED_EVENT for transactionId {} - error {}",
                                                    userCanceledEvent.getTransactionId(),
                                                    exception.getMessage()
                                            )
                                    )
                                    .doOnNext(
                                            event -> log.info(
                                                    "Generated event TRANSACTION_USER_CANCELED_EVENT for transactionId {}",
                                                    event.getTransactionId()
                                            )
                                    );
                        }
                );

    }
}
