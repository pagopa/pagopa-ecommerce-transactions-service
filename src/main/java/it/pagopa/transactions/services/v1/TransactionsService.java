package it.pagopa.transactions.services.v1;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.BaseTransactionView;
import it.pagopa.ecommerce.commons.documents.PaymentTransferInformation;
import it.pagopa.ecommerce.commons.documents.v2.*;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction;
import it.pagopa.ecommerce.commons.domain.v1.TransactionEventCode;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.domain.v2.*;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransactionWithPaymentToken;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.redis.templatewrappers.v2.PaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;
import it.pagopa.generated.ecommerce.paymentmethods.v2.dto.*;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.generated.wallet.v1.dto.WalletAuthCardDataDto;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.WalletClient;
import it.pagopa.transactions.commands.*;
import it.pagopa.transactions.commands.data.*;
import it.pagopa.transactions.commands.handlers.v2.*;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.projections.handlers.v2.*;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import it.pagopa.transactions.utils.PaymentSessionData;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.UUIDUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.function.TupleUtils;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple4;
import reactor.util.function.Tuples;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static it.pagopa.generated.transactions.v2.server.model.OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED;

@Service(TransactionsService.QUALIFIER_NAME)
@Slf4j
public class TransactionsService {

    public static final String QUALIFIER_NAME = "TransactionsServiceV1";

    private final TransactionActivateHandler transactionActivateHandlerV2;

    private final TransactionRequestAuthorizationHandler requestAuthHandlerV2;

    private final TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandlerV2;

    private final TransactionSendClosureRequestHandler transactionSendClosureRequestHandler;

    private final TransactionRequestUserReceiptHandler transactionRequestUserReceiptHandlerV2;

    private final TransactionUserCancelHandler transactionCancelHandlerV2;

    private final AuthorizationRequestProjectionHandler authorizationProjectionHandlerV2;

    private final AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandlerV2;

    private final ClosureRequestedProjectionHandler closureRequestedProjectionHandler;

    private final CancellationRequestProjectionHandler cancellationRequestProjectionHandlerV2;

    private final TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandlerV2;

    private final TransactionsActivationProjectionHandler transactionsActivationProjectionHandlerV2;

    private final TransactionsViewRepository transactionsViewRepository;

    private final EcommercePaymentMethodsClient ecommercePaymentMethodsClient;

    private final WalletClient walletClient;

    private final TransactionsUtils transactionsUtils;

    private final TransactionsEventStoreRepository<Object> eventsRepository;

    private final PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper;

    private final ConfidentialMailUtils confidentialMailUtils;

    private final UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils;

    private final Map<String, TransactionOutcomeInfoDto.OutcomeEnum> npgAuthorizationErrorCodeMapping;
    private final Set<TransactionStatusDto> ecommerceFinalStates;
    private final Set<TransactionStatusDto> ecommercePossibleFinalState;

    @Autowired
    public TransactionsService(
            @Qualifier(
                TransactionActivateHandler.QUALIFIER_NAME
            ) TransactionActivateHandler transactionActivateHandlerV2,
            TransactionRequestAuthorizationHandler requestAuthHandlerV2,
            @Qualifier(
                TransactionUpdateAuthorizationHandler.QUALIFIER_NAME
            ) TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandlerV2,
            TransactionSendClosureRequestHandler transactionSendClosureRequestHandler,
            @Qualifier(
                TransactionRequestUserReceiptHandler.QUALIFIER_NAME
            ) TransactionRequestUserReceiptHandler transactionRequestUserReceiptHandlerV2,
            @Qualifier(
                TransactionUserCancelHandler.QUALIFIER_NAME
            ) TransactionUserCancelHandler transactionCancelHandlerV2,
            @Qualifier(
                AuthorizationRequestProjectionHandler.QUALIFIER_NAME
            ) AuthorizationRequestProjectionHandler authorizationProjectionHandlerV2,
            @Qualifier(
                AuthorizationUpdateProjectionHandler.QUALIFIER_NAME
            ) AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandlerV2,
            ClosureRequestedProjectionHandler closureRequestedProjectionHandler,
            @Qualifier(
                CancellationRequestProjectionHandler.QUALIFIER_NAME
            ) CancellationRequestProjectionHandler cancellationRequestProjectionHandlerV2,
            @Qualifier(
                TransactionUserReceiptProjectionHandler.QUALIFIER_NAME
            ) TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandlerV2,
            @Qualifier(
                TransactionsActivationProjectionHandler.QUALIFIER_NAME
            ) TransactionsActivationProjectionHandler transactionsActivationProjectionHandlerV2,
            TransactionsViewRepository transactionsViewRepository,
            EcommercePaymentMethodsClient ecommercePaymentMethodsClient,
            WalletClient walletClient,
            UUIDUtils uuidUtils,
            TransactionsUtils transactionsUtils,
            TransactionsEventStoreRepository<Object> eventsRepository,
            @Value("${payment.token.validity}") Integer paymentTokenValidity,
            PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper,
            ConfidentialMailUtils confidentialMailUtils,
            UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils,
            @Value("#{${npg.authorizationErrorCodeMapping}}") Map<String, String> npgAuthorizationErrorCodeMapping,
            @Value("${ecommerce.finalStates}") Set<String> ecommerceFinalStates,
            @Value("${ecommerce.possibleFinalStates}") Set<String> ecommercePossibleFinalStates
    ) {
        this.transactionActivateHandlerV2 = transactionActivateHandlerV2;
        this.requestAuthHandlerV2 = requestAuthHandlerV2;
        this.transactionUpdateAuthorizationHandlerV2 = transactionUpdateAuthorizationHandlerV2;
        this.transactionSendClosureRequestHandler = transactionSendClosureRequestHandler;
        this.transactionRequestUserReceiptHandlerV2 = transactionRequestUserReceiptHandlerV2;
        this.transactionCancelHandlerV2 = transactionCancelHandlerV2;
        this.authorizationProjectionHandlerV2 = authorizationProjectionHandlerV2;
        this.authorizationUpdateProjectionHandlerV2 = authorizationUpdateProjectionHandlerV2;
        this.closureRequestedProjectionHandler = closureRequestedProjectionHandler;
        this.cancellationRequestProjectionHandlerV2 = cancellationRequestProjectionHandlerV2;
        this.transactionUserReceiptProjectionHandlerV2 = transactionUserReceiptProjectionHandlerV2;
        this.transactionsActivationProjectionHandlerV2 = transactionsActivationProjectionHandlerV2;
        this.transactionsViewRepository = transactionsViewRepository;
        this.ecommercePaymentMethodsClient = ecommercePaymentMethodsClient;
        this.walletClient = walletClient;
        this.transactionsUtils = transactionsUtils;
        this.eventsRepository = eventsRepository;
        this.paymentRequestInfoRedisTemplateWrapper = paymentRequestInfoRedisTemplateWrapper;
        this.confidentialMailUtils = confidentialMailUtils;
        this.updateTransactionStatusTracerUtils = updateTransactionStatusTracerUtils;
        this.npgAuthorizationErrorCodeMapping = npgAuthorizationErrorCodeMapping.entrySet().stream().collect(
                Collectors.toMap(
                        Map.Entry::getKey,
                        outcome -> TransactionOutcomeInfoDto.OutcomeEnum
                                .fromValue(BigDecimal.valueOf(Long.parseLong(outcome.getValue())))
                )
        );
        this.ecommerceFinalStates = ecommerceFinalStates.stream().map(TransactionStatusDto::valueOf)
                .collect(Collectors.toSet());
        this.ecommercePossibleFinalState = ecommercePossibleFinalStates.stream().map(TransactionStatusDto::valueOf)
                .collect(Collectors.toSet());
    }

