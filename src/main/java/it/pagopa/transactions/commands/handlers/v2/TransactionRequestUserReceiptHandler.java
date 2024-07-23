package it.pagopa.transactions.commands.handlers.v2;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.domain.v2.TransactionAuthorizationCompleted;
import it.pagopa.ecommerce.commons.domain.v2.TransactionClosed;
import it.pagopa.ecommerce.commons.domain.v2.TransactionExpired;
import it.pagopa.ecommerce.commons.domain.v2.TransactionWithRequestedAuthorization;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransactionWithRequestedAuthorization;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestDto;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestPaymentsInnerDto;
import it.pagopa.transactions.commands.TransactionAddUserReceiptCommand;
import it.pagopa.transactions.commands.handlers.TransactionRequestUserReceiptHandlerCommon;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.UpdateTransactionStatusTracerUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static it.pagopa.transactions.utils.UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.INVALID_REQUEST;
import static it.pagopa.transactions.utils.UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.WRONG_TRANSACTION_STATUS;

@Component(TransactionRequestUserReceiptHandler.QUALIFIER_NAME)
@Slf4j
public class TransactionRequestUserReceiptHandler extends TransactionRequestUserReceiptHandlerCommon {

    public static final String QUALIFIER_NAME = "TransactionRequestUserReceiptHandlerV2";

    private final TransactionsEventStoreRepository<it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData> userReceiptAddedEventRepository;

