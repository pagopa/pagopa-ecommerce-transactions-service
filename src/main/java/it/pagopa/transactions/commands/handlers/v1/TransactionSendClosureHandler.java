package it.pagopa.transactions.commands.handlers.v1;

import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionRefundedData;
import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.domain.v1.TransactionAuthorizationCompleted;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.utils.EuroUtils;
import it.pagopa.generated.ecommerce.nodo.v2.dto.*;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionClosureSendCommand;
import it.pagopa.transactions.commands.handlers.TransactionSendClosureHandlerCommon;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.stream.Stream;

@Component(TransactionSendClosureHandler.QUALIFIER_NAME)
@Slf4j
public class TransactionSendClosureHandler extends TransactionSendClosureHandlerCommon {
    public static final String QUALIFIER_NAME = "TransactionSendClosureHandlerV1";
    private final TransactionsEventStoreRepository<it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData> transactionEventStoreRepository;
    private final TransactionsEventStoreRepository<it.pagopa.ecommerce.commons.documents.v1.TransactionRefundedData> transactionRefundedEventStoreRepository;
    private final TransactionsEventStoreRepository<Void> transactionClosureErrorEventStoreRepository;
    private final NodeForPspClient nodeForPspClient;
    private final QueueAsyncClient closureRetryQueueAsyncClient;
    private final QueueAsyncClient refundQueueAsyncClient;

    @Autowired
    public TransactionSendClosureHandler(
            TransactionsEventStoreRepository<TransactionClosureData> transactionEventStoreRepository,
            TransactionsEventStoreRepository<Void> transactionClosureErrorEventStoreRepository,
            TransactionsEventStoreRepository<TransactionRefundedData> transactionRefundedEventStoreRepository,
            NodeForPspClient nodeForPspClient,
            @Qualifier(
                "transactionClosureRetryQueueAsyncClientV1"
            ) QueueAsyncClient closureRetryQueueAsyncClient,
            @Value("${payment.token.validity}") Integer paymentTokenValidity,
            @Value("${transactions.ecommerce.retry.offset}") Integer softTimeoutOffset,
            @Value("${transactions.closure_handler.retry_interval}") Integer retryTimeoutInterval,
            @Qualifier("transactionRefundQueueAsyncClientV1") QueueAsyncClient refundQueueAsyncClient,
            TransactionsUtils transactionsUtils,
            AuthRequestDataUtils authRequestDataUtils,
            @Value("${azurestorage.queues.transientQueues.ttlSeconds}") int transientQueuesTTLSeconds,
            TracingUtils tracingUtils
    ) {
        super(
                transactionsUtils,
                authRequestDataUtils,
                tracingUtils,
                paymentTokenValidity,
                retryTimeoutInterval,
                softTimeoutOffset,
                transientQueuesTTLSeconds
        );
        this.transactionEventStoreRepository = transactionEventStoreRepository;
        this.transactionClosureErrorEventStoreRepository = transactionClosureErrorEventStoreRepository;
        this.transactionRefundedEventStoreRepository = transactionRefundedEventStoreRepository;
        this.closureRetryQueueAsyncClient = closureRetryQueueAsyncClient;
        this.refundQueueAsyncClient = refundQueueAsyncClient;
        this.nodeForPspClient = nodeForPspClient;
    }

