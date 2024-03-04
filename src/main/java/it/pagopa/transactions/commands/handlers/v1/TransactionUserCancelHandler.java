package it.pagopa.transactions.commands.handlers.v1;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.transactions.commands.TransactionUserCancelCommand;
import it.pagopa.transactions.commands.handlers.TransactionUserCancelHandlerCommon;
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

@Component(TransactionUserCancelHandler.QUALIFIER_NAME)
@Slf4j
public class TransactionUserCancelHandler extends TransactionUserCancelHandlerCommon {

    public static final String QUALIFIER_NAME = "TransactionUserCancelHandlerV1";
    private final TransactionsEventStoreRepository<Void> transactionEventUserCancelStoreRepository;

    @Autowired
    public TransactionUserCancelHandler(
            TransactionsEventStoreRepository<Void> transactionEventUserCancelStoreRepository,
            @Qualifier("transactionClosureQueueAsyncClientV1") QueueAsyncClient transactionClosureQueueAsyncClient,
            TransactionsUtils transactionsUtils,
            @Value("${azurestorage.queues.transientQueues.ttlSeconds}") int transientQueuesTTLSeconds,
            TracingUtils tracingUtils
    ) {
        super(tracingUtils, transactionsUtils, transientQueuesTTLSeconds, transactionClosureQueueAsyncClient);
        this.transactionEventUserCancelStoreRepository = transactionEventUserCancelStoreRepository;
    }

    @Override
    public Mono<BaseTransactionEvent<?>> handle(TransactionUserCancelCommand command) {
        Mono<it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction> transaction = transactionsUtils
                .reduceEventsV1(
                        command.getData()
                );

        return transaction
                .filter(tx -> tx.getStatus().equals(TransactionStatusDto.ACTIVATED))
                .switchIfEmpty(Mono.error(new AlreadyProcessedException(command.getData())))
                .flatMap(
                        t -> {
                            it.pagopa.ecommerce.commons.documents.v1.TransactionUserCanceledEvent userCanceledEvent = new it.pagopa.ecommerce.commons.documents.v1.TransactionUserCanceledEvent(
                                    t.getTransactionId().value()
                            );
                            return transactionEventUserCancelStoreRepository.save(userCanceledEvent)
                                    .flatMap(
                                            event -> transactionClosureQueueAsyncClient
                                                    .sendMessageWithResponse(
                                                            new QueueEvent<>(userCanceledEvent, null),
                                                            Duration.ZERO,
                                                            Duration.ofSeconds(transientQueuesTTLSeconds)
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
