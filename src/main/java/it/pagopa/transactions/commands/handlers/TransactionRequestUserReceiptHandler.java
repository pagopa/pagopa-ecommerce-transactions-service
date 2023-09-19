package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserReceiptData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserReceiptRequestedEvent;
import it.pagopa.ecommerce.commons.domain.v1.TransactionClosed;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestDto;
import it.pagopa.transactions.commands.TransactionAddUserReceiptCommand;
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
public class TransactionRequestUserReceiptHandler
        implements CommandHandler<TransactionAddUserReceiptCommand, Mono<TransactionUserReceiptRequestedEvent>> {

    private final TransactionsEventStoreRepository<TransactionUserReceiptData> userReceiptAddedEventRepository;

    private final TransactionsUtils transactionsUtils;

    private final QueueAsyncClient transactionNotificationRequestedQueueAsyncClient;

    private final int transientQueuesTTLSeconds;

    private final TracingUtils tracingUtils;

    @Autowired
    public TransactionRequestUserReceiptHandler(
            TransactionsEventStoreRepository<TransactionUserReceiptData> userReceiptAddedEventRepository,
            TransactionsUtils transactionsUtils,
            @Qualifier(
                    "transactionNotificationRequestedQueueAsyncClient"
            ) QueueAsyncClient transactionNotificationRequestedQueueAsyncClient,
            @Value("${azurestorage.queues.transientQueues.ttlSeconds}") int transientQueuesTTLSeconds,
            TracingUtils tracingUtils
    ) {
        this.userReceiptAddedEventRepository = userReceiptAddedEventRepository;
        this.transactionsUtils = transactionsUtils;
        this.transactionNotificationRequestedQueueAsyncClient = transactionNotificationRequestedQueueAsyncClient;
        this.transientQueuesTTLSeconds = transientQueuesTTLSeconds;
        this.tracingUtils = tracingUtils;
    }

    @Override
    public Mono<TransactionUserReceiptRequestedEvent> handle(TransactionAddUserReceiptCommand command) {
        Mono<BaseTransaction> transaction = transactionsUtils.reduceEventsV1(
                command.getData().transaction().getTransactionId()
        );

        Mono<TransactionClosed> alreadyProcessedError = transaction
                .cast(BaseTransaction.class)
                .doOnNext(
                        t -> log.error(
                                "Error: requesting closure status update for transaction in state {}, Nodo closure outcome {}",
                                t.getStatus(),
                                t instanceof TransactionClosed transactionClosed
                                        ? transactionClosed.getTransactionClosureData().getResponseOutcome()
                                        : "N/A"
                        )
                )
                .flatMap(t -> Mono.error(new AlreadyProcessedException(t.getTransactionId())));
        return transaction
                .filter(
                        t -> t.getStatus() == TransactionStatusDto.CLOSED &&
                                t instanceof TransactionClosed transactionClosed &&
                                TransactionClosureData.Outcome.OK
                                        .equals(
                                                transactionClosed.getTransactionClosureData().getResponseOutcome()
                                        )
                )
                .switchIfEmpty(alreadyProcessedError)
                .cast(TransactionClosed.class)
                .flatMap(tx -> {
                    AddUserReceiptRequestDto addUserReceiptRequestDto = command.getData().addUserReceiptRequest();
                    String transactionId = command.getData().transaction().getTransactionId().value();
                    String language = "it-IT"; // FIXME: Add language to AuthorizationRequestData
                    TransactionUserReceiptRequestedEvent event = new TransactionUserReceiptRequestedEvent(
                            transactionId,
                            new TransactionUserReceiptData(
                                    requestOutcomeToReceiptOutcome(
                                            command.getData().addUserReceiptRequest().getOutcome()
                                    ),
                                    language,
                                    addUserReceiptRequestDto.getPaymentDate().toZonedDateTime().toString(),
                                    addUserReceiptRequestDto.getPayments().get(0)
                                            .getOfficeName(),
                                    addUserReceiptRequestDto.getPayments().get(0)
                                            .getDescription()

                            )
                    );

                    return userReceiptAddedEventRepository.save(event)
                            .flatMap(
                                    userReceiptEvent -> tracingUtils.traceMono(
                                                    this.getClass().getSimpleName(),
                                                    tracingInfo -> transactionNotificationRequestedQueueAsyncClient
                                                            .sendMessageWithResponse(
                                                                    new QueueEvent<>(userReceiptEvent, tracingInfo),
                                                                    Duration.ZERO,
                                                                    Duration.ofSeconds(transientQueuesTTLSeconds)
                                                            )
                                            ).doOnError(
                                                    exception -> log.error(
                                                            "Error to generate event {} for transactionId {} - error {}",
                                                            event.getEventCode(),
                                                            event.getTransactionId(),
                                                            exception.getMessage()
                                                    )
                                            )
                                            .doOnNext(
                                                    queueResponse -> log.info(
                                                            "Generated event {} for transactionId {}",
                                                            event.getEventCode(),
                                                            event.getTransactionId()
                                                    )
                                            )
                                            .thenReturn(userReceiptEvent)
                            );
                });
    }

    private static TransactionUserReceiptData.Outcome requestOutcomeToReceiptOutcome(
            AddUserReceiptRequestDto.OutcomeEnum requestOutcome
    ) {
        return switch (requestOutcome) {
            case OK -> TransactionUserReceiptData.Outcome.OK;
            case KO -> TransactionUserReceiptData.Outcome.KO;
        };
    }
}