    @CircuitBreaker(name = "node-backend")
    @Retry(name = "newTransaction")
    public Mono<NewTransactionResponseDto> newTransaction(
                                                          NewTransactionRequestDto newTransactionRequestDto,
                                                          ClientIdDto clientIdDto,
                                                          TransactionId transactionId
    ) {
        Transaction.ClientId clientId = Transaction.ClientId.fromString(
                Optional.ofNullable(clientIdDto)
                        .map(ClientIdDto::toString)
                        .orElse(null)
        );

        TransactionActivateCommand transactionActivateCommand = new TransactionActivateCommand(
                newTransactionRequestDto.getPaymentNotices().stream().map(p -> new RptId(p.getRptId())).toList(),
                new NewTransactionRequestData(
                        newTransactionRequestDto.getIdCart(),
                        confidentialMailUtils.toConfidential(newTransactionRequestDto.getEmail()),
                        null,
                        null,
                        newTransactionRequestDto.getPaymentNotices().stream().map(
                                el -> new PaymentNotice(
                                        null,
                                        new RptId(el.getRptId()),
                                        new TransactionAmount(el.getAmount()),
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null
                                )
                        ).toList()
                ),
                clientId.name(),
                transactionId,
                null
        );
        log.info(
                "Initializing transaction for rptIds: {}. ClientId: {}",
                transactionActivateCommand.getRptIds().stream().map(RptId::value).toList(),
                clientId
        );
        return transactionActivateHandlerV2.handle(transactionActivateCommand)
                .doOnNext(
                        args -> log.info(
                                "Transaction initialized for rptIds: {}",
                                transactionActivateCommand.getRptIds().stream().map(RptId::value).toList()
                        )
                )
                .flatMap(
                        es -> {
                            final Mono<BaseTransactionEvent<?>> transactionActivatedEvent = es
                                    .getT1();
                            final String authToken = es.getT2();
                            return transactionActivatedEvent
                                    .flatMap(
                                            t -> projectActivatedEventV2(
                                                    (TransactionActivatedEvent) t,
                                                    authToken
                                            )
                                    );
                        }
                );
    }

