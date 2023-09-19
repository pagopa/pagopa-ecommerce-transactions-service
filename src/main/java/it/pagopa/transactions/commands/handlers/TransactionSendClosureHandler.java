package it.pagopa.transactions.commands.handlers;

import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.domain.v1.TransactionAuthorizationCompleted;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.redis.templatewrappers.PaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.utils.EuroUtils;
import it.pagopa.generated.ecommerce.nodo.v2.dto.*;
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
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.stream.Stream;

@Component
@Slf4j
public class TransactionSendClosureHandler implements
        CommandHandler<TransactionClosureSendCommand, Mono<Tuple2<Optional<TransactionRefundRequestedEvent>, Either<TransactionClosureErrorEvent, TransactionEvent<TransactionClosureData>>>>> {

    private static final String CONFERMATO = "Confermato";
    private static final String RIFIUTATO = "Rifiutato";
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

    private final int transientQueuesTTLSeconds;

    private final TracingUtils tracingUtils;

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
            AuthRequestDataUtils authRequestDataUtils,
            @Value("${azurestorage.queues.transientQueues.ttlSeconds}") int transientQueuesTTLSeconds,
            TracingUtils tracingUtils
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
        this.transientQueuesTTLSeconds = transientQueuesTTLSeconds;
        this.tracingUtils = tracingUtils;
    }

    @Override
    public Mono<Tuple2<Optional<TransactionRefundRequestedEvent>, Either<TransactionClosureErrorEvent, TransactionEvent<TransactionClosureData>>>> handle(
            TransactionClosureSendCommand command
    ) {
        Mono<BaseTransaction> transaction = transactionsUtils.reduceEventsV1(
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
                            .from(updateAuthorizationRequestDto, tx.getTransactionId());
                    TransactionAuthorizationRequestData transactionAuthorizationRequestData = tx
                            .getTransactionAuthorizationRequestData();
                    TransactionAuthorizationCompletedData transactionAuthorizationCompletedData = tx
                            .getTransactionAuthorizationCompletedData();
                    TransactionActivatedData transactionActivatedData = tx
                            .getTransactionActivatedData();
                    BigDecimal amount = EuroUtils.euroCentsToEuro(
                            tx.getPaymentNotices().stream()
                                    .mapToInt(
                                            paymentNotice -> paymentNotice.transactionAmount().value()
                                    ).sum()
                    );
                    BigDecimal fee = EuroUtils.euroCentsToEuro(transactionAuthorizationRequestData.getFee());
                    BigDecimal totalAmount = amount.add(fee);
                    ClosePaymentRequestV2Dto.OutcomeEnum outcome = authorizationResultToOutcomeV2(
                            transactionAuthorizationCompletedData.getAuthorizationResultDto()
                    );
                    ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                            .paymentTokens(
                                    tx.getTransactionActivatedData().getPaymentNotices().stream()
                                            .map(PaymentNotice::getPaymentToken).toList()
                            )
                            .outcome(
                                    outcome
                            )
                            .transactionId(tx.getTransactionId().value())
                            .transactionDetails(
                                    buildTransactionDetailsDto(
                                            transactionActivatedData,
                                            authRequestData,
                                            transactionAuthorizationRequestData,
                                            transactionAuthorizationCompletedData,
                                            command,
                                            fee,
                                            amount,
                                            totalAmount,
                                            outcome
                                    )
                            );
                    if (ClosePaymentRequestV2Dto.OutcomeEnum.OK.equals(closePaymentRequest.getOutcome())) {
                        closePaymentRequest.idPSP(transactionAuthorizationRequestData.getPspId())
                                .idBrokerPSP(transactionAuthorizationRequestData.getBrokerName())
                                .idChannel(transactionAuthorizationRequestData.getPspChannelCode())
                                .transactionId(tx.getTransactionId().value())
                                .totalAmount(totalAmount)
                                .fee(fee)
                                .timestampOperation(updateAuthorizationRequestDto.getTimestampOperation())
                                .paymentMethod(transactionAuthorizationRequestData.getPaymentTypeCode())
                                .additionalPaymentInformations(
                                        new AdditionalPaymentInformationsDto()
                                                .outcomePaymentGateway(
                                                        AdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum
                                                                .fromValue(authRequestData.outcome())
                                                )
                                                .authorizationCode(authRequestData.authorizationCode())
                                                .fee(fee.toString())
                                                .timestampOperation(
                                                        updateAuthorizationRequestDto
                                                                .getTimestampOperation()
                                                                .truncatedTo(ChronoUnit.SECONDS)
                                                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                                )
                                                .totalAmount(totalAmount.toString())
                                                .rrn(authRequestData.rrn())
                                );
                    }

                    /*
                     * ClosePayment (either OK or KO): save to event store and return event On
                     * error: save TransactionClosureErrorEvent to event store, enqueue and return
                     * error event
                     */
                    // FIXME: Refactor to handle multiple notices
                    it.pagopa.ecommerce.commons.domain.PaymentNotice paymentNotice = tx.getPaymentNotices().get(0);
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
                                    )
                                            .map(
                                                    (refundedEvent) -> Tuples.of(
                                                            Optional.of(refundedEvent),
                                                            Either.<TransactionClosureErrorEvent, TransactionEvent<TransactionClosureData>>right(
                                                                    closureEvent
                                                            )
                                                    )

                                            ).switchIfEmpty(
                                                    Mono.just(Tuples.of(Optional.empty(), Either.right(closureEvent)))
                                            )
                            )
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
                                                        e -> tracingUtils.traceMono(
                                                                this.getClass().getSimpleName(),
                                                                tracingInfo -> closureRetryQueueAsyncClient
                                                                        .sendMessageWithResponse(
                                                                                new QueueEvent<>(e, tracingInfo),
                                                                                visibilityTimeout,
                                                                                Duration.ofSeconds(
                                                                                        transientQueuesTTLSeconds
                                                                                )
                                                                        )
                                                                        .thenReturn(e)
                                                        )
                                                );
                                    } else {
                                        log.info(
                                                "Skipped enqueueing of closure error retry event: too near payment token expiry (offset={}, expiration at {})",
                                                softTimeoutOffset,
                                                validityEnd
                                        );
                                    }

                                    return eventSaved.map(
                                            (closureErrorEvent) -> Tuples.of(
                                                    Optional.empty(),
                                                    Either.<TransactionClosureErrorEvent, TransactionEvent<TransactionClosureData>>left(
                                                            closureErrorEvent
                                                    )
                                            )
                                    );
                                }
                                // Unrecoverable error calling Nodo for perform close payment.
                                // Generate closure event setting closure outcome to KO
                                // and enqueue refund request event
                                return eventSaved
                                        .flatMap(

                                                closureErrorEvent -> sendRefundRequestEvent(
                                                        Either.left(closureErrorEvent),
                                                        transactionAuthorizationCompletedData
                                                                .getAuthorizationResultDto()
                                                ).map(
                                                        (refundRequestedEvent) -> Tuples.of(
                                                                Optional.of(refundRequestedEvent),
                                                                Either.<TransactionClosureErrorEvent, TransactionEvent<TransactionClosureData>>left(
                                                                        closureErrorEvent
                                                                )
                                                        )

                                                ).switchIfEmpty(
                                                        Mono.just(
                                                                Tuples.of(
                                                                        Optional.empty(),
                                                                        Either.left(closureErrorEvent)
                                                                )
                                                        )

                                                )
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

    private TransactionDetailsDto buildTransactionDetailsDto(
            TransactionActivatedData transactionActivatedData,
            AuthRequestDataUtils.AuthRequestData authRequestData,
            TransactionAuthorizationRequestData transactionAuthorizationRequestData,
            TransactionAuthorizationCompletedData transactionAuthorizationCompletedData,
            TransactionClosureSendCommand command,
            BigDecimal fee,
            BigDecimal amount,
            BigDecimal totalAmount,
            ClosePaymentRequestV2Dto.OutcomeEnum outcomeEnum
    ) {
        return new TransactionDetailsDto()
                .transaction(
                        buildTransactionDto(
                                authRequestData,
                                transactionAuthorizationRequestData,
                                transactionAuthorizationCompletedData,
                                command,
                                fee,
                                amount,
                                totalAmount,
                                outcomeEnum
                        )
                )
                .info(
                        buildInfoDto(transactionActivatedData, transactionAuthorizationRequestData)
                )
                .user(new UserDto().type(UserDto.TypeEnum.GUEST));

    }

    private TransactionDto buildTransactionDto(
            AuthRequestDataUtils.AuthRequestData authRequestData,
            TransactionAuthorizationRequestData transactionAuthorizationRequestData,
            TransactionAuthorizationCompletedData transactionAuthorizationCompletedData,
            TransactionClosureSendCommand command,
            BigDecimal fee,
            BigDecimal amount,
            BigDecimal totalAmount,
            ClosePaymentRequestV2Dto.OutcomeEnum outcomeEnum
    ) {
        return new TransactionDto()
                .transactionStatus(
                        ClosePaymentRequestV2Dto.OutcomeEnum.OK.equals(outcomeEnum) ? CONFERMATO : RIFIUTATO
                )
                .fee(fee)
                .amount(amount)
                .grandTotal(totalAmount)
                .transactionId(
                        command.getData().transaction()
                                .getTransactionId().value()
                )
                .creationDate(
                        command.getData().transaction()
                                .getCreationDate().toOffsetDateTime()
                )
                .paymentGateway(
                        transactionAuthorizationRequestData.getPaymentGateway().name()
                )
                .rrn(authRequestData.rrn())
                .authorizationCode(authRequestData.authorizationCode())
                .timestampOperation(
                        transactionAuthorizationCompletedData.getTimestampOperation()
                )
                .errorCode(
                        ClosePaymentRequestV2Dto.OutcomeEnum.KO.equals(outcomeEnum)
                                ? transactionAuthorizationCompletedData.getErrorCode()
                                : null
                )
                .psp(buildPspDto(transactionAuthorizationRequestData));
    }

    private PspDto buildPspDto(TransactionAuthorizationRequestData transactionAuthorizationRequestData) {

        return new PspDto()
                .idPsp(
                        transactionAuthorizationRequestData
                                .getPspId()
                )
                .idChannel(
                        transactionAuthorizationRequestData
                                .getPspChannelCode()
                )
                .businessName(
                        transactionAuthorizationRequestData
                                .getPspBusinessName()
                )
                .brokerName(transactionAuthorizationRequestData.getBrokerName())
                .pspOnUs(transactionAuthorizationRequestData.isPspOnUs());
    }

    private InfoDto buildInfoDto(
            TransactionActivatedData transactionActivatedData,
            TransactionAuthorizationRequestData transactionAuthorizationRequestData
    ) {
        InfoDto result = new InfoDto()
                .clientId(transactionActivatedData.getClientId().name())
                .brandLogo(
                        Stream.ofNullable(transactionAuthorizationRequestData.getLogo())
                                .filter(logo -> logo != null)
                                .map(l -> l.toString())
                                .findFirst()
                                .orElse(null)
                )
                .brand(
                        Optional.ofNullable(transactionAuthorizationRequestData.getBrand()).map(Enum::name).orElse(null)
                )
                .type(
                        transactionAuthorizationRequestData
                                .getPaymentTypeCode()
                )
                .paymentMethodName(transactionAuthorizationRequestData.getPaymentMethodName());

        return result;
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
                                closureErrorEvent -> AuthorizationResultDto.OK.equals(authorizationResult),
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
                                    tracingUtils.traceMono(
                                            this.getClass().getSimpleName(),
                                            tracingInfo -> refundQueueAsyncClient
                                                    .sendMessageWithResponse(
                                                            new QueueEvent<>(refundRequestedEvent, tracingInfo),
                                                            Duration.ZERO,
                                                            Duration.ofSeconds(transientQueuesTTLSeconds)
                                                    )
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
