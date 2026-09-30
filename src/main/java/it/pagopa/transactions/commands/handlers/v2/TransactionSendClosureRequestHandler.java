package it.pagopa.transactions.commands.handlers.v2;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.v2.TransactionClosureRequestedEvent;
import it.pagopa.ecommerce.commons.domain.v2.TransactionAuthorizationCompleted;
import it.pagopa.ecommerce.commons.domain.v2.TransactionEventCode;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.transactions.commands.TransactionClosureRequestCommand;
import it.pagopa.transactions.commands.data.ClosureRequestedEventData;
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
import reactor.util.function.Tuples;

import java.time.Duration;
import java.util.Map;
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
            @Value("${closureRequestedRetryDelay.visibilityTimeoutSeconds}") int closureRequestedRetryDelaySeconds,
            TransactionsUtils transactionsUtils,
            TracingUtils tracingUtils
    ) {
        super(
                tracingUtils,
                transientQueuesTTLSeconds,
                transactionsUtils,
                transactionClosureQueueAsyncClient,
                closureRequestedRetryDelaySeconds
        );
        this.transactionEventSendClosureRequestRepository = transactionEventSendClosureRequestRepository;
    }

    @Override
    public Mono<BaseTransactionEvent<?>> handle(TransactionClosureRequestCommand command) {
        Mono<BaseTransaction> transaction = transactionsUtils.reduceV2Events(
                command.getEvents()
        );

        Mono<? extends BaseTransaction> alreadyProcessedError = transaction
                .flatMap(t -> Mono.error(new AlreadyProcessedException(t.getTransactionId())));

        return transaction.filter(
                t -> Set.of(TransactionStatusDto.AUTHORIZATION_COMPLETED, TransactionStatusDto.CLOSURE_REQUESTED)
                        .contains(t.getStatus())
        ).switchIfEmpty(alreadyProcessedError)
                .flatMap(
                        t -> Mono.just(t)
                                .filter(tr -> tr.getStatus() == TransactionStatusDto.AUTHORIZATION_COMPLETED)
                                .cast(TransactionAuthorizationCompleted.class)
                                .flatMap(trx -> {
                                    it.pagopa.ecommerce.commons.documents.v2.TransactionClosureRequestedEvent transactionClosureRequestedEvent = new it.pagopa.ecommerce.commons.documents.v2.TransactionClosureRequestedEvent(
                                            trx.getTransactionId().value()
                                    );

                                    return transactionEventSendClosureRequestRepository
                                            .insert(transactionClosureRequestedEvent)
                                            .map(e -> new ClosureRequestedEventData(Duration.ZERO, e));
                                })
                                .switchIfEmpty(
                                        Mono.just(command.getEvents().getLast())
                                                .cast(TransactionClosureRequestedEvent.class)
                                                .doOnNext(
                                                        evt -> LogTracingUtils.loggerTracingUtils()
                                                                .success()
                                                                .attributes(
                                                                        Map.of(
                                                                                LogTracingUtils.AttributeKeys.CTX_EVENT_CODE,
                                                                                TransactionEventCode.TRANSACTION_CLOSURE_REQUESTED_EVENT
                                                                                        .toString()
                                                                        )
                                                                )
                                                                .logInfo(log, "Event already present. Processing it.")
                                                )
                                                .map(
                                                        e -> new ClosureRequestedEventData(
                                                                Duration.ofSeconds(closureRequestedRetryDelaySeconds),
                                                                e
                                                        )
                                                )
                                )
                ).flatMap(
                        closureRequestedEventData -> tracingUtils.traceMono(
                                this.getClass().getSimpleName(),
                                tracingInfo -> transactionClosureQueueAsyncClient
                                        .sendMessageWithResponse(
                                                new QueueEvent<>(
                                                        closureRequestedEventData.transactionClosureRequestedEvent(),
                                                        tracingInfo
                                                ),
                                                closureRequestedEventData.visibilityTimeout(),
                                                Duration.ofSeconds(transientQueuesTTLSeconds)
                                        )
                                        .doOnNext(
                                                event -> LogTracingUtils.loggerTracingUtils()
                                                        .success()
                                                        .dependency(LogTracingUtils.STORAGE_QUEUE_DEPENDENCY)
                                                        .attributes(
                                                                Map.of(
                                                                        LogTracingUtils.AttributeKeys.CTX_EVENT_CODE,
                                                                        closureRequestedEventData
                                                                                .transactionClosureRequestedEvent()
                                                                                .getEventCode()
                                                                )
                                                        )
                                                        .details(
                                                                Map.of(
                                                                        "send_reason",
                                                                        "New transaction authorization event",
                                                                        "visibility_timeout",
                                                                        closureRequestedEventData.visibilityTimeout()
                                                                                .toString(),
                                                                        "ttl",
                                                                        Duration.ofSeconds(transientQueuesTTLSeconds)
                                                                                .toString()
                                                                )
                                                        )
                                                        .logInfo(log, "Event successfully sent to queue")
                                        )
                                        .doOnError(
                                                exception -> LogTracingUtils.loggerTracingUtils()
                                                        .failure()
                                                        .dependency(LogTracingUtils.STORAGE_QUEUE_DEPENDENCY)
                                                        .attributes(
                                                                Map.of(
                                                                        LogTracingUtils.AttributeKeys.CTX_EVENT_CODE,
                                                                        TransactionEventCode.TRANSACTION_CLOSURE_REQUESTED_EVENT
                                                                                .toString()
                                                                )
                                                        )
                                                        .logError(log, exception, "Error on queueing domain event")
                                        )
                        )
                                .thenReturn(closureRequestedEventData.transactionClosureRequestedEvent())

                );

    }
}