    @CircuitBreaker(name = "ecommerce-db")
    @Retry(name = "getTransactionInfo")
    public Mono<TransactionInfoDto> getTransactionInfo(
                                                       String transactionId,
                                                       UUID xUserId
    ) {
        log.info("Get Transaction Invoked with id {} ", transactionId);
        return getBaseTransactionView(transactionId, xUserId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .map(this::buildTransactionInfoDtoFromView);
    }

    @CircuitBreaker(name = "ecommerce-db")
    @Retry(name = "getTransactionOutcome")
    public Mono<TransactionOutcomeInfoDto> getTransactionOutcome(
                                                                 String transactionId,
                                                                 UUID xUserId
    ) {
        log.info("Get transaction outcome invoked with id {} ", transactionId);
        return getBaseTransactionView(transactionId, xUserId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .map(this::buildTransactionOutcomeInfoDtoFromView);
    }

    private TransactionOutcomeInfoDto buildTransactionOutcomeInfoDtoFromView(BaseTransactionView baseTransactionView) {
        switch (baseTransactionView) {
            case Transaction transaction -> {
                TransactionOutcomeInfoDto.OutcomeEnum outcome = evaluateOutcome(transaction.getStatus(), transaction.getSendPaymentResultOutcome(), transaction.getPaymentGateway(), transaction.getGatewayAuthorizationStatus(), transaction.getAuthorizationErrorCode(), transaction.getClosureErrorData());
                return new TransactionOutcomeInfoDto()
                        .outcome(outcome)
                        .totalAmount(outcome == TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_0 ? transaction.getPaymentNotices().stream().mapToInt(it.pagopa.ecommerce.commons.documents.PaymentNotice::getAmount).sum() : null)
                        .fees(outcome == TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_0 ? +Optional.ofNullable(transaction.getFeeTotal()).orElse(0) : null)
                        .isFinalStatus(evaluateFinalStatus(transaction.getStatus(), transaction.getClosureErrorData(), transaction.getPaymentGateway(), transaction.getGatewayAuthorizationStatus()));
            }
            default -> throw new IllegalStateException("Unexpected value: " + baseTransactionView);
        }
    }

    private Boolean evaluateFinalStatus(
                                        TransactionStatusDto status,
                                        ClosureErrorData closureErrorData,
                                        String paymentGateway,
                                        String gatewayAuthorizationStatus
    ) {
        return ecommerceFinalStates.contains(status) ||
                (closureErrorData != null && closureErrorData.getHttpErrorCode() != null
                        && closureErrorData.getHttpErrorCode().is4xxClientError())
                ||
                (ecommercePossibleFinalState.contains(status)
                        && !wasAuthorizedByGateway(paymentGateway, gatewayAuthorizationStatus));
    }

    private boolean wasAuthorizedByGateway(String gateway, String gatewayAuthorizationStatus) {
        return switch (gateway) {
            case "NPG" -> EXECUTED.getValue().equals(gatewayAuthorizationStatus);
            case "REDIRECT" -> "OK".equals(gatewayAuthorizationStatus);
            case null, default -> false;
        };
    }

    private TransactionOutcomeInfoDto.OutcomeEnum evaluateOutcome(
            TransactionStatusDto status,
            TransactionUserReceiptData.Outcome sendPaymentResultOutcome,
            String paymentGateway,
            String gatewayAuthorizationStatus,
            String authorizationErrorCode,
            ClosureErrorData closureErrorData
    ) {
        if (closureErrorData != null) {
            return wasAuthorizedByGateway(paymentGateway, gatewayAuthorizationStatus) ?
                    evaluateClosePaymentResultError(closureErrorData) : //Authorized
                    evaluateUnauthorizedStatus(paymentGateway, gatewayAuthorizationStatus, authorizationErrorCode); //Not authorized
        } else {
            switch (status) {
                case NOTIFIED_OK -> {
                    return TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_0;
                }
                case NOTIFICATION_REQUESTED, NOTIFICATION_ERROR -> {
                    return TransactionUserReceiptData.Outcome.OK.equals(sendPaymentResultOutcome)
                            ? TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_0
                            : TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25;
                }
                case NOTIFIED_KO, REFUNDED -> {
                    return TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25;
                }
                case EXPIRED_NOT_AUTHORIZED -> {
                    return TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_4;
                }
                case CANCELED, CANCELLATION_EXPIRED -> {
                    return TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_8;
                }
                case CLOSURE_ERROR, AUTHORIZATION_COMPLETED -> {
                    return wasAuthorizedByGateway(paymentGateway, gatewayAuthorizationStatus) ?
                            TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1 : //Authorized
                            evaluateUnauthorizedStatus(
                                    paymentGateway,
                                    gatewayAuthorizationStatus,
                                    authorizationErrorCode); //Not authorized
                }
                case CLOSURE_REQUESTED -> {
                    return wasAuthorizedByGateway(paymentGateway, gatewayAuthorizationStatus) ?
                            TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_17 :
                            evaluateUnauthorizedStatus(
                                    paymentGateway,
                                    gatewayAuthorizationStatus,
                                    authorizationErrorCode);
                }
                case UNAUTHORIZED -> {
                    return wasAuthorizedByGateway(paymentGateway, gatewayAuthorizationStatus) ?
                            TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25 :
                            evaluateUnauthorizedStatus(
                                    paymentGateway,
                                    gatewayAuthorizationStatus,
                                    authorizationErrorCode);
                }
                case CLOSED -> {
                    return sendPaymentResultOutcome != null
                            && sendPaymentResultOutcome.equals(TransactionUserReceiptData.Outcome.NOT_RECEIVED)
                            ? TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_17
                            : TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1;
                }
                case EXPIRED -> {
                    if (paymentGateway == null || gatewayAuthorizationStatus == null)
                        return TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_17;
                    else if (!wasAuthorizedByGateway(paymentGateway, gatewayAuthorizationStatus)) {
                        return evaluateUnauthorizedStatus(paymentGateway, gatewayAuthorizationStatus, authorizationErrorCode);
                    } else {
                        return switch (sendPaymentResultOutcome) {
                            case OK -> TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_0;
                            case KO -> TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25;
                            case NOT_RECEIVED -> TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_17;
                            case null -> TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1;
                        };
                    }
                }
                case AUTHORIZATION_REQUESTED -> {
                    return TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_17;
                }
                case REFUND_ERROR, REFUND_REQUESTED -> {
                    return TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1;
                }
                case null, default -> {
                    return TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1;
                }
            }
        }
    }

    private TransactionOutcomeInfoDto.OutcomeEnum evaluateClosePaymentResultError(ClosureErrorData closureErrorData) {
        HttpStatus status = closureErrorData.getHttpErrorCode();
        String errorDescription = closureErrorData.getErrorDescription();
        return switch (status) {
            case UNPROCESSABLE_ENTITY -> "Node did not receive RPT yet".equals(errorDescription)
                    ? TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_18
                    : TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1;

            case BAD_REQUEST, NOT_FOUND -> TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_18;
            case null, default -> TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1;

        };
    }

    private TransactionOutcomeInfoDto.OutcomeEnum evaluateUnauthorizedStatus(String paymentGateway, String gatewayAuthorizationStatus, String authorizationErrorCode) {
        return switch (paymentGateway) {
            case "NPG" -> switch (gatewayAuthorizationStatus) {
                case "CANCELED" -> TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_8;
                case "DENIED_BY_RISK", "THREEDS_VALIDATED", "THREEDS_FAILED" ->
                        TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2;
                case "AUTHORIZED", "PENDING", "VOIDED", "REFUNDED", "FAILED" ->
                        TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25;
                case "DECLINED" ->
                        Optional.ofNullable(npgAuthorizationErrorCodeMapping.get(authorizationErrorCode)).orElse(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25);
                case null, default -> TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25;
            };
            case "REDIRECT" -> switch (gatewayAuthorizationStatus) {
                case "KO" -> TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2;
                case "CANCELED" -> TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_8;
                case "ERROR", "EXPIRED" -> TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25;
                case null, default -> TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25;
            };
            case null, default -> TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1;
        };
    }

    private TransactionInfoDto buildTransactionInfoDtoFromView(BaseTransactionView baseTransactionView) {
        return switch (baseTransactionView) {
            case it.pagopa.ecommerce.commons.documents.v1.Transaction transaction -> new TransactionInfoDto()
                    .transactionId(transaction.getTransactionId())
                    .payments(
                            transaction.getPaymentNotices().stream().map(
                                    paymentNotice -> new PaymentInfoDto()
                                            .amount(paymentNotice.getAmount())
                                            .reason(paymentNotice.getDescription())
                                            .paymentToken(paymentNotice.getPaymentToken())
                                            .rptId(paymentNotice.getRptId())
                                            .isAllCCP(paymentNotice.isAllCCP())
                                            .transferList(
                                                    paymentNotice.getTransferList().stream().map(
                                                            notice -> new TransferDto()
                                                                    .transferCategory(
                                                                            notice.getTransferCategory()
                                                                    )
                                                                    .transferAmount(
                                                                            notice.getTransferAmount()
                                                                    ).digitalStamp(notice.getDigitalStamp())
                                                                    .paFiscalCode(notice.getPaFiscalCode())
                                                    ).toList()
                                            )
                            ).toList()
                    )
                    .feeTotal(transaction.getFeeTotal())
                    .clientId(
                            TransactionInfoDto.ClientIdEnum.valueOf(
                                    transaction.getClientId().toString()
                            )
                    )
                    .status(transactionsUtils.convertEnumerationV1(transaction.getStatus()))
                    .idCart(transaction.getIdCart())
                    .gateway(transaction.getPaymentGateway())
                    .sendPaymentResultOutcome(
                            transaction.getSendPaymentResultOutcome() == null ? null
                                    : TransactionInfoDto.SendPaymentResultOutcomeEnum
                                    .valueOf(transaction.getSendPaymentResultOutcome().name())
                    )
                    .authorizationCode(transaction.getAuthorizationCode())
                    .errorCode(transaction.getAuthorizationErrorCode());
            case Transaction transaction -> new TransactionInfoDto()
                    .transactionId(transaction.getTransactionId())
                    .payments(
                            transaction.getPaymentNotices().stream().map(
                                    paymentNotice -> new PaymentInfoDto()
                                            .amount(paymentNotice.getAmount())
                                            .reason(paymentNotice.getDescription())
                                            .paymentToken(paymentNotice.getPaymentToken())
                                            .rptId(paymentNotice.getRptId())
                                            .isAllCCP(paymentNotice.isAllCCP())
                                            .transferList(
                                                    paymentNotice.getTransferList().stream().map(
                                                            notice -> new TransferDto()
                                                                    .transferCategory(
                                                                            notice.getTransferCategory()
                                                                    )
                                                                    .transferAmount(
                                                                            notice.getTransferAmount()
                                                                    ).digitalStamp(notice.getDigitalStamp())
                                                                    .paFiscalCode(notice.getPaFiscalCode())
                                                    ).toList()
                                            )
                            ).toList()
                    )
                    .feeTotal(transaction.getFeeTotal())
                    .clientId(
                            TransactionInfoDto.ClientIdEnum.valueOf(
                                    transaction.getClientId().getEffectiveClient().toString()
                            )
                    )
                    .status(transactionsUtils.convertEnumerationV1(transaction.getStatus()))
                    .idCart(transaction.getIdCart())
                    .gateway(transaction.getPaymentGateway())
                    .sendPaymentResultOutcome(
                            transaction.getSendPaymentResultOutcome() == null ? null
                                    : TransactionInfoDto.SendPaymentResultOutcomeEnum
                                    .valueOf(transaction.getSendPaymentResultOutcome().name())
                    )
                    .authorizationCode(transaction.getAuthorizationCode())
                    .errorCode(transaction.getAuthorizationErrorCode())
                    .gatewayAuthorizationStatus(transaction.getGatewayAuthorizationStatus());
            default -> throw new IllegalStateException("Unexpected value: " + baseTransactionView);
        };
    }

    @Retry(name = "cancelTransaction")
    public Mono<Void> cancelTransaction(
                                        String transactionId,
                                        UUID xUserId
    ) {
        return transactionCancelHandlerV2
                .handle(
                        new TransactionUserCancelCommand(
                                null,
                                new TransactionId((transactionId)),
                                xUserId
                        )
                ).flatMap(
                        event -> cancellationRequestProjectionHandlerV2
                                .handle((TransactionUserCanceledEvent) event)
                )
                .then();

    }

    @Retry(name = "requestTransactionAuthorization")
    public Mono<RequestAuthorizationResponseDto> requestTransactionAuthorization(
                                                                                 String transactionId,
                                                                                 UUID xUserId,
                                                                                 String paymentGatewayId,
                                                                                 String lang,
                                                                                 RequestAuthorizationRequestDto authRequest
    ) {
        return eventsRepository
                .findByTransactionIdAndEventCode(
                        transactionId,
                        TransactionEventCode.TRANSACTION_ACTIVATED_EVENT.toString()
                )
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .cast(TransactionActivatedEvent.class)
                .flatMap(transaction -> validateTransactionDetails(transaction, authRequest))
                .flatMap(transaction -> processAuthRequest(transaction, authRequest))
                .flatMap(args -> executeAuthPipeline(args, lang, authRequest, paymentGatewayId));
    }

    /**
     * Executes the authorization pipeline
     *
     * @param args             A tuple containing the transaction and authorization
     *                         session data
     * @param lang             The language code
     * @param authRequest      The authorization request
     * @param paymentGatewayId The payment gateway ID
     * @return A Mono containing the authorization response
     */
    private Mono<RequestAuthorizationResponseDto> executeAuthPipeline(
                                                                      Tuple2<TransactionActivatedEvent, AuthorizationRequestSessionData> args,
                                                                      String lang,
                                                                      RequestAuthorizationRequestDto authRequest,
                                                                      String paymentGatewayId
    ) {
        TransactionActivatedEvent transactionDocument = args.getT1();
        AuthorizationRequestSessionData authRequestSessionData = args.getT2();

        log.info("Requesting authorization for transactionId: {}", transactionDocument.getTransactionId());

        AuthorizationRequestData authData = createAuthRequestData(
                transactionDocument,
                authRequestSessionData,
                authRequest,
                paymentGatewayId
        );

        TransactionRequestAuthorizationCommand transactionRequestAuthCommand = new TransactionRequestAuthorizationCommand(
                transactionsUtils.getRptIds(transactionDocument).stream().map(RptId::new).toList(),
                lang,
                authData
        );

        return executeHandlerBasedOnTransactionType(transactionDocument, transactionRequestAuthCommand, authData)
                .doOnSuccess(invalidateCacheFor(transactionDocument));
    }

    /**
     * Creates authorization request data from transaction and session data
     *
     * @param transactionActivatedEvent The transaction document
     * @param authRequestSessionData    The authorization session data
     * @param authRequest               The authorization request
     * @param paymentGatewayId          The payment gateway ID
     * @return Authorization request data
     */
    private AuthorizationRequestData createAuthRequestData(
                                                           TransactionActivatedEvent transactionActivatedEvent,
                                                           AuthorizationRequestSessionData authRequestSessionData,
                                                           RequestAuthorizationRequestDto authRequest,
                                                           String paymentGatewayId
    ) {

        BundleDto bundle = authRequestSessionData.bundle().orElseThrow();
        String paymentMethodName = authRequestSessionData.paymentMethodName();
        String paymentMethodDescription = authRequestSessionData.paymentMethodDescription();
        Optional<String> sessionId = authRequestSessionData.npgSessionId();
        String brand = authRequestSessionData.brand();
        Optional<String> contractId = authRequestSessionData.npgContractId();
        String asset = authRequestSessionData.asset();
        Map<String, String> brandAssets = authRequestSessionData.brandAssets();

        return new AuthorizationRequestData(
                new TransactionId(transactionActivatedEvent.getTransactionId()),
                mapTransactionActivatedEventToV2PaymentNoticeList(transactionActivatedEvent),
                transactionsUtils.getEmail(transactionActivatedEvent),
                authRequest.getFee(),
                authRequest.getPaymentInstrumentId(),
                authRequest.getPspId(),
                bundle.getPaymentMethod(),
                bundle.getIdBrokerPsp(),
                bundle.getIdChannel(),
                paymentMethodName,
                paymentMethodDescription,
                bundle.getPspBusinessName(),
                bundle.getOnUs(),
                paymentGatewayId,
                sessionId,
                contractId,
                brand,
                authRequest.getDetails(),
                asset,
                Optional.ofNullable(brandAssets),
                bundle.getIdBundle()
        );
    }

    /**
     * Executes the appropriate handler based on the transaction type
     *
     * @param transactionActivatedEvent     The transaction document
     * @param transactionRequestAuthCommand The authorization command
     * @param authData                      The authorization data
     * @return A Mono containing the authorization response
     */
    private Mono<RequestAuthorizationResponseDto> executeHandlerBasedOnTransactionType(
                                                                                       TransactionActivatedEvent transactionActivatedEvent,
                                                                                       TransactionRequestAuthorizationCommand transactionRequestAuthCommand,
                                                                                       AuthorizationRequestData authData
    ) {

        return requestAuthHandlerV2
                .handleWithCreationDate(transactionRequestAuthCommand)
                .doOnNext(
                        responseAndDate -> logAuthRequestedFor(transactionActivatedEvent.getTransactionId())
                                .accept(responseAndDate.getT1())
                )
                .flatMap(
                        responseAndDate -> authorizationProjectionHandlerV2
                                .handle(new AuthorizationRequestedEventData(authData, responseAndDate.getT2()))
                                .thenReturn(responseAndDate.getT1())
                );

    }

    /**
     * Creates a consumer that logs when authorization is requested for a specific
     * transaction
     *
     * @param transactionId the ID of the transaction being authorized
     * @return a consumer that logs the authorization request
     */
    private Consumer<RequestAuthorizationResponseDto> logAuthRequestedFor(String transactionId) {
        return response -> logAuthRequested(transactionId);
    }

    /**
     * Logs that authorization has been requested for a transaction
     *
     * @param transactionId The ID of the transaction
     */
    private void logAuthRequested(String transactionId) {
        log.info("Requested authorization for transaction: {}", transactionId);
    }

    /**
     * Creates a consumer that invalidates the cache for a transaction
     *
     * @param transactionActivatedEvent the transaction document whose cache should
     *                                  be invalidated
     * @return a consumer that invalidates the cache entry
     */
    private Consumer<RequestAuthorizationResponseDto> invalidateCacheFor(
                                                                         TransactionActivatedEvent transactionActivatedEvent
    ) {
        return response -> invalidatePaymentRequestCache(transactionActivatedEvent);
    }

    /**
     * Invalidates the payment request cache for a transaction
     *
     * @param transactionActivatedEvent The transaction document
     */
    private void invalidatePaymentRequestCache(TransactionActivatedEvent transactionActivatedEvent) {
        transactionsUtils.getPaymentNotices(transactionActivatedEvent).forEach(this::invalidateRptIdCache);
    }

    /**
     * Invalidates the cache for a specific payment notice
     *
     * @param paymentNotice The payment notice to invalidate
     */
    private void invalidateRptIdCache(it.pagopa.ecommerce.commons.documents.PaymentNotice paymentNotice) {
        log.info("Invalidate cache for RptId : {}", paymentNotice.getRptId());
        paymentRequestInfoRedisTemplateWrapper.deleteById(paymentNotice.getRptId());
    }

    /**
     * Maps a transaction view document to a list of V2 payment notices
     *
     * @param transactionActivatedEvent The transaction view document to map
     * @return A list of V2 payment notices
     */
    private List<PaymentNotice> mapTransactionActivatedEventToV2PaymentNoticeList(
                                                                                  TransactionActivatedEvent transactionActivatedEvent
    ) {
        return transactionsUtils.getPaymentNotices(transactionActivatedEvent).stream()
                .map(this::mapPaymentNoticeToV2)
                .toList();
    }

    /**
     * Maps a payment notice to a V2 payment notice
     *
     * @param paymentNotice The payment notice to map
     * @return A V2 payment notice
     */
    private PaymentNotice mapPaymentNoticeToV2(it.pagopa.ecommerce.commons.documents.PaymentNotice paymentNotice) {
        return new PaymentNotice(
                new PaymentToken(paymentNotice.getPaymentToken()),
                new RptId(paymentNotice.getRptId()),
                new TransactionAmount(paymentNotice.getAmount()),
                new TransactionDescription(paymentNotice.getDescription()),
                new PaymentContextCode(paymentNotice.getPaymentContextCode()),
                mapTransferInfoListToV2(paymentNotice.getTransferList()),
                paymentNotice.isAllCCP(),
                new CompanyName(paymentNotice.getCompanyName()),
                paymentNotice.getCreditorReferenceId()
        );
    }

    /**
     * Maps a list of payment transfer information objects to V2 payment transfer
     * info objects
     *
     * @param transferInfoList The list of transfer information objects
     * @return A list of V2 payment transfer info objects
     */
    private List<PaymentTransferInfo> mapTransferInfoListToV2(List<PaymentTransferInformation> transferInfoList) {
        return transferInfoList.stream()
                .map(this::mapTransferInfoToV2)
                .toList();
    }

    /**
     * Maps a payment transfer information object to a V2 payment transfer info
     * object
     *
     * @param transferInfo The V1 transfer information to map
     * @return A V2 payment transfer info
     */
    private PaymentTransferInfo mapTransferInfoToV2(PaymentTransferInformation transferInfo) {
        return new PaymentTransferInfo(
                transferInfo.getPaFiscalCode(),
                transferInfo.getDigitalStamp(),
                transferInfo.getTransferAmount(),
                transferInfo.getTransferCategory()
        );
    }

    /**
     * Processes the authorization request by retrieving payment session data and
     * calculating fees
     *
     * @param transaction The transaction to process
     * @param authRequest The authorization request data
     * @return A tuple containing the transaction and authorization session data
     */
    private Mono<Tuple2<TransactionActivatedEvent, AuthorizationRequestSessionData>> processAuthRequest(
                                                                                                        TransactionActivatedEvent transaction,
                                                                                                        RequestAuthorizationRequestDto authRequest
    ) {
        log.info("Authorization psp validation for transactionId: {}", transaction.getTransactionId());

        String clientId = transactionsUtils.getClientId(transaction);

        return retrieveInformationFromAuthorizationRequest(authRequest, clientId)
                .flatMap(
                        paymentSessionData -> calculateTransactionFee(
                                transaction,
                                authRequest,
                                paymentSessionData
                        )
                )
                .map(data -> createAuthSessionData(authRequest, data))
                .filter(authSessionData -> authSessionData.bundle().isPresent())
                .switchIfEmpty(
                        createUnsatisfiablePspRequestError(
                                transaction.getTransactionId(),
                                authRequest
                        )
                )
                .map(authSessionData -> Tuples.of(transaction, authSessionData));
    }

    /**
     * Calculates transaction fees based on payment session data
     *
     * @param transactionActivatedEvent The transaction being processed
     * @param authRequest               The authorization request data
     * @param paymentSessionData        The payment session data
     * @return A tuple containing the fee response and the payment session data
     */
    private Mono<Tuple2<CalculateFeeResponseDto, PaymentSessionData>> calculateTransactionFee(
                                                                                              TransactionActivatedEvent transactionActivatedEvent,
                                                                                              RequestAuthorizationRequestDto authRequest,
                                                                                              PaymentSessionData paymentSessionData
    ) {

        List<it.pagopa.ecommerce.commons.documents.PaymentNotice> paymentNotices = transactionsUtils
                .getPaymentNotices(transactionActivatedEvent);

        return ecommercePaymentMethodsClient
                .calculateFee(
                        authRequest.getPaymentInstrumentId(),
                        transactionActivatedEvent.getTransactionId(),
                        createCalculateFeeRequest(
                                transactionActivatedEvent,
                                authRequest,
                                paymentSessionData,
                                paymentNotices
                        ),
                        Integer.MAX_VALUE
                )
                .map(calculateFeeResponseDto -> Tuples.of(calculateFeeResponseDto, paymentSessionData));
    }

    /**
     * Creates a request object for calculating fees
     *
     * @param transaction        The transaction
     * @param authRequest        The authorization request
     * @param paymentSessionData The payment session data
     * @param paymentNotices     The payment notices associated with the transaction
     * @return A fee calculation request DTO
     */
    private CalculateFeeRequestDto createCalculateFeeRequest(
                                                             TransactionActivatedEvent transaction,
                                                             RequestAuthorizationRequestDto authRequest,
                                                             PaymentSessionData paymentSessionData,
                                                             List<it.pagopa.ecommerce.commons.documents.PaymentNotice> paymentNotices
    ) {
        return new CalculateFeeRequestDto()
                .touchpoint(transactionsUtils.getEffectiveClientId(transaction))
                .bin(paymentSessionData.cardBin())
                .idPspList(List.of(authRequest.getPspId()))
                .paymentNotices(mapPaymentNoticeListToV2DtoList(paymentNotices))
                .isAllCCP(transactionsUtils.isAllCcp(transaction, 0));
    }

    /**
     * Maps a list of payment notices to V2 payment notice DTOs
     *
     * @param paymentNotices The payment notices to map
     * @return A list of V2 payment notice DTOs
     */
    private List<PaymentNoticeDto> mapPaymentNoticeListToV2DtoList(
                                                                   List<it.pagopa.ecommerce.commons.documents.PaymentNotice> paymentNotices
    ) {
        return paymentNotices
                .stream()
                .map(this::mapPaymentNoticeToV2Dto)
                .toList();
    }

    /**
     * Maps a single payment notice to a V2 payment notice DTO
     *
     * @param paymentNotice The payment notice to map
     * @return A V2 payment notice DTO
     */
    private PaymentNoticeDto mapPaymentNoticeToV2Dto(
                                                     it.pagopa.ecommerce.commons.documents.PaymentNotice paymentNotice
    ) {
        return new PaymentNoticeDto()
                .paymentAmount(paymentNotice.getAmount().longValue())
                .primaryCreditorInstitution(paymentNotice.getRptId().substring(0, 11))
                .transferList(mapTransferInfoListToV2DtoList(paymentNotice.getTransferList()));
    }

    /**
     * Maps a list of payment transfer information objects to V2 transfer list item
     * DTOs
     *
     * @param transferList The list of transfer information objects
     * @return A list of V2 transfer list item DTOs
     */
    private List<TransferListItemDto> mapTransferInfoListToV2DtoList(List<PaymentTransferInformation> transferList) {
        return transferList
                .stream()
                .map(this::mapTransferInfoToV2Dto)
                .toList();
    }

    /**
     * Maps a payment transfer information object to a V2 transfer list item DTO
     *
     * @param transferInfo The transfer information to map
     * @return A V2 transfer list item DTO
     */
    private TransferListItemDto mapTransferInfoToV2Dto(PaymentTransferInformation transferInfo) {
        return new TransferListItemDto()
                .creditorInstitution(transferInfo.getPaFiscalCode())
                .digitalStamp(transferInfo.getDigitalStamp())
                .transferCategory(transferInfo.getTransferCategory());
    }

    /**
     * Creates an authorization session data object from fee calculation results
     *
     * @param authRequest The authorization request
     * @param paymentData A tuple containing fee calculation results and payment
     *                    session data
     * @return An authorization session data object
     */
    private AuthorizationRequestSessionData createAuthSessionData(
                                                                  RequestAuthorizationRequestDto authRequest,
                                                                  Tuple2<CalculateFeeResponseDto, PaymentSessionData> paymentData
    ) {

        CalculateFeeResponseDto calculateFeeResponse = paymentData.getT1();
        PaymentSessionData paymentSessionData = paymentData.getT2();

        return new AuthorizationRequestSessionData(
                calculateFeeResponse.getPaymentMethodName(),
                calculateFeeResponse.getPaymentMethodDescription(),
                findMatchingBundle(calculateFeeResponse, authRequest),
                paymentSessionData.brand(),
                Optional.ofNullable(paymentSessionData.sessionId()),
                Optional.ofNullable(paymentSessionData.contractId()),
                calculateFeeResponse.getAsset(),
                calculateFeeResponse.getBrandAssets()
        );
    }

    /**
     * Finds a bundle matching the PSP ID and fee from the authorization request
     *
     * @param calculateFeeResponse The fee calculation response
     * @param authRequest          The authorization request
     * @return An optional containing the matching bundle, or empty if no match
     *         found
     */
    private Optional<BundleDto> findMatchingBundle(
                                                   CalculateFeeResponseDto calculateFeeResponse,
                                                   RequestAuthorizationRequestDto authRequest
    ) {
        return calculateFeeResponse.getBundles().stream()
                .filter(psp -> isMatchingPspAndFee(psp, authRequest)).findFirst();
    }

    /**
     * Checks if a bundle matches the PSP ID and fee from the authorization request
     *
     * @param psp         The bundle to check
     * @param authRequest The authorization request
     * @return true if the bundle matches, false otherwise
     */
    private boolean isMatchingPspAndFee(
                                        BundleDto psp,
                                        RequestAuthorizationRequestDto authRequest
    ) {
        return authRequest.getPspId().equals(psp.getIdPsp())
                && Long.valueOf(authRequest.getFee()).equals(psp.getTaxPayerFee());
    }

    /**
     * Creates an error for unsatisfiable PSP request
     *
     * @param transactionId The transaction ID
     * @param authRequest   The authorization request
     * @return A Mono error with UnsatisfiablePspRequestException
     */
    private <T> Mono<T> createUnsatisfiablePspRequestError(
                                                           String transactionId,
                                                           RequestAuthorizationRequestDto authRequest
    ) {
        return Mono.error(
                new UnsatisfiablePspRequestException(
                        new PaymentToken(transactionId),
                        authRequest.getLanguage(),
                        authRequest.getFee()
                )
        );
    }

    /**
     * Validates transaction details against the authorization request
     *
     * @param transaction The transaction to validate
     * @param authRequest The authorization request to validate against
     * @return A Mono containing the validated transaction or an error
     */
    private Mono<TransactionActivatedEvent> validateTransactionDetails(
                                                                       TransactionActivatedEvent transaction,
                                                                       RequestAuthorizationRequestDto authRequest
    ) {
        String transactionId = transaction.getTransactionId();
        log.info("Authorization request amount validation for transactionId: {}", transactionId);

        if (hasAmountMismatch(authRequest, transaction)) {
            return createAmountMismatchError(authRequest, transaction);
        } else if (hasAllCCPMismatch(authRequest, transaction)) {
            return createAllCCPMismatchError(authRequest, transaction);
        }
        return Mono.just(transaction);
    }

    /**
     * Checks if there's a mismatch between the transaction amount and the requested
     * amount
     *
     * @param authRequest The authorization request
     * @param transaction The transaction to check
     * @return true if there's a mismatch, false otherwise
     */
    private boolean hasAmountMismatch(
                                      RequestAuthorizationRequestDto authRequest,
                                      TransactionActivatedEvent transaction
    ) {
        return !transactionsUtils.getTransactionTotalAmountFromEvent(transaction)
                .equals(authRequest.getAmount());
    }

    /**
     * Checks if there's a mismatch in the allCCP flag between the transaction and
     * the request
     *
     * @param authRequest               The authorization request
     * @param transactionActivatedEvent The transaction to check
     * @return true if there's a mismatch, false otherwise
     */
    private boolean hasAllCCPMismatch(
                                      RequestAuthorizationRequestDto authRequest,
                                      TransactionActivatedEvent transactionActivatedEvent
    ) {
        return !transactionsUtils.isAllCcp(transactionActivatedEvent, 0).equals(authRequest.getIsAllCCP());
    }

    /**
     * Creates an error for amount mismatch
     *
     * @param authRequest               The authorization request
     * @param transactionActivatedEvent The transaction
     * @return A Mono error with appropriate exception
     */
    private <T> Mono<T> createAmountMismatchError(
                                                  RequestAuthorizationRequestDto authRequest,
                                                  TransactionActivatedEvent transactionActivatedEvent
    ) {
        return Mono.error(
                new TransactionAmountMismatchException(
                        authRequest.getAmount(),
                        transactionsUtils.getTransactionTotalAmountFromEvent(transactionActivatedEvent)
                )
        );
    }

    /**
     * Creates an error for allCCP mismatch
     *
     * @param authRequest The authorization request
     * @param transaction The transaction
     * @return A Mono error with appropriate exception
     */
    private <T> Mono<T> createAllCCPMismatchError(
                                                  RequestAuthorizationRequestDto authRequest,
                                                  TransactionActivatedEvent transaction
    ) {
        return Mono.error(
                new PaymentNoticeAllCCPMismatchException(
                        transactionsUtils.getRptId(transaction, 0),
                        authRequest.getIsAllCCP(),
                        transactionsUtils.isAllCcp(transaction, 0)
                )
        );
    }

    /**
     * Retrieves the base transaction view, filtering by user ID if provided
     *
     * @param transactionId The ID of the transaction to retrieve
     * @param xUserId       The user ID to filter by, may be null
     * @return A Mono containing the transaction view if found
     */
    private Mono<BaseTransactionView> getBaseTransactionView(String transactionId, UUID xUserId) {
        return transactionsViewRepository.findById(transactionId)
                .filter(transactionDocument -> switch (transactionDocument) {
                    case it.pagopa.ecommerce.commons.documents.v1.Transaction ignored -> xUserId == null;
                    case Transaction t ->
                            xUserId == null ? t.getUserId() == null : t.getUserId().equals(xUserId.toString());
                    default ->
                            throw new NotImplementedException("Handling for transaction document version: [%s] not implemented yet".formatted(transactionDocument.getClass()));
                });
    }

    @Retry(name = "updateTransactionAuthorization")
    public Mono<TransactionInfoDto> updateTransactionAuthorization(
            UUID decodedTransactionId,
            UpdateAuthorizationRequestDto updateAuthorizationRequestDto
    ) {

        TransactionId transactionId = new TransactionId(decodedTransactionId);
        log.info("UpdateTransactionAuthorization decoded transaction id: [{}]", transactionId.value());

        Flux<BaseTransactionEvent<Object>> events = eventsRepository
                .findByTransactionIdOrderByCreationDateAsc(transactionId.value())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId.value())));