    @Override
    public Mono<Tuple2<Optional<BaseTransactionEvent<?>>, Either<BaseTransactionEvent<?>, BaseTransactionEvent<?>>>> handle(
                                                                                                                            TransactionClosureSendCommand command
    ) {
        Mono<BaseTransaction> transaction = transactionsUtils.reduceEventsV1(
                command.getData().transactionId()
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
                    it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData transactionAuthorizationRequestData = tx
                            .getTransactionAuthorizationRequestData();
                    it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedData transactionAuthorizationCompletedData = tx
                            .getTransactionAuthorizationCompletedData();
                    it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedData transactionActivatedData = tx
                            .getTransactionActivatedData();

                    Integer amount = tx.getPaymentNotices().stream()
                            .mapToInt(
                                    paymentNotice -> paymentNotice.transactionAmount().value()
                            ).sum();
                    Integer fee = transactionAuthorizationRequestData.getFee();
                    Integer totalAmount = amount + fee;

                    BigDecimal amountEuroCents = BigDecimal.valueOf(
                            amount
                    );
                    BigDecimal feeEuroCents = BigDecimal.valueOf(fee);
                    BigDecimal totalAmountEuroCents = BigDecimal.valueOf(totalAmount);

                    BigDecimal feeEuro = EuroUtils.euroCentsToEuro(fee);
                    BigDecimal totalAmountEuro = EuroUtils.euroCentsToEuro(totalAmount);

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
                                            feeEuroCents,
                                            amountEuroCents,
                                            totalAmountEuroCents,
                                            outcome,
                                            tx.getTransactionId(),
                                            tx.getCreationDate()
                                    )
                            );
                    if (ClosePaymentRequestV2Dto.OutcomeEnum.OK.equals(closePaymentRequest.getOutcome())) {
                        closePaymentRequest.idPSP(transactionAuthorizationRequestData.getPspId())
                                .idBrokerPSP(transactionAuthorizationRequestData.getBrokerName())
                                .idChannel(transactionAuthorizationRequestData.getPspChannelCode())
                                .transactionId(tx.getTransactionId().value())
                                .totalAmount(totalAmountEuro)
                                .fee(feeEuro)
                                .timestampOperation(updateAuthorizationRequestDto.getTimestampOperation())
                                .paymentMethod(transactionAuthorizationRequestData.getPaymentTypeCode())
                                .additionalPaymentInformations(
                                        new AdditionalPaymentInformationsDto()
                                                .outcomePaymentGateway(
                                                        AdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum
                                                                .fromValue(authRequestData.outcome())
                                                )
                                                .authorizationCode(authRequestData.authorizationCode())
                                                .fee(feeEuro.toString())
                                                .timestampOperation(
                                                        updateAuthorizationRequestDto
                                                                .getTimestampOperation()
                                                                .atZoneSameInstant(ZoneId.of("Europe/Paris"))
                                                                .truncatedTo(ChronoUnit.SECONDS)
                                                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                                )
                                                .totalAmount(totalAmountEuro.toString())
                                                .rrn(authRequestData.rrn())
                                );
                    }

