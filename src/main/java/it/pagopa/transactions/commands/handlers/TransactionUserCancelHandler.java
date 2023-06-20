package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserCanceledEvent;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
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

    private final int transientQueuesTTLMinutes;

    @Autowired
    public TransactionUserCancelHandler(
            TransactionsEventStoreRepository<Void> transactionEventUserCancelStoreRepository,
            @Qualifier("transactionClosureQueueAsyncClient") QueueAsyncClient transactionClosureQueueAsyncClient,
            TransactionsUtils transactionsUtils,
            @Value("${azurestorage.queues.transientQueues.ttlMinutes}") int transientQueuesTTLMinutes
    ) {
        this.transactionsUtils = transactionsUtils;
        this.transactionEventUserCancelStoreRepository = transactionEventUserCancelStoreRepository;
        this.transactionClosureQueueAsyncClient = transactionClosureQueueAsyncClient;
        this.transientQueuesTTLMinutes = transientQueuesTTLMinutes;
    }

    @Override
    public Mono<TransactionUserCanceledEvent> handle(TransactionUserCancelCommand command) {
        Mono<BaseTransaction> transaction = transactionsUtils.reduceEvents(
                command.getData()
        );

        return transaction
                .filter(tx -> tx.getStatus().equals(TransactionStatusDto.ACTIVATED))
                .switchIfEmpty(Mono.error(new AlreadyProcessedException(command.getData())))
                .flatMap(
                        t -> {
                            TransactionUserCanceledEvent userCanceledEvent = new TransactionUserCanceledEvent(
                                    t.getTransactionId().value().toString()
                            );
                            return transactionEventUserCancelStoreRepository.save(userCanceledEvent)
                                    .flatMap(
                                            event -> transactionClosureQueueAsyncClient.sendMessageWithResponse(
                                                    BinaryData.fromObject(userCanceledEvent),
                                                    Duration.ZERO,
                                                    Duration.ofMinutes(transientQueuesTTLMinutes)
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