        Mono<ZonedDateTime> authorizationRequestedCreationDate = events
                .filter(
                        event -> event.getEventCode()
                                .equals(TransactionEventCode.TRANSACTION_AUTHORIZATION_REQUESTED_EVENT.toString())
                )
                .next()
                .map(authRequestedEvent -> ZonedDateTime.parse(authRequestedEvent.getCreationDate()))
                .switchIfEmpty(Mono.error(new AlreadyProcessedException(transactionId)));

        Mono<Tuple2<BaseTransaction, ZonedDateTime>> transactionV1 = transactionsUtils
                .reduceEvents(
                        events,
                        new EmptyTransaction(),
                        it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent,
                        it.pagopa.ecommerce.commons.domain.v1.Transaction.class
                )
                .filter(t -> !(t instanceof EmptyTransaction))
                .cast(BaseTransaction.class)
                .zipWith(authorizationRequestedCreationDate)
                .onErrorResume(ClassCastException.class, e -> Mono.empty());

        Mono<Tuple2<it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction, ZonedDateTime>> transactionV2 = transactionsUtils
                .reduceEvents(
                        events,
                        new it.pagopa.ecommerce.commons.domain.v2.EmptyTransaction(),
                        it.pagopa.ecommerce.commons.domain.v2.Transaction::applyEvent,
                        it.pagopa.ecommerce.commons.domain.v2.Transaction.class
                )
                .filter(t -> !(t instanceof it.pagopa.ecommerce.commons.domain.v2.EmptyTransaction))
                .cast(it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction.class)
                .zipWith(authorizationRequestedCreationDate)
                .onErrorResume(ClassCastException.class, e -> Mono.empty());