                    /*
                     * ClosePayment (either OK or KO): save to event store and return event On
                     * error: save TransactionClosureErrorEvent to event store, enqueue and return
                     * error event
                     */
                    log.info(
                            "Invoking closePaymentV2 for rptIds: {}",
                            command.getRptIds().stream().map(RptId::value).toList()
                    );
                    return nodeForPspClient.closePaymentV2(closePaymentRequest)
                            .flatMap(
                                    response -> buildAndSaveClosureEvent(
                                            tx.getTransactionId(),
                                            transactionAuthorizationCompletedData.getAuthorizationResultDto(),
                                            response.getOutcome()
                                    )
                            )
                            .flatMap(

                                    closureEvent -> sendRefundRequestEvent(
                                            Either.right(closureEvent),
                                            transactionAuthorizationCompletedData.getAuthorizationResultDto(),
                                            tx.getTransactionId()
                                    )
                                            .map(
                                                    (refundedEvent) -> Tuples.of(
                                                            Optional.<BaseTransactionEvent<?>>of(refundedEvent),
                                                            Either.<BaseTransactionEvent<?>, BaseTransactionEvent<?>>right(
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

                                // TODO: waiting for nodo update in order to have an error code to avoid check
                                // on fixed string
                                boolean isRefundable = exception instanceof BadGatewayException responseStatusException
                                        && HttpStatus.UNPROCESSABLE_ENTITY
                                                .equals(responseStatusException.getHttpStatus())
                                        && "Node did not receive RPT yet".equals(responseStatusException.getDetail());

                                log.error(
                                        "Got exception while invoking closePaymentV2 unrecoverable error: %s - isRefundable: %s "
                                                .formatted(unrecoverableError, isRefundable),
                                        exception
                                );
                                // the closure error event is build and sent iff the transaction was previously
                                // authorized
                                // and the error received from Nodo is a recoverable ones such as http code 500
                                it.pagopa.ecommerce.commons.documents.v1.TransactionClosureErrorEvent errorEvent = new it.pagopa.ecommerce.commons.documents.v1.TransactionClosureErrorEvent(
                                        tx.getTransactionId().value()
                                );

                                Mono<it.pagopa.ecommerce.commons.documents.v1.TransactionClosureErrorEvent> eventSaved = transactionClosureErrorEventStoreRepository
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
                                                    Optional.<BaseTransactionEvent<?>>empty(),
                                                    Either.<BaseTransactionEvent<?>, BaseTransactionEvent<?>>left(
                                                            closureErrorEvent
                                                    )
                                            )
                                    );
                                }

                                // Unrecoverable error calling Nodo for perform close payment.
                                // Generate closure event setting closure outcome to KO
                                // and enqueue refund request event
                                if (isRefundable) {
                                    return eventSaved
                                            .flatMap(

                                                    closureErrorEvent -> sendRefundRequestEvent(
                                                            Either.left(closureErrorEvent),
                                                            transactionAuthorizationCompletedData
                                                                    .getAuthorizationResultDto(),
                                                            tx.getTransactionId()
                                                    ).map(
                                                            (refundRequestedEvent) -> Tuples.of(
                                                                    Optional.<BaseTransactionEvent<?>>of(
                                                                            refundRequestedEvent
                                                                    ),
                                                                    Either.<BaseTransactionEvent<?>, BaseTransactionEvent<?>>left(
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
                                }

                                /*
                                 * When a transaction is not recoverable through retries and cannot be refunded,
                                 * then the API returns a 502 status code along with the reason from the Node's
                                 * status code. The transaction status remains AUTHORIZATION_COMPLETED
                                 */
                                return Mono.error(
                                        new BadGatewayException(
                                                "Error while invoke Nodo closePayment",
                                                HttpStatus.BAD_GATEWAY
                                        )
                                );

                            });
                });
    }

    private TransactionDetailsDto buildTransactionDetailsDto(
                                                             it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedData transactionActivatedData,
                                                             AuthRequestDataUtils.AuthRequestData authRequestData,
                                                             it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData transactionAuthorizationRequestData,
                                                             it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedData transactionAuthorizationCompletedData,
                                                             BigDecimal fee,
                                                             BigDecimal amount,
                                                             BigDecimal totalAmount,
                                                             ClosePaymentRequestV2Dto.OutcomeEnum outcomeEnum,
                                                             TransactionId transactionId,
                                                             ZonedDateTime creationDate
    ) {
        return new TransactionDetailsDto()
                .transaction(
                        buildTransactionDto(
                                authRequestData,
                                transactionAuthorizationRequestData,
                                transactionAuthorizationCompletedData,
                                fee,
                                amount,
                                totalAmount,
                                outcomeEnum,
                                transactionId,
                                creationDate
                        )
                )
                .info(
                        buildInfoDto(transactionActivatedData, transactionAuthorizationRequestData)
                )
                .user(new UserDto().type(UserDto.TypeEnum.GUEST));

    }

