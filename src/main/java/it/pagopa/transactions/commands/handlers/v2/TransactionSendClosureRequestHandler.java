package it.pagopa.transactions.commands.handlers.v2;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.v2.TransactionClosureRequestedEvent;
import it.pagopa.ecommerce.commons.domain.v2.TransactionAuthorizationCompleted;
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

    @Override
    public Mono<BaseTransactionEvent<?>> handle(TransactionClosureRequestCommand command) {
        Mono<BaseTransaction> transaction = transactionsUtils.reduceV2Events(
                command.getEvents()
        );

        Mono<? extends BaseTransaction> alreadyProcessedError = transaction
                .doOnNext(t -> log.error("Error: requesting async closure for transaction in state {}", t.getStatus()))
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
                                            .save(transactionClosureRequestedEvent);
                                })
                                .switchIfEmpty(
                                        Mono.just(command.getEvents().getLast())
                                                .cast(TransactionClosureRequestedEvent.class)
                                )
                ).flatMap(
                        event -> tracingUtils.traceMono(
                                this.getClass().getSimpleName(),
                                tracingInfo -> transactionClosureQueueAsyncClient
                                        .sendMessageWithResponse(
                                                new QueueEvent<>(event, tracingInfo),
                                                Duration.ZERO,
                                                Duration.ofSeconds(transientQueuesTTLSeconds)
                                        )
                        ).thenReturn(event)
                                .doOnError(
                                        exception -> log.error(
                                                "Error to generate event TRANSACTION_CLOSURE_REQUESTED_EVENT for transactionId {} - error {}",
                                                event.getTransactionId(),
                                                exception.getMessage()
                                        )
                                )
                                .doOnNext(
                                        evt -> log.info(
                                                "Generated event TRANSACTION_CLOSURE_REQUESTED_EVENT for transactionId {}",
                                                evt.getTransactionId()
                                        )
                                )

                );

    }
}