        Mono<Tuple4<String, String, Transaction.ClientId, Boolean>> txTracingDataV1 = transactionV1.map(Tuple2::getT1)
                .map(t -> Tuples.of(
                        transactionsUtils.getPspId(t).orElseThrow(),
                        transactionsUtils.getPaymentMethodTypeCode(t).orElseThrow(),
                        Transaction.ClientId.fromString(t.getClientId().name()),
                        transactionsUtils.isWalletPayment(t).orElseThrow())
                );

        Mono<Tuple4<String, String, Transaction.ClientId, Boolean>> txTracingDataV2 = transactionV2.map(Tuple2::getT1)
                .map(t -> Tuples.of(
                        transactionsUtils.getPspId(t).orElseThrow(),
                        transactionsUtils.getPaymentMethodTypeCode(t).orElseThrow(),
                        t.getClientId(),
                        transactionsUtils.isWalletPayment(t).orElseThrow())
                );

        Mono<Tuple4<String, String, Transaction.ClientId, Boolean>> txData = txTracingDataV2.switchIfEmpty(txTracingDataV1);

        Mono<Tuple2<UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger, UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdateContext>> authUpdateContext = txData
                .map(TupleUtils.function((pspId, paymentMethodTypeCode, clientId, isWalletPayment) -> switch (updateAuthorizationRequestDto.getOutcomeGateway()) {
                    case OutcomeNpgGatewayDto outcome -> Tuples.of(
                            UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.NPG,
                            new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdateContext(
                                    pspId,
                                    new UpdateTransactionStatusTracerUtils.GatewayOutcomeResult(
                                            outcome.getOperationResult().toString(),
                                            Optional.ofNullable(outcome.getErrorCode())
                                    ),
                                    paymentMethodTypeCode,
                                    clientId,
                                    isWalletPayment
                            )
                    );
                    case OutcomeRedirectGatewayDto outcome -> Tuples.of(
                            UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.REDIRECT,
                            new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdateContext(
                                    pspId,
                                    new UpdateTransactionStatusTracerUtils.GatewayOutcomeResult(
                                            outcome.getOutcome().toString(),
                                            Optional.ofNullable(outcome.getErrorCode())
                                    ),
                                    paymentMethodTypeCode,
                                    clientId,
                                    isWalletPayment
                            )
                    );
                    default ->
                            throw new InvalidRequestException("Input outcomeGateway not map to any trigger: [%s]".formatted(updateAuthorizationRequestDto.getOutcomeGateway()));
                }));