    private TransactionDto buildTransactionDto(
                                               AuthRequestDataUtils.AuthRequestData authRequestData,
                                               it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData transactionAuthorizationRequestData,
                                               it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedData transactionAuthorizationCompletedData,
                                               BigDecimal fee,
                                               BigDecimal amount,
                                               BigDecimal totalAmount,
                                               ClosePaymentRequestV2Dto.OutcomeEnum outcomeEnum,
                                               TransactionId transationId,
                                               ZonedDateTime creationDate
    ) {
        return new TransactionDto()
                .transactionStatus(
                        ClosePaymentRequestV2Dto.OutcomeEnum.OK.equals(outcomeEnum) ? CONFERMATO : RIFIUTATO
                )
                .fee(fee)
                .amount(amount)
                .grandTotal(totalAmount)
                .transactionId(
                        transationId.value()
                )
                .creationDate(
                        creationDate.toOffsetDateTime()
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

    private PspDto buildPspDto(
                               it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData transactionAuthorizationRequestData
    ) {

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
                                 it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedData transactionActivatedData,
                                 it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData transactionAuthorizationRequestData
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

    private Mono<it.pagopa.ecommerce.commons.documents.v1.TransactionRefundRequestedEvent> sendRefundRequestEvent(
                                                                                                                  Either<it.pagopa.ecommerce.commons.documents.v1.TransactionClosureErrorEvent, it.pagopa.ecommerce.commons.documents.v1.TransactionEvent<it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData>> closureOutcomeEvent,
                                                                                                                  AuthorizationResultDto authorizationResult,
                                                                                                                  TransactionId transactionId
    ) {
        return Mono.just(closureOutcomeEvent)
                .filter(
                        e -> e.fold(
                                closureErrorEvent -> AuthorizationResultDto.OK.equals(authorizationResult),
                                // Refund requested event sent on the queue only if the transaction was
                                // previously
                                // authorized and the Nodo response outcome is KO
                                closureEvent -> it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData.Outcome.KO
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
                                    return TransactionStatusDto.CLOSURE_ERROR;

                                },

                                closureEvent -> {
                                    log.info(
                                            "Requesting refund for transaction {} as it was previously authorized but we either received KO response from Nodo",
                                            closureEvent.getTransactionId()
                                    );
                                    return TransactionStatusDto.CLOSED;
                                }
                        )
                )
                .flatMap(previousStatus -> {

                    it.pagopa.ecommerce.commons.documents.v1.TransactionRefundRequestedEvent refundRequestedEvent = new it.pagopa.ecommerce.commons.documents.v1.TransactionRefundRequestedEvent(
                            transactionId.value(),
                            new it.pagopa.ecommerce.commons.documents.v1.TransactionRefundedData(previousStatus)
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

    private Mono<it.pagopa.ecommerce.commons.documents.v1.TransactionEvent<it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData>> buildAndSaveClosureEvent(
            TransactionId transactionId,
            AuthorizationResultDto authorizationResult,
            ClosePaymentResponseDto.OutcomeEnum nodoOutcome
    ) {
        it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData.Outcome eventNodoOutcome = outcomeV2ToTransactionClosureDataOutcome(nodoOutcome);
        it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData transactionClosureData = new it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData(eventNodoOutcome);
        Mono<it.pagopa.ecommerce.commons.documents.v1.TransactionEvent<it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData>> closureEvent = switch (authorizationResult) {
            case OK ->
                    Mono.just(new it.pagopa.ecommerce.commons.documents.v1.TransactionClosedEvent(transactionId.value(), transactionClosureData));
            case KO ->
                    Mono.just(new it.pagopa.ecommerce.commons.documents.v1.TransactionClosureFailedEvent(transactionId.value(), transactionClosureData));
            case null, default -> Mono.error(
                    new IllegalArgumentException(
                            "Unhandled authorization result: %s".formatted(authorizationResult)
                    )
            );
        };
        return closureEvent
                .flatMap(transactionEventStoreRepository::save);
    }

    private it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData.Outcome outcomeV2ToTransactionClosureDataOutcome(
                                                                                                                             ClosePaymentResponseDto.OutcomeEnum closePaymentOutcome
    ) {
        switch (closePaymentOutcome) {
            case OK -> {
                return it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData.Outcome.OK;
            }
            case KO -> {
                return it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData.Outcome.KO;
            }
            default -> throw new IllegalArgumentException(
                    "Missing transaction closure data outcome mapping to Nodo closePaymentV2 outcome"
            );
        }
    }

}
