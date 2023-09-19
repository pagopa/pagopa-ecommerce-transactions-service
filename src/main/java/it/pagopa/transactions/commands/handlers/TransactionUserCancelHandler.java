package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserCanceledEvent;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.transactions.commands.TransactionUserCancelCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
@Slf4j
public class TransactionUserCancelHandler implements
        CommandHandler<TransactionUserCancelCommand, Mono<TransactionUserCanceledEvent>> {

    private final TransactionsEventStoreRepository<Void> transactionEventUserCancelStoreRepository;
    private final QueueAsyncClient transactionClosureQueueAsyncClient;

    private final TransactionsUtils transactionsUtils;

    private final int transientQueuesTTLSeconds;

    private final TracingUtils tracingUtils;

    @Autowired
    public TransactionUserCancelHandler(
            TransactionsEventStoreRepository<Void> transactionEventUserCancelStoreRepository,
            @Qualifier("transactionClosureQueueAsyncClient") QueueAsyncClient transactionClosureQueueAsyncClient,
            TransactionsUtils transactionsUtils,
            @Value("${azurestorage.queues.transientQueues.ttlSeconds}") int transientQueuesTTLSeconds,
            TracingUtils tracingUtils
    ) {
        this.transactionsUtils = transactionsUtils;
        this.transactionEventUserCancelStoreRepository = transactionEventUserCancelStoreRepository;
        this.transactionClosureQueueAsyncClient = transactionClosureQueueAsyncClient;
        this.transientQueuesTTLSeconds = transientQueuesTTLSeconds;
        this.tracingUtils = tracingUtils;
    }

    @Override
    public Mono<TransactionUserCanceledEvent> handle(TransactionUserCancelCommand command) {
        Mono<BaseTransaction> transaction = transactionsUtils.reduceEventsV1(
                command.getData()
        );

        return transaction
                .filter(tx -> tx.getStatus().equals(TransactionStatusDto.ACTIVATED))
                .switchIfEmpty(Mono.error(new AlreadyProcessedException(command.getData())))
                .flatMap(
                        t -> {
                            TransactionUserCanceledEvent userCanceledEvent = new TransactionUserCanceledEvent(
                                    t.getTransactionId().value()
                            );
                            return transactionEventUserCancelStoreRepository.save(userCanceledEvent)
                                    .flatMap(
                                            event -> tracingUtils.traceMono(
                                                    this.getClass().getSimpleName(),
                                                    tracingInfo -> transactionClosureQueueAsyncClient
                                                            .sendMessageWithResponse(
                                                                    new QueueEvent<>(userCanceledEvent, tracingInfo),
                                                                    Duration.ZERO,
                                                                    Duration.ofSeconds(transientQueuesTTLSeconds)
                                                            )
                                            )
                                    )
                                    .thenReturn(userCanceledEvent)
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
