package it.pagopa.transactions.commands.handlers.v2;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestDto;
import it.pagopa.transactions.commands.TransactionAddUserReceiptCommand;
import it.pagopa.transactions.commands.handlers.TransactionRequestUserReceiptHandlerCommon;
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

@Component(TransactionRequestUserReceiptHandler.QUALIFIER_NAME)
@Slf4j
public class TransactionRequestUserReceiptHandler extends TransactionRequestUserReceiptHandlerCommon {

    public static final String QUALIFIER_NAME = "TransactionRequestUserReceiptHandlerV2";

    private final TransactionsEventStoreRepository<it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData> userReceiptAddedEventRepository;

    @Autowired
    public TransactionRequestUserReceiptHandler(
            TransactionsEventStoreRepository<it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData> userReceiptAddedEventRepository,
            TransactionsUtils transactionsUtils,
            @Qualifier(
                "transactionNotificationRequestedQueueAsyncClientV2"
            ) QueueAsyncClient transactionNotificationRequestedQueueAsyncClient,
            @Value("${azurestorage.queues.transientQueues.ttlSeconds}") int transientQueuesTTLSeconds,
            TracingUtils tracingUtils,
            @Value(
                "${ecommerce.send-payment-result-for-tx-expired.enabled}"
            ) boolean sendPaymentResultForTxExpiredEnabled
    ) {
        super(
                tracingUtils,
                transactionsUtils,
                transientQueuesTTLSeconds,
                transactionNotificationRequestedQueueAsyncClient,
                sendPaymentResultForTxExpiredEnabled
        );
        this.userReceiptAddedEventRepository = userReceiptAddedEventRepository;
    }

    @Override
    public Mono<BaseTransactionEvent<?>> handle(TransactionAddUserReceiptCommand command) {
        Mono<it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction> transaction = transactionsUtils
                .reduceEventsV2(
                        command.getData().transactionId()
                );

        Mono<it.pagopa.ecommerce.commons.domain.v2.TransactionClosed> alreadyProcessedError = transaction
                .cast(it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction.class)
                .doOnNext(
                        t -> log.error(
                                "Error: requesting closure status update for transaction in state {}, Nodo closure outcome {}",
                                t.getStatus(),
                                t instanceof it.pagopa.ecommerce.commons.domain.v2.TransactionClosed transactionClosed
                                        ? transactionClosed.getTransactionClosureData().getResponseOutcome()
                                        : "N/A"
                        )
                )
                .flatMap(t -> Mono.error(new AlreadyProcessedException(t.getTransactionId())));
        return transaction
                .filter(
                        t -> t.getStatus() == TransactionStatusDto.CLOSED &&
                                t instanceof it.pagopa.ecommerce.commons.domain.v2.TransactionClosed transactionClosed
                                &&
                                it.pagopa.ecommerce.commons.documents.v2.TransactionClosureData.Outcome.OK
                                        .equals(
                                                transactionClosed.getTransactionClosureData().getResponseOutcome()
                                        )
                                || t.getStatus() == TransactionStatusDto.EXPIRED && sendPaymentResultForTxExpiredEnabled
                )
                .switchIfEmpty(alreadyProcessedError)
                .cast(it.pagopa.ecommerce.commons.domain.v2.TransactionClosed.class)
                .flatMap(tx -> {
                    AddUserReceiptRequestDto addUserReceiptRequestDto = command.getData().addUserReceiptRequest();
                    String language = "it-IT"; // FIXME: Add language to AuthorizationRequestData
                    it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptRequestedEvent event = new it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptRequestedEvent(
                            command.getData().transactionId().value(),
                            new it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData(
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

    private static it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome requestOutcomeToReceiptOutcome(
                                                                                                                              AddUserReceiptRequestDto.OutcomeEnum requestOutcome
    ) {
        return switch (requestOutcome) {
            case OK -> it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome.OK;
            case KO -> it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome.KO;
        };
    }
}
