package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.documents.*;
import it.pagopa.ecommerce.commons.domain.EmptyTransaction;
import it.pagopa.ecommerce.commons.domain.Transaction;
import it.pagopa.ecommerce.commons.domain.TransactionWithCompletedAuthorization;
import it.pagopa.ecommerce.commons.domain.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentResponseDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionClosureSendCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.EuroUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class TransactionSendClosureHandler implements CommandHandler<TransactionClosureSendCommand, Mono<Either<TransactionClosureErrorEvent, TransactionClosureSentEvent>>> {

    private final TransactionsEventStoreRepository<TransactionClosureSendData> transactionEventStoreRepository;

    private final TransactionsEventStoreRepository<Void> transactionClosureErrorEventStoreRepository;

    private final TransactionsEventStoreRepository<Object> eventStoreRepository;

    private final NodeForPspClient nodeForPspClient;

    private final QueueAsyncClient transactionClosureSentEventQueueClient;

    private final Integer paymentTokenValidity;

    private final Integer retryTimeoutInterval;

    private final Integer softTimeoutOffset;

    @Autowired
    public TransactionSendClosureHandler(
            TransactionsEventStoreRepository<TransactionClosureSendData> transactionEventStoreRepository,
            TransactionsEventStoreRepository<Void> transactionClosureErrorEventStoreRepository,
            TransactionsEventStoreRepository<Object> eventStoreRepository,
            NodeForPspClient nodeForPspClient,
            @Qualifier("transactionClosureSentEventQueueAsyncClient") QueueAsyncClient transactionClosureSentEventQueueClient,
            @Value("${payment.token.validity}") Integer paymentTokenValidity,
            @Value("${transactions.ecommerce.retry.offset}") Integer softTimeoutOffset,
            @Value("${transactions.closure_handler.retry_interval}") Integer retryTimeoutInterval
    ) {
        this.transactionEventStoreRepository = transactionEventStoreRepository;
        this.transactionClosureErrorEventStoreRepository = transactionClosureErrorEventStoreRepository;
        this.eventStoreRepository = eventStoreRepository;
        this.nodeForPspClient = nodeForPspClient;
        this.transactionClosureSentEventQueueClient = transactionClosureSentEventQueueClient;
        this.paymentTokenValidity = paymentTokenValidity;
        this.softTimeoutOffset = softTimeoutOffset;
        this.retryTimeoutInterval = retryTimeoutInterval;
    }

    @Override
    public Mono<Either<TransactionClosureErrorEvent, TransactionClosureSentEvent>> handle(TransactionClosureSendCommand command) {
        Mono<Transaction> transaction = replayTransactionEvents(command.getData().transaction().getTransactionId().value());

        Mono<? extends BaseTransaction> alreadyProcessedError = transaction
                .cast(BaseTransaction.class)
                .doOnNext(t -> log.error("Error: requesting closure for transaction in state {}", t.getStatus()))
                .flatMap(t -> Mono.error(new AlreadyProcessedException(t.getRptId())));

        return transaction
                .cast(BaseTransaction.class)
                .filter(t -> t.getStatus() == it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZED)
                .switchIfEmpty(alreadyProcessedError)
                .cast(TransactionWithCompletedAuthorization.class)
                .flatMap(tx -> {
                    UpdateAuthorizationRequestDto updateAuthorizationRequestDto = command.getData().updateAuthorizationRequest();
                    TransactionAuthorizationRequestData transactionAuthorizationRequestData = tx.getTransactionAuthorizationRequestData();
                    TransactionAuthorizationStatusUpdateData transactionAuthorizationStatusUpdateData = tx.getTransactionAuthorizationStatusUpdateData();

                    ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                            .paymentTokens(List.of(tx.getTransactionActivatedData().getPaymentToken()))
                            .outcome(authorizationResultToOutcomeV2(transactionAuthorizationStatusUpdateData.getAuthorizationResult()))
                            .idPSP(transactionAuthorizationRequestData.getPspId())
                            .idBrokerPSP(transactionAuthorizationRequestData.getBrokerName())
                            .idChannel(transactionAuthorizationRequestData.getPspChannelCode())
                            .transactionId(tx.getTransactionId().value().toString())
                            .totalAmount(EuroUtils.euroCentsToEuro(tx.getAmount().value() + transactionAuthorizationRequestData.getFee()))
                            .fee(EuroUtils.euroCentsToEuro(transactionAuthorizationRequestData.getFee()))
                            .timestampOperation(updateAuthorizationRequestDto.getTimestampOperation())
                            .paymentMethod(transactionAuthorizationRequestData.getPaymentTypeCode())
                            .additionalPaymentInformations(
                                    Map.of(
                                            "outcome_payment_gateway", transactionAuthorizationStatusUpdateData.getAuthorizationResult().toString(),
                                            "authorization_code", updateAuthorizationRequestDto.getAuthorizationCode()
                                    )
                            );

                    /*
                     * ClosePayment (either OK or KO): save to event store and return event
                     * On error: save TransactionClosureErrorEvent to event store, enqueue and return error event
                     */
                    log.info("Invoking closePaymentV2 for RptId: {}", tx.getRptId());
                    return nodeForPspClient.closePaymentV2(closePaymentRequest)
                            .flatMap(response -> buildEventFromOutcome(response.getOutcome(), command, updateAuthorizationRequestDto))
                            .flatMap(transactionEventStoreRepository::save)
                            .map(Either::<TransactionClosureErrorEvent, TransactionClosureSentEvent>right)
                            .onErrorResume(exception -> {
                                log.error("Got exception while invoking closePaymentV2", exception);
                                TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                                        tx.getTransactionId().value().toString(),
                                        tx.getRptId().value(),
                                        tx.getTransactionActivatedData().getPaymentToken()
                                );

                                /*
                                Conceptual view of visibility timeout computation:

                                end = start + paymentTokenTimeout
                                If (end - now) >= offset
                                    Visibility timeout = min(retryTimeoutInterval, (end - offset) - now)
                                Else do nothing

                                Meaning that:
                                  * We set a visibility timeout to either the retry timeout (if now + retryTimeout is inside the validity window of the token)
                                    or at (start + paymentTokenTimeout - offset) where `start` is the creation time of the transaction
                                  * If we're at less than `offset` from the token validity end it's not worth rescheduling a retry, so we don't :)

                                                  ┌─────────────┐
                                                  ▼             │
                                                 t2             │
                                                  │             │
                                start             │       end   │
                                  │   now         │        │    │ (now + retryTimeoutInterval)
                                ──┴────┬──────────┼────────┴────┼──────
                                       │          │ <offset>    │
                                       │
                                       │                        ▲
                                       └────────────────────────┘
                                */

                                Instant validityEnd = tx.getCreationDate().plusSeconds(paymentTokenValidity).toInstant();
                                Instant softValidityEnd = validityEnd.minusSeconds(softTimeoutOffset);

                                Mono<TransactionClosureErrorEvent> eventSaved = transactionClosureErrorEventStoreRepository.save(errorEvent);
                                if (softValidityEnd.isAfter(Instant.now())) {
                                    Duration latestAllowedVisibilityTimeout = Duration.between(Instant.now(), softValidityEnd);
                                    Duration candidateVisibilityTimeout = Duration.ofSeconds(retryTimeoutInterval);

                                    Duration visibilityTimeout = ObjectUtils.min(candidateVisibilityTimeout, latestAllowedVisibilityTimeout);

                                    eventSaved = eventSaved
                                            .flatMap(e -> transactionClosureSentEventQueueClient
                                                    .sendMessageWithResponse(
                                                            BinaryData.fromObject(e),
                                                            visibilityTimeout,
                                                            null
                                                    )
                                                    .thenReturn(e)
                                            );
                                }

                                return eventSaved.map(Either::left);
                            });
                });
    }

    private ClosePaymentRequestV2Dto.OutcomeEnum authorizationResultToOutcomeV2(AuthorizationResultDto authorizationResult) {
        switch (authorizationResult) {
            case OK -> {
                return ClosePaymentRequestV2Dto.OutcomeEnum.OK;
            }
            case KO -> {
                return ClosePaymentRequestV2Dto.OutcomeEnum.KO;
            }
            default ->
                    throw new IllegalArgumentException("Missing authorization result enum value mapping to Nodo closePaymentV2 outcome");
        }
    }

    private Mono<Transaction> replayTransactionEvents(UUID transactionId) {
        Flux<TransactionEvent<Object>> events = eventStoreRepository.findByTransactionId(transactionId.toString());

        return events.reduce(new EmptyTransaction(), Transaction::applyEvent);
    }

    private Mono<TransactionClosureSentEvent> buildEventFromOutcome(ClosePaymentResponseDto.OutcomeEnum outcome, TransactionClosureSendCommand command, UpdateAuthorizationRequestDto updateAuthorizationRequestDto) {
        TransactionStatusDto updatedStatus;

        switch (outcome) {
            case OK -> updatedStatus = TransactionStatusDto.CLOSED;
            case KO -> updatedStatus = TransactionStatusDto.CLOSURE_FAILED;
            default -> {
                return Mono.error(new RuntimeException("Invalid result enum value"));
            }
        }

        TransactionClosureSentEvent event = new TransactionClosureSentEvent(
                command.getData().transaction().getTransactionId().value().toString(),
                command.getData().transaction().getRptId().value(),
                command.getData().transaction().getTransactionActivatedData().getPaymentToken(),
                new TransactionClosureSendData(
                        outcome,
                        updatedStatus

                )
        );

        return Mono.just(event);
    }
}
