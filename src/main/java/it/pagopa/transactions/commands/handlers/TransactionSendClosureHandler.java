package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.domain.v1.Transaction;
import it.pagopa.ecommerce.commons.domain.v1.TransactionAuthorizationCompleted;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
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
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
@Slf4j
public class TransactionSendClosureHandler extends
        BaseHandler<TransactionClosureSendCommand, Mono<Either<TransactionClosureErrorEvent, TransactionEvent<Void>>>> {

    private final TransactionsEventStoreRepository<Void> transactionEventStoreRepository;

    private final NodeForPspClient nodeForPspClient;

    private final QueueAsyncClient transactionClosureSentEventQueueClient;

    private final Integer paymentTokenValidity;

    private final Integer retryTimeoutInterval;

    private final Integer softTimeoutOffset;

    @Autowired
    public TransactionSendClosureHandler(
            TransactionsEventStoreRepository<Void> transactionEventStoreRepository,
            TransactionsEventStoreRepository<Object> eventStoreRepository,
            NodeForPspClient nodeForPspClient,
            @Qualifier(
                "transactionClosureSentEventQueueAsyncClient"
            ) QueueAsyncClient transactionClosureSentEventQueueClient,
            @Value("${payment.token.validity}") Integer paymentTokenValidity,
            @Value("${transactions.ecommerce.retry.offset}") Integer softTimeoutOffset,
            @Value("${transactions.closure_handler.retry_interval}") Integer retryTimeoutInterval
    ) {
        super(eventStoreRepository);
        this.transactionEventStoreRepository = transactionEventStoreRepository;
        this.nodeForPspClient = nodeForPspClient;
        this.transactionClosureSentEventQueueClient = transactionClosureSentEventQueueClient;
        this.paymentTokenValidity = paymentTokenValidity;
        this.softTimeoutOffset = softTimeoutOffset;
        this.retryTimeoutInterval = retryTimeoutInterval;
    }

    @Override
    public Mono<Either<TransactionClosureErrorEvent, TransactionEvent<Void>>> handle(
                                                                                     TransactionClosureSendCommand command
    ) {
        Mono<Transaction> transaction = replayTransactionEvents(
                command.getData().transaction().getTransactionId().value()
        );

        Mono<? extends BaseTransaction> alreadyProcessedError = transaction
                .cast(BaseTransaction.class)
                .doOnNext(t -> log.error("Error: requesting closure for transaction in state {}", t.getStatus()))
                .flatMap(t -> Mono.error(new AlreadyProcessedException(t.getTransactionId())));

        return transaction
                .cast(BaseTransaction.class)
                .filter(
                        t -> t.getStatus() == TransactionStatusDto.AUTHORIZATION_COMPLETED
                )
                .switchIfEmpty(alreadyProcessedError)
                .cast(TransactionAuthorizationCompleted.class)
                .flatMap(tx -> {
                    UpdateAuthorizationRequestDto updateAuthorizationRequestDto = command.getData()
                            .updateAuthorizationRequest();
                    TransactionAuthorizationRequestData transactionAuthorizationRequestData = tx
                            .getTransactionAuthorizationRequestData();
                    TransactionAuthorizationCompletedData transactionAuthorizationCompletedData = tx
                            .getTransactionAuthorizationCompletedData();

                    ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                            .paymentTokens(
                                    tx.getTransactionActivatedData().getPaymentNotices().stream()
                                            .map(PaymentNotice::getPaymentToken).toList()
                            )
                            .outcome(
                                    authorizationResultToOutcomeV2(
                                            transactionAuthorizationCompletedData.getAuthorizationResultDto()
                                    )
                            )
                            .idPSP(transactionAuthorizationRequestData.getPspId())
                            .idBrokerPSP(transactionAuthorizationRequestData.getBrokerName())
                            .idChannel(transactionAuthorizationRequestData.getPspChannelCode())
                            .transactionId(tx.getTransactionId().value().toString())
                            .totalAmount(
                                    EuroUtils.euroCentsToEuro(
                                            tx.getPaymentNotices().stream()
                                                    .mapToInt(
                                                            paymentNotice -> paymentNotice.transactionAmount().value()
                                                    )
                                                    .sum() + transactionAuthorizationRequestData.getFee()
                                    )
                            )
                            .fee(EuroUtils.euroCentsToEuro(transactionAuthorizationRequestData.getFee()))
                            .timestampOperation(updateAuthorizationRequestDto.getTimestampOperation())
                            .paymentMethod(transactionAuthorizationRequestData.getPaymentTypeCode())
                            .additionalPaymentInformations(
                                    Map.of(
                                            "outcome_payment_gateway",
                                            transactionAuthorizationCompletedData.getAuthorizationResultDto()
                                                    .toString(),
                                            "authorization_code",
                                            updateAuthorizationRequestDto.getAuthorizationCode()
                                    )
                            );

                    /*
                     * ClosePayment (either OK or KO): save to event store and return event On
                     * error: save TransactionClosureErrorEvent to event store, enqueue and return
                     * error event
                     */
                    // FIXME: Refactor to handle multiple notices
                    it.pagopa.ecommerce.commons.domain.v1.PaymentNotice paymentNotice = tx.getPaymentNotices().get(0);
                    log.info("Invoking closePaymentV2 for RptId: {}", paymentNotice.rptId().value());
                    return nodeForPspClient.closePaymentV2(closePaymentRequest)
                            .flatMap(response -> buildClosureEvent(command, closePaymentRequest.getOutcome()))
                            .flatMap(transactionEventStoreRepository::save)
                            .map(Either::<TransactionClosureErrorEvent, TransactionEvent<Void>>right)
                            .onErrorResume(exception -> {
                                log.error("Got exception while invoking closePaymentV2", exception);
                                TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                                        tx.getTransactionId().value().toString()
                                );

                                /* @formatter:off
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

                                @formatter:on
                                */

                                Instant validityEnd = tx.getCreationDate().plusSeconds(paymentTokenValidity)
                                        .toInstant();
                                Instant softValidityEnd = validityEnd.minusSeconds(softTimeoutOffset);

                                Mono<TransactionClosureErrorEvent> eventSaved = transactionEventStoreRepository
                                        .save(errorEvent);
                                if (softValidityEnd.isAfter(Instant.now())) {
                                    Duration latestAllowedVisibilityTimeout = Duration
                                            .between(Instant.now(), softValidityEnd);
                                    Duration candidateVisibilityTimeout = Duration.ofSeconds(retryTimeoutInterval);

                                    Duration visibilityTimeout = ObjectUtils
                                            .min(candidateVisibilityTimeout, latestAllowedVisibilityTimeout);
                                    log.info(
                                            "Enqueued closure error retry event with visibility timeout {}",
                                            visibilityTimeout
                                    );

                                    eventSaved = eventSaved
                                            .flatMap(
                                                    e -> transactionClosureSentEventQueueClient
                                                            .sendMessageWithResponse(
                                                                    BinaryData.fromObject(e),
                                                                    visibilityTimeout,
                                                                    null
                                                            )
                                                            .thenReturn(e)
                                            );
                                } else {
                                    log.info(
                                            "Skipped enqueueing of closure error retry event: too near payment token expiry (offset={}, expiration at {})",
                                            softTimeoutOffset,
                                            validityEnd
                                    );
                                }

                                return eventSaved.map(Either::left);
                            });
                });
    }

    private ClosePaymentRequestV2Dto.OutcomeEnum authorizationResultToOutcomeV2(
                                                                                AuthorizationResultDto authorizationResult
    ) {
        switch (authorizationResult) {
            case OK -> {
                return ClosePaymentRequestV2Dto.OutcomeEnum.OK;
            }
            case KO -> {
                return ClosePaymentRequestV2Dto.OutcomeEnum.KO;
            }
            default -> throw new IllegalArgumentException(
                    "Missing authorization result enum value mapping to Nodo closePaymentV2 outcome"
            );
        }
    }

    private Mono<TransactionEvent<Void>> buildClosureEvent(
            TransactionClosureSendCommand command,
            ClosePaymentRequestV2Dto.OutcomeEnum outcome) {
        String transactionId = command.getData().transaction().getTransactionId().value().toString();
        return switch (outcome) {
            case OK -> Mono.just(new TransactionClosedEvent(transactionId));

            case KO -> Mono.just(new TransactionClosureFailedEvent(transactionId));

            case null, default -> Mono.error(new IllegalArgumentException(
                    "Missing authorization result enum value mapping to Nodo closePaymentV2 outcome"
            ));
        };
    }
}