        Mono<TransactionInfoDto> v2Info = transactionV2
                .flatMap(
                        t -> this.updateTransactionAuthorizationStatusV2(
                                t.getT1(),
                                updateAuthorizationRequestDto,
                                t.getT2()
                        )
                );

        return v2Info
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId.value())))
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(
                        ignored -> authUpdateContext.subscribe(TupleUtils.consumer((trigger, updateContext) ->
                                updateTransactionStatusTracerUtils
                                        .traceStatusUpdateOperation(
                                                new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdate(
                                                        trigger,
                                                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.OK,
                                                        updateContext
                                                )
                                        )
                        ))
                )
                .onErrorResume(exception -> {
                    UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome outcome = exceptionToUpdateStatusOutcome(
                            exception
                    );

                    if (exception instanceof InvalidRequestException) {
                        return authUpdateContext.doOnNext(TupleUtils.consumer((trigger, _updateContext) ->
                                updateTransactionStatusTracerUtils.traceStatusUpdateOperation(
                                        new UpdateTransactionStatusTracerUtils.ErrorStatusTransactionUpdate(
                                                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusType.AUTHORIZATION_OUTCOME,
                                                trigger,
                                                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.INVALID_REQUEST
                                        )
                                )
                        )).then(Mono.error(exception));
                    } else {
                        return authUpdateContext.doOnNext(TupleUtils.consumer((trigger, updateContext) ->
                                updateTransactionStatusTracerUtils.traceStatusUpdateOperation(
                                        new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdate(
                                                trigger,
                                                outcome,
                                                updateContext
                                        )
                                )
                        )).then(Mono.error(exception));
                    }
                });
    }

    private Mono<TransactionInfoDto> updateTransactionAuthorizationStatusV2(
                                                                            it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction transaction,
                                                                            UpdateAuthorizationRequestDto updateAuthorizationRequestDto,
                                                                            ZonedDateTime authorizationRequestedTime
    ) {
        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction.getTransactionId(),
                transaction.getStatus().toString(),
                updateAuthorizationRequestDto,
                authorizationRequestedTime,
                Optional.of(transaction)
        );

        TransactionUpdateAuthorizationCommand transactionUpdateAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                updateAuthorizationStatusData
        );

        Mono<it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction> baseTransaction = Mono.just(transaction);
        return wasTransactionAuthorized(
                transaction.getTransactionId()
        ).<Either<TransactionInfoDto, Mono<it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction>>>flatMap(
                alreadyAuthorized -> {
                    if (Boolean.FALSE.equals(alreadyAuthorized)) {
                        return Mono.just(baseTransaction).map(Either::right);
                    } else {
                        return baseTransaction.map(
                                trx -> {
                                    log.info(
                                            "UpdateTransactionAuthorization outcome already received. Transaction status: [{}]",
                                            trx.getStatus()
                                    );
                                    return buildTransactionInfoDtoV2(trx);
                                }
                        ).map(Either::left);
                    }
                }
        )
                .flatMap(
                        either -> either.fold(
                                Mono::just,
                                tx -> baseTransaction
                                        .flatMap(
                                                t -> transactionUpdateAuthorizationHandlerV2
                                                        .handle(transactionUpdateAuthorizationCommand)
                                                        .doOnNext(
                                                                authorizationStatusUpdatedEvent -> log.info(
                                                                        "UpdateTransactionAuthorization requested for rptIds: {}",
                                                                        transactionUpdateAuthorizationCommand
                                                                                .getRptIds().stream().map(RptId::value)
                                                                                .toList()
                                                                )
                                                        )
                                                        .doOnError(
                                                                AlreadyProcessedException.class,
                                                                exception -> log.error(
                                                                        "UpdateTransactionAuthorization Error: requesting authorization update for transaction in state [{}]",
                                                                        t.getStatus()
                                                                )
                                                        )
                                                        .cast(
                                                                TransactionAuthorizationCompletedEvent.class
                                                        )
                                                        .flatMap(
                                                                authorizationUpdateProjectionHandlerV2::handle
                                                        )
                                        )

                                        .cast(
                                                BaseTransactionWithPaymentToken.class
                                        )
                                        .flatMap(
                                                this::closePaymentV2
                                        )
                                        .map(this::buildTransactionInfoDtoV2)
                        )
                );
    }

    private Mono<Transaction> closePaymentV2(
                                             BaseTransactionWithPaymentToken transaction
    ) {

        TransactionClosureRequestCommand transactionClosureRequestCommand = new TransactionClosureRequestCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                transaction.getTransactionId()
        );

        return transactionSendClosureRequestHandler
                .handle(transactionClosureRequestCommand)
                .doOnNext(
                        closureSentRequestedEvent -> log.info(
                                "Requested async transaction closure for rptIds: {}",
                                transactionClosureRequestCommand.getRptIds().stream().map(RptId::value).toList()
                        )
                )
                .flatMap(
                        closureRequestedEvent -> closureRequestedProjectionHandler.handle(
                                (TransactionClosureRequestedEvent) closureRequestedEvent

                        )
                );
    }

    private TransactionInfoDto buildTransactionInfoDtoV2(
                                                         Transaction transactionDocument
    ) {
        return new TransactionInfoDto()
                .transactionId(
                        transactionDocument
                                .getTransactionId()
                )
                .payments(
                        transactionDocument
                                .getPaymentNotices()
                                .stream()
                                .map(
                                        paymentNotice -> new PaymentInfoDto()
                                                .amount(paymentNotice.getAmount())
                                                .reason(paymentNotice.getDescription())
                                                .paymentToken(paymentNotice.getPaymentToken())
                                                .rptId(paymentNotice.getRptId())
                                )
                                .toList()
                )
                .status(transactionsUtils.convertEnumerationV1(transactionDocument.getStatus()));

    }

    private TransactionInfoDto buildTransactionInfoDtoV2(
                                                         it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction baseTransaction
    ) {
        return new TransactionInfoDto()
                .transactionId(baseTransaction.getTransactionId().value())
                .payments(
                        baseTransaction.getPaymentNotices()
                                .stream().map(
                                        paymentNotice -> new PaymentInfoDto()
                                                .amount(paymentNotice.transactionAmount().value())
                                                .reason(paymentNotice.transactionDescription().value())
                                                .paymentToken(paymentNotice.paymentToken().value())
                                                .rptId(paymentNotice.rptId().value())
                                ).toList()
                )
                .status(transactionsUtils.convertEnumerationV1(baseTransaction.getStatus()));

    }

    private Mono<Boolean> wasTransactionAuthorized(
                                                   TransactionId transactionId
    ) {
        /*
         * @formatter:off
         *
         * This method determines whether transaction has been previously authorized or not
         * by searching for an authorization completed event.
         * The check is performed directly on the presence of an authorization completed event
         * and not on the fact that the transaction aggregate is an instance of `BaseTransactionWithCompletedAuthorization`
         * because a generic transaction can go in the REFUNDED or EXPIRED states without undergoing authorization
         * (the corresponding aggregates do not extend, in fact, `BaseTransactionWithCompletedAuthorization`).
         *
         * This can happen, for example, when a transaction expires before getting a payment gateway response
         * (for the EXPIRED state; if in REFUNDED that means the transaction was already refunded).
         *
         * @formatter:on
         */
        return eventsRepository
                .findByTransactionIdAndEventCode(
                        transactionId.value(),
                        TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT.toString()
                )
                .map(v -> true)
                .switchIfEmpty(Mono.just(false));

    }

    @Retry(name = "addUserReceipt")
    public Mono<TransactionInfoDto> addUserReceipt(
            String transactionId,
            AddUserReceiptRequestDto addUserReceiptRequest
    ) {
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .flatMap(
                        transactionView -> switch (transactionView) {
                            case Transaction t -> transactionRequestUserReceiptHandlerV2
                                    .handle(new TransactionAddUserReceiptCommand(
                                            t.getPaymentNotices().stream().map(p -> new RptId(p.getRptId())).toList(),
                                            new AddUserReceiptData(
                                                    new TransactionId(transactionId),
                                                    addUserReceiptRequest
                                            )
                                    ))
                                    .doOnNext(
                                            transactionUserReceiptRequestedEvent -> log.info(
                                                    "AddUserReceipt [{}] for transactionId: [{}]",
                                                    TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT,
                                                    transactionUserReceiptRequestedEvent.getTransactionId()
                                            )
                                    )
                                    .flatMap(event -> transactionUserReceiptProjectionHandlerV2
                                            .handle((TransactionUserReceiptRequestedEvent) event))
                                    .doOnNext(
                                            transaction -> log.info(
                                                    "AddUserReceipt transaction status updated [{}] for transactionId: [{}]",
                                                    transaction.getStatus(),
                                                    transaction.getTransactionId()
                                            )
                                    )
                                    .map(this::buildTransactionInfoDtoV2);
                            default ->
                                    Mono.error(new BadGatewayException("Error while processing request unexpected transaction version type", HttpStatus.BAD_GATEWAY));
                        }
                );


    }

    private Mono<NewTransactionResponseDto> projectActivatedEventV2(
                                                                    TransactionActivatedEvent transactionActivatedEvent,
                                                                    String authToken
    ) {
        return transactionsActivationProjectionHandlerV2
                .handle(transactionActivatedEvent)
                .map(
                        transaction -> new NewTransactionResponseDto()
                                .transactionId(transaction.getTransactionId().value())
                                .payments(
                                        transaction.getPaymentNotices().stream().map(
                                                paymentNotice -> new PaymentInfoDto()
                                                        .amount(paymentNotice.transactionAmount().value())
                                                        .reason(paymentNotice.transactionDescription().value())
                                                        .rptId(paymentNotice.rptId().value())
                                                        .paymentToken(paymentNotice.paymentToken().value())
                                                        .isAllCCP(paymentNotice.isAllCCP())
                                                        .transferList(
                                                                paymentNotice.transferList().stream().map(
                                                                        paymentTransferInfo -> new TransferDto()
                                                                                .digitalStamp(
                                                                                        paymentTransferInfo
                                                                                                .digitalStamp()
                                                                                )
                                                                                .paFiscalCode(
                                                                                        paymentTransferInfo
                                                                                                .paFiscalCode()
                                                                                )
                                                                                .transferAmount(
                                                                                        paymentTransferInfo
                                                                                                .transferAmount()
                                                                                )
                                                                                .transferCategory(
                                                                                        paymentTransferInfo
                                                                                                .transferCategory()
                                                                                )
                                                                ).toList()
                                                        )
                                        ).toList()
                                )
                                .authToken(authToken)
                                .status(transactionsUtils.convertEnumerationV1(transaction.getStatus()))
                                // .feeTotal()//TODO da dove prendere le fees?
                                .clientId(convertClientId(transaction.getClientId()))
                                .idCart(transaction.getTransactionActivatedData().getIdCart())
                );
    }

    public NewTransactionResponseDto.ClientIdEnum convertClientId(
                                                                  it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId clientId
    ) {
        return Optional.ofNullable(clientId)
                .map(
                        value -> {
                            try {
                                return NewTransactionResponseDto.ClientIdEnum.fromValue(value.name());
                            } catch (IllegalArgumentException e) {
                                log.error("Unknown input origin ", e);
                                throw new InvalidRequestException("Unknown input origin", e);
                            }
                        }
                ).orElseThrow(() -> new InvalidRequestException("Null value as input origin"));
    }

    public NewTransactionResponseDto.ClientIdEnum convertClientId(
                                                                  Transaction.ClientId clientId
    ) {
        return Optional.ofNullable(clientId)
                .map(
                        value -> {
                            try {
                                return NewTransactionResponseDto.ClientIdEnum
                                        .fromValue(value.getEffectiveClient().name());
                            } catch (IllegalArgumentException e) {
                                log.error("Unknown input origin ", e);
                                throw new InvalidRequestException("Unknown input origin", e);
                            }
                        }
                ).orElseThrow(() -> new InvalidRequestException("Null value as input origin"));
    }

    private Mono<PaymentSessionData> retrieveInformationFromAuthorizationRequest(RequestAuthorizationRequestDto requestAuthorizationRequestDto, String clientId) {
        return switch (requestAuthorizationRequestDto.getDetails()) {
            case CardsAuthRequestDetailsDto cards ->
                    ecommercePaymentMethodsClient.retrieveCardData(requestAuthorizationRequestDto.getPaymentInstrumentId(), cards.getOrderId()).map(response -> new PaymentSessionData(response.getBin(), response.getSessionId(), response.getBrand(), null));
            case WalletAuthRequestDetailsDto wallet -> walletClient
                    .getWalletInfo(wallet.getWalletId())
                    .map(walletAuthDataDto -> {
                        String bin = null;
                        if (walletAuthDataDto.getPaymentMethodData() instanceof WalletAuthCardDataDto cardsData) {
                            bin = cardsData.getBin();
                        }
                        return new PaymentSessionData(
                                bin,
                                null,
                                walletAuthDataDto.getBrand(),
                                walletAuthDataDto.getContractId());
                    });
            case ApmAuthRequestDetailsDto ignore ->
                    ecommercePaymentMethodsClient.getPaymentMethod(requestAuthorizationRequestDto.getPaymentInstrumentId(), clientId).map(response -> new PaymentSessionData(null, null, response.getName(), null));
            case RedirectionAuthRequestDetailsDto ignored -> Mono.just(new PaymentSessionData(
                    null,
                    null,
                    "N/A",//TODO handle this value for Nodo close payment
                    null
            ));
            default -> Mono.just(new PaymentSessionData(null, null, null, null));
        };
    }

    /**
     * This method maps input throwable to proper {@link UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome} enumeration
     *
     * @param throwable the caught throwable
     * @return the mapped outcome to be traced
     */
    public static UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome exceptionToUpdateStatusOutcome(Throwable throwable) {
        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome outcome = switch (throwable) {
            case AlreadyProcessedException ignored ->
                    UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.WRONG_TRANSACTION_STATUS;
            case TransactionNotFoundException ignored ->
                    UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.TRANSACTION_NOT_FOUND;
            case InvalidRequestException ignored ->
                    UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.INVALID_REQUEST;
            default -> UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.PROCESSING_ERROR;
        };
        log.error("Exception processing request. [{}] mapped to [{}]", throwable, outcome);
        return outcome;
    }
}
