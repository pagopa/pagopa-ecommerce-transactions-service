package it.pagopa.transactions.commands.handlers.v2;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.transactions.commands.TransactionClosureRequestCommand;
import it.pagopa.transactions.commands.handlers.TransactionSendClosureRequestHandlerCommon;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.Duration;
import java.util.Set;

@Component
@Slf4j
public class TransactionSendClosureRequestHandler extends TransactionSendClosureRequestHandlerCommon {

    private final TransactionsEventStoreRepository<Void> transactionEventSendClosureRequestRepository;

    @Autowired
    protected TransactionSendClosureRequestHandler(
            TransactionsEventStoreRepository<Void> transactionEventSendClosureRequestRepository,
            @Qualifier("transactionClosureQueueAsyncClientV2") QueueAsyncClient transactionClosureQueueAsyncClient,
            @Value("${azurestorage.queues.transientQueues.ttlSeconds}") int transientQueuesTTLSeconds,
            TransactionsUtils transactionsUtils,
            TracingUtils tracingUtils
    ) {
        super(tracingUtils, transientQueuesTTLSeconds, transactionsUtils, transactionClosureQueueAsyncClient);
        this.transactionEventSendClosureRequestRepository = transactionEventSendClosureRequestRepository;
    }

    public Mono<BaseTransactionEvent<?>> handle(TransactionClosureRequestCommand command) {
        return transactionsUtils.reduceV2Events(command.getEvents())
                .flatMap(transaction -> {
                    TransactionStatusDto status = transaction.getStatus();

                    // 1. Validazione Stato
                    if (!isValidStatus(status)) {
                        log.error("Error: requesting async closure for transaction in state {}", status);
                        return Mono.error(new AlreadyProcessedException(transaction.getTransactionId()));
                    }

                    // 2. Definizione dell'evento e del timeout (Logica di business estratta)
                    return saveEventIfNeeded(transaction, command)
                            .flatMap(tuple -> sendToQueue(tuple.getT2(), tuple.getT1()));
                });
    }

    private boolean isValidStatus(TransactionStatusDto status) {
        return Set.of(TransactionStatusDto.AUTHORIZATION_COMPLETED, TransactionStatusDto.CLOSURE_REQUESTED)
                .contains(status);
    }

    private Mono<Tuple2<Duration, BaseTransactionEvent<?>>> saveEventIfNeeded(
                                                                              BaseTransaction transaction,
                                                                              TransactionClosureRequestCommand command
    ) {
        if (transaction.getStatus() == TransactionStatusDto.AUTHORIZATION_COMPLETED) {
            var transactionClosureRequestedEvent = new it.pagopa.ecommerce.commons.documents.v2.TransactionClosureRequestedEvent(
                    transaction.getTransactionId().value()
            );
            return transactionEventSendClosureRequestRepository.save(transactionClosureRequestedEvent)
                    .map(e -> Tuples.of(Duration.ZERO, e));
        }

        log.info(
                "event TRANSACTION_CLOSURE_REQUESTED_EVENT for transactionId {} already present. Processing it.",
                transaction.getTransactionId()
        );

        var lastEvent = (BaseTransactionEvent<?>) command.getEvents().getLast();
        return Mono.just(Tuples.of(Duration.ofSeconds(30), lastEvent));
    }

    private Mono<BaseTransactionEvent<?>> sendToQueue(
                                                      BaseTransactionEvent<?> event,
                                                      Duration visibilityTimeout
    ) {
        return tracingUtils.traceMono(
                this.getClass().getSimpleName(),
                tracingInfo -> transactionClosureQueueAsyncClient.sendMessageWithResponse(
                        new QueueEvent<>(event, tracingInfo),
                        visibilityTimeout,
                        Duration.ofSeconds(transientQueuesTTLSeconds)
                )
        )
                .doOnNext(
                        response -> log.info(
                                "Generated and processed event {} for transactionId {}",
                                event.getClass().getSimpleName(),
                                event.getTransactionId()
                        )
                )
                .doOnError(
                        e -> log.error(
                                "Error processing event for transactionId {} - {}",
                                event.getTransactionId(),
                                e.getMessage()
                        )
                )
                .thenReturn(event);
    }
}
