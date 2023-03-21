package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserCanceledEvent;
import it.pagopa.ecommerce.commons.domain.v1.Transaction;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionExpired;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithPaymentToken;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.commands.TransactionUserCancelCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.qpid.proton.engine.BaseHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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

    @Autowired
    public TransactionUserCancelHandler(
            TransactionsEventStoreRepository<Void> transactionEventUserCancelStoreRepository,
            @Qualifier("transactionClosureQueueAsyncClient") QueueAsyncClient transactionClosureQueueAsyncClient,
            TransactionsUtils transactionsUtils
    ) {
        this.transactionsUtils = transactionsUtils;
        this.transactionEventUserCancelStoreRepository = transactionEventUserCancelStoreRepository;
        this.transactionClosureQueueAsyncClient = transactionClosureQueueAsyncClient;
    }

    @Override
    public Mono<TransactionUserCanceledEvent> handle(TransactionUserCancelCommand command) {
        Mono<BaseTransaction> transaction = transactionsUtils.reduceEvents(
                command.getData()
        );

        return transaction
                .flatMap(
                        t -> {
                            TransactionUserCanceledEvent userCanceledEvent = new TransactionUserCanceledEvent(
                                    t.getTransactionId().value().toString()
                            );
                            return transactionEventUserCancelStoreRepository.save(userCanceledEvent)
                                    .then(
                                            transactionClosureQueueAsyncClient.sendMessageWithResponse(
                                                    BinaryData.fromObject(userCanceledEvent),
                                                    Duration.ZERO,
                                                    null
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