    private final UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils;

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
            ) boolean sendPaymentResultForTxExpiredEnabled,
            UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils
    ) {
        super(
                tracingUtils,
                transactionsUtils,
                transientQueuesTTLSeconds,
                transactionNotificationRequestedQueueAsyncClient,
                sendPaymentResultForTxExpiredEnabled
        );
        this.userReceiptAddedEventRepository = userReceiptAddedEventRepository;
        this.updateTransactionStatusTracerUtils = updateTransactionStatusTracerUtils;
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
                .flatMap(t -> {
                    updateTransactionStatusTracerUtils.traceStatusUpdateOperation(
                            new UpdateTransactionStatusTracerUtils.NodoStatusUpdate(
                                    WRONG_TRANSACTION_STATUS,
                                    Optional.ofNullable(
                                            ((BaseTransactionWithRequestedAuthorization) t)
                                                    .getTransactionAuthorizationRequestData()
                                                    .getPspId()
                                    ),
                                    ((BaseTransactionWithRequestedAuthorization) t)
                                            .getTransactionAuthorizationRequestData()
                                            .getPaymentTypeCode(),
                                    t.getClientId()
                            )
                    );
                    return Mono.error(new AlreadyProcessedException(t.getTransactionId()));
                });
        return transaction
                .map(
                        tx -> tx instanceof it.pagopa.ecommerce.commons.domain.v2.TransactionExpired txExpired
                                && sendPaymentResultForTxExpiredEnabled ? txExpired.getTransactionAtPreviousState() : tx
                )
                .filter(
                        t -> t.getStatus() == TransactionStatusDto.CLOSED &&
                                t instanceof it.pagopa.ecommerce.commons.domain.v2.TransactionClosed transactionClosed
                                &&
                                it.pagopa.ecommerce.commons.documents.v2.TransactionClosureData.Outcome.OK
                                        .equals(
                                                transactionClosed.getTransactionClosureData().getResponseOutcome()
                                        )
                )
                .switchIfEmpty(alreadyProcessedError)
                .filterWhen(tx -> {
                    Set<String> eCommercePaymentTokens = tx.getPaymentNotices().stream()
                            .map(p -> p.paymentToken().value()).collect(Collectors.toSet());
                    Set<String> addUserReceiptRequestPaymentTokens = command.getData().addUserReceiptRequest()
                            .getPayments().stream().map(AddUserReceiptRequestPaymentsInnerDto::getPaymentToken)
                            .collect(Collectors.toSet());
                    boolean isOk = eCommercePaymentTokens.size() == addUserReceiptRequestPaymentTokens.size()
                            && eCommercePaymentTokens.containsAll(addUserReceiptRequestPaymentTokens);
                    log.debug(
                            "eCommerce transaction payment tokens: {}, send payment result payment tokens: {} -> isOk: [{}]",
                            eCommercePaymentTokens,
                            addUserReceiptRequestPaymentTokens,
                            isOk
                    );
                    if (!isOk) {
                        log.debug("invoking updateTransactionStatusTracerUtils");
                        updateTransactionStatusTracerUtils.traceStatusUpdateOperation(
                                new UpdateTransactionStatusTracerUtils.NodoStatusUpdate(
                                        INVALID_REQUEST,
                                        Optional.ofNullable(
                                                ((BaseTransactionWithRequestedAuthorization) tx)
                                                        .getTransactionAuthorizationRequestData()
                                                        .getPspId()
                                        ),
                                        ((TransactionClosed) tx)
                                                .getTransactionAuthorizationRequestData()
                                                .getPaymentTypeCode(),
                                        tx.getClientId()
                                )
                        );
                        log.debug("return mono error");
                        return Mono.error(
                                new InvalidRequestException(
                                        "eCommerce and Nodo payment tokens mismatch detected!%ntransactionId: %s,%neCommerce payment tokens: %s%nNodo send paymnt result payment tokens: %s"
                                                .formatted(
                                                        tx.getTransactionId().value(),
                                                        eCommercePaymentTokens,
                                                        addUserReceiptRequestPaymentTokens
                                                )
                                )
                        );
                    }
                    return Mono.just(true);
                })
                .cast(it.pagopa.ecommerce.commons.domain.v2.TransactionClosed.class)
                .map(tx -> {
                    AddUserReceiptRequestDto addUserReceiptRequestDto = command.getData().addUserReceiptRequest();
                    String language = "it-IT"; // FIXME: Add language to AuthorizationRequestData
                    return Tuples.of(
                            new it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptRequestedEvent(
                                    command.getData().transactionId().value(),
                                    new it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData(
                                            requestOutcomeToReceiptOutcome(
                                                    command.getData().addUserReceiptRequest().getOutcome()
                                            ),
                                            language,
                                            addUserReceiptRequestDto.getPaymentDate().toZonedDateTime().toString()
                                    )
                            ),
                            tx
                    );
                }).flatMap(
                        event_tx -> userReceiptAddedEventRepository.save(event_tx.getT1())
                                .flatMap(
                                        userReceiptEvent -> tracingUtils.traceMono(
                                                this.getClass().getSimpleName(),
                                                tracingInfo -> transactionNotificationRequestedQueueAsyncClient
                                                        .sendMessageWithResponse(
                                                                new QueueEvent<>(userReceiptEvent, tracingInfo),
                                                                Duration.ZERO,
                                                                Duration.ofSeconds(transientQueuesTTLSeconds)
                                                        )
                                        ).doOnNext(
                                                queueResponse -> {
                                                    log.info(
                                                            "Generated event {} for transactionId {}",
                                                            event_tx.getT1().getEventCode(),
                                                            event_tx.getT1().getTransactionId()
                                                    );
                                                    updateTransactionStatusTracerUtils.traceStatusUpdateOperation(
                                                            new UpdateTransactionStatusTracerUtils.NodoStatusUpdate(
                                                                    UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.OK,
                                                                    Optional.ofNullable(
                                                                            event_tx.getT2()
                                                                                    .getTransactionAuthorizationRequestData()
                                                                                    .getPspId()
                                                                    ),
                                                                    event_tx.getT2()
                                                                            .getTransactionAuthorizationRequestData()
                                                                            .getPaymentTypeCode(),
                                                                    event_tx.getT2().getClientId()
                                                            )
                                                    );
                                                }
                                        )
                                                .thenReturn(userReceiptEvent)
                                )
                );

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
