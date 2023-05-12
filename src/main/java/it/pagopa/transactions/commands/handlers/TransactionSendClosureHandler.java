package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.domain.v1.TransactionAuthorizationCompleted;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.redis.templatewrappers.PaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.utils.EuroUtils;
import it.pagopa.generated.ecommerce.nodo.v2.dto.AdditionalPaymentInformationsDto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentResponseDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionClosureSendCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.AuthRequestDataUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

@Component
@Slf4j
public class TransactionSendClosureHandler implements
        CommandHandler<TransactionClosureSendCommand, Mono<Either<TransactionClosureErrorEvent, TransactionEvent<TransactionClosureData>>>> {

    public static final String TIPO_VERSAMENTO_CP = "CP";

    private final TransactionsEventStoreRepository<TransactionClosureData> transactionEventStoreRepository;

    private final TransactionsEventStoreRepository<TransactionRefundedData> transactionRefundedEventStoreRepository;

    private final TransactionsEventStoreRepository<Void> transactionClosureErrorEventStoreRepository;

    private final PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper;

    private final NodeForPspClient nodeForPspClient;

    private final QueueAsyncClient closureRetryQueueAsyncClient;

    private final Integer paymentTokenValidity;

    private final Integer retryTimeoutInterval;

    private final Integer softTimeoutOffset;

    private final QueueAsyncClient refundQueueAsyncClient;
    private final TransactionsUtils transactionsUtils;
    private final AuthRequestDataUtils authRequestDataUtils;

    @Autowired
    public TransactionSendClosureHandler(
            TransactionsEventStoreRepository<TransactionClosureData> transactionEventStoreRepository,
            TransactionsEventStoreRepository<Void> transactionClosureErrorEventStoreRepository,
            TransactionsEventStoreRepository<TransactionRefundedData> transactionRefundedEventStoreRepository,
            PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper,
            NodeForPspClient nodeForPspClient,
            @Qualifier(
                "transactionClosureRetryQueueAsyncClient"
            ) QueueAsyncClient closureRetryQueueAsyncClient,
            @Value("${payment.token.validity}") Integer paymentTokenValidity,
            @Value("${transactions.ecommerce.retry.offset}") Integer softTimeoutOffset,
            @Value("${transactions.closure_handler.retry_interval}") Integer retryTimeoutInterval,
            @Qualifier("transactionRefundQueueAsyncClient") QueueAsyncClient refundQueueAsyncClient,
            TransactionsUtils transactionsUtils,
            AuthRequestDataUtils authRequestDataUtils
    ) {
        this.transactionEventStoreRepository = transactionEventStoreRepository;
        this.transactionClosureErrorEventStoreRepository = transactionClosureErrorEventStoreRepository;
        this.transactionRefundedEventStoreRepository = transactionRefundedEventStoreRepository;
        this.paymentRequestInfoRedisTemplateWrapper = paymentRequestInfoRedisTemplateWrapper;
        this.nodeForPspClient = nodeForPspClient;
        this.closureRetryQueueAsyncClient = closureRetryQueueAsyncClient;
        this.paymentTokenValidity = paymentTokenValidity;
        this.softTimeoutOffset = softTimeoutOffset;
        this.retryTimeoutInterval = retryTimeoutInterval;
        this.refundQueueAsyncClient = refundQueueAsyncClient;
        this.transactionsUtils = transactionsUtils;
        this.authRequestDataUtils = authRequestDataUtils;
    }

    @Override
    public Mono<Either<TransactionClosureErrorEvent, TransactionEvent<TransactionClosureData>>> handle(
                                                                                                       TransactionClosureSendCommand command
    ) {
        Mono<BaseTransaction> transaction = transactionsUtils.reduceEvents(
                command.getData().transaction().getTransactionId()
        );

        Mono<? extends BaseTransaction> alreadyProcessedError = transaction
                .doOnNext(t -> log.error("Error: requesting closure for transaction in state {}", t.getStatus()))
                .flatMap(t -> Mono.error(new AlreadyProcessedException(t.getTransactionId())));
        return transaction
                .filter(
                        t -> t.getStatus() == TransactionStatusDto.AUTHORIZATION_COMPLETED
                )
                .switchIfEmpty(alreadyProcessedError)
                .cast(TransactionAuthorizationCompleted.class)
                .flatMap(tx -> {
                    UpdateAuthorizationRequestDto updateAuthorizationRequestDto = command.getData()
                            .updateAuthorizationRequest();
                    AuthRequestDataUtils.AuthRequestData authRequestData = authRequestDataUtils
                            .from(updateAuthorizationRequestDto);
                    TransactionAuthorizationRequestData transactionAuthorizationRequestData = tx
                            .getTransactionAuthorizationRequestData();
                    TransactionAuthorizationCompletedData transactionAuthorizationCompletedData = tx
                            .getTransactionAuthorizationCompletedData();
                    BigDecimal totalAmount = EuroUtils.euroCentsToEuro(
                            tx.getPaymentNotices().stream()
                                    .mapToInt(
                                            paymentNotice -> paymentNotice.transactionAmount().value()
                                    )
                                    .sum() + transactionAuthorizationRequestData.getFee()
                    );
                    BigDecimal fee = EuroUtils.euroCentsToEuro(transactionAuthorizationRequestData.getFee());
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
                            .transactionId(tx.getTransactionId().value());

                    if (ClosePaymentRequestV2Dto.OutcomeEnum.OK.equals(closePaymentRequest.getOutcome())) {
                        closePaymentRequest.idPSP(transactionAuthorizationRequestData.getPspId())
                                .idBrokerPSP(transactionAuthorizationRequestData.getBrokerName())
                                .idChannel(transactionAuthorizationRequestData.getPspChannelCode())
                            .transactionId(tx.getTransactionId().value().toString())
                                .totalAmount(totalAmount)
                                .fee(fee)
                                .timestampOperation(updateAuthorizationRequestDto.getTimestampOperation())
                                .paymentMethod(transactionAuthorizationRequestData.getPaymentTypeCode())
                                .additionalPaymentInformations(
                                        new AdditionalPaymentInformationsDto()
                                                .tipoVersamento(TIPO_VERSAMENTO_CP)
                                                .outcomePaymentGateway(
                                                        AdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum
                                                                .fromValue(authRequestData.outcome())
                                                )
                                                .authorizationCode(authRequestData.authorizationCode())
                                                .fee(fee)
                                                .timestampOperation(
                                                        updateAuthorizationRequestDto.getTimestampOperation()
                                                )
                                                .rrn(authRequestData.rrn())
                                );
                    }
                    /*
                     * ClosePayment (either OK or KO): save to event store and return event On
                     * error: save TransactionClosureErrorEvent to event store, enqueue and return
                     * error event
                     */
                    // FIXME: Refactor to handle multiple notices
                    it.pagopa.ecommerce.commons.domain.v1.PaymentNotice paymentNotice = tx.getPaymentNotices().get(0);
                    log.info("Invoking closePaymentV2 for RptId: {}", paymentNotice.rptId().value());
                    return nodeForPspClient.closePaymentV2(closePaymentRequest)
                            .flatMap(
                                    response -> buildAndSaveClosureEvent(
                                            command,
                                            transactionAuthorizationCompletedData.getAuthorizationResultDto(),
                                            response.getOutcome()
                                    )
                            )
                            .flatMap(
                                    closureEvent -> sendRefundRequestEvent(
                                            Either.right(closureEvent),
                                            transactionAuthorizationCompletedData.getAuthorizationResultDto()
                                    ).thenReturn(closureEvent)
                            )
                            .map(Either::<TransactionClosureErrorEvent, TransactionEvent<TransactionClosureData>>right)
                            .onErrorResume(exception -> {

                                // in case response code from Nodo is a 4xx class error retrying closure have no
                                // meaning since it will mean that build request have an error
                                // transactions-service side
                                boolean unrecoverableError = exception instanceof BadGatewayException responseStatusException
                                        && responseStatusException.getHttpStatus().is4xxClientError();

                                log.error(
                                        "Got exception while invoking closePaymentV2 unrecoverable error: %s"
                                                .formatted(unrecoverableError),
                                        exception
                                );
                                // the closure error event is build and sent iff the transaction was previously
                                // authorized
                                // and the error received from Nodo is a recoverable ones such as http code 500
                                TransactionClosureErrorEvent errorEvent = new TransactionClosureErrorEvent(
                                        tx.getTransactionId().value()
                                );

                                Mono<TransactionClosureErrorEvent> eventSaved = transactionClosureErrorEventStoreRepository
                                        .save(errorEvent);

                                if (!unrecoverableError) {
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
                                                        e -> closureRetryQueueAsyncClient
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
                                }
                                // Unrecoverable error calling Nodo for perform close payment.
                                // Generate closure event setting closure outcome to KO
                                // and enqueue refund request event
                                return eventSaved
                                        .<Either<TransactionClosureErrorEvent, TransactionEvent<TransactionClosureData>>>map(
                                                Either::left
                                        )
                                        .flatMap(
                                                closureErrorEvent -> sendRefundRequestEvent(
                                                        closureErrorEvent,
                                                        transactionAuthorizationCompletedData
                                                                .getAuthorizationResultDto()
                                                ).thenReturn(closureErrorEvent)
                                        );
                            })
                            .doFinally(response -> {
                                tx.getPaymentNotices().forEach(el -> {
                                    log.info("Invalidate cache for RptId : {}", el.rptId().value());
                                    paymentRequestInfoRedisTemplateWrapper.deleteById(el.rptId().value());
                                }
                                );
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

    private Mono<TransactionRefundRequestedEvent> sendRefundRequestEvent(
                                                                         Either<TransactionClosureErrorEvent, TransactionEvent<TransactionClosureData>> closureOutcomeEvent,
                                                                         AuthorizationResultDto authorizationResult
    ) {
        return Mono.just(closureOutcomeEvent)
                .filter(
                        e -> e.fold(
                                closureErrorEvent -> true,
                                // Refund requested event sent on the queue only if the transaction was
                                // previously
                                // authorized and the Nodo response outcome is KO
                                closureEvent -> TransactionClosureData.Outcome.KO
                                        .equals(closureEvent.getData().getResponseOutcome())
                                        && AuthorizationResultDto.OK.equals(authorizationResult)
                        )
                )
                .map(
                        e -> e.fold(
                                closureErrorEvent -> {
                                    log.info(
                                            "Requesting refund for transaction {} because of bad or no response from Nodo",
                                            closureErrorEvent.getTransactionId()
                                    );
                                    return Tuples.of(
                                            closureErrorEvent.getTransactionId(),
                                            TransactionStatusDto.CLOSURE_ERROR
                                    );
                                },

                                closureEvent -> {
                                    log.info(
                                            "Requesting refund for transaction {} as it was previously authorized but we either received KO response from Nodo",
                                            closureEvent.getTransactionId()
                                    );
                                    return Tuples.of(closureEvent.getTransactionId(), TransactionStatusDto.CLOSED);
                                }
                        )
                )
                .flatMap(data -> {
                    String transactionId = data.getT1();

                    TransactionStatusDto previousStatus = data.getT2();
                    TransactionRefundRequestedEvent refundRequestedEvent = new TransactionRefundRequestedEvent(
                            transactionId,
                            new TransactionRefundedData(previousStatus)
                    );

                    return transactionRefundedEventStoreRepository.save(refundRequestedEvent)
                            .then(
                                    refundQueueAsyncClient
                                            .sendMessageWithResponse(
                                                    BinaryData.fromObject(refundRequestedEvent),
                                                    Duration.ZERO,
                                                    null
                                            )
                            )
                            .thenReturn(refundRequestedEvent);
                });
    }

    private Mono<TransactionEvent<TransactionClosureData>> buildAndSaveClosureEvent(
            TransactionClosureSendCommand command,
            AuthorizationResultDto authorizationResult,
            ClosePaymentResponseDto.OutcomeEnum nodoOutcome
    ) {
        String transactionId = command.getData().transaction().getTransactionId().value().toString();
        TransactionClosureData.Outcome eventNodoOutcome = outcomeV2ToTransactionClosureDataOutcome(nodoOutcome);
        TransactionClosureData transactionClosureData = new TransactionClosureData(eventNodoOutcome);
        Mono<TransactionEvent<TransactionClosureData>> closureEvent = switch (authorizationResult) {
            case OK -> Mono.just(new TransactionClosedEvent(transactionId, transactionClosureData));
            case KO -> Mono.just(new TransactionClosureFailedEvent(transactionId, transactionClosureData));
            case null, default -> Mono.error(
                    new IllegalArgumentException(
                            "Unhandled authorization result: %s".formatted(authorizationResult)
                    )
            );
        };
        return closureEvent
                .flatMap(transactionEventStoreRepository::save);
    }

    private TransactionClosureData.Outcome outcomeV2ToTransactionClosureDataOutcome(
                                                                                    ClosePaymentResponseDto.OutcomeEnum closePaymentOutcome
    ) {
        switch (closePaymentOutcome) {
            case OK -> {
                return TransactionClosureData.Outcome.OK;
            }
            case KO -> {
                return TransactionClosureData.Outcome.KO;
            }
            default -> throw new IllegalArgumentException(
                    "Missing transaction closure data outcome mapping to Nodo closePaymentV2 outcome"
            );
        }
    }

}
