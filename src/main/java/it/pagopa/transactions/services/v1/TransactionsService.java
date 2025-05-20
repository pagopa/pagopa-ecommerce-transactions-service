package it.pagopa.transactions.services.v1;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.BaseTransactionView;
import it.pagopa.ecommerce.commons.documents.PaymentTransferInformation;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.domain.v2.*;
import it.pagopa.ecommerce.commons.documents.v2.*;
import it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction;
import it.pagopa.ecommerce.commons.domain.v1.TransactionEventCode;
import it.pagopa.ecommerce.commons.redis.templatewrappers.v2.PaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransactionWithPaymentToken;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
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
import it.pagopa.transactions.utils.*;
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
import java.util.stream.Collectors;

import static it.pagopa.generated.transactions.v2.server.model.OutcomeNpgGatewayDto.OperationResultEnum.*;

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
                                .totalAmount(outcome == TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_0  ? transaction.getPaymentNotices().stream().mapToInt(it.pagopa.ecommerce.commons.documents.PaymentNotice::getAmount).sum() : null)
                                .fees(outcome == TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_0  ?  + Optional.ofNullable(transaction.getFeeTotal()).orElse(0) : null)
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
                    evaluateClosePaymentResultError(closureErrorData): //Authorized
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
                            TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1: //Authorized
                            evaluateUnauthorizedStatus(
                                    paymentGateway,
                                    gatewayAuthorizationStatus,
                                    authorizationErrorCode); //Not authorized
                }
                case CLOSURE_REQUESTED -> {
                    return wasAuthorizedByGateway(paymentGateway, gatewayAuthorizationStatus) ?
                            TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_17:
                            evaluateUnauthorizedStatus(
                                    paymentGateway,
                                    gatewayAuthorizationStatus,
                                    authorizationErrorCode);
                }
                case UNAUTHORIZED -> {
                    return wasAuthorizedByGateway(paymentGateway, gatewayAuthorizationStatus) ?
                            TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25:
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
    public Mono<Void> cancelTransaction(String transactionId, UUID xUserId) {
        return getBaseTransactionView(transactionId, xUserId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .flatMap(
                        transactionDocument -> {
                            TransactionUserCancelCommand transactionCancelCommand = new TransactionUserCancelCommand(
                                    null,
                                    new TransactionId(transactionId)
                            );

                            return switch (transactionDocument) {
                                case Transaction t ->
                                        transactionCancelHandlerV2
                                                .handle(transactionCancelCommand).flatMap(event -> cancellationRequestProjectionHandlerV2
                                                        .handle((TransactionUserCanceledEvent) event));
                                default ->
                                        Mono.error(new BadGatewayException("Error while processing request unexpected transaction version type", HttpStatus.BAD_GATEWAY));
                            };
                        }
                )
                .then();

    }

    @Retry(name = "requestTransactionAuthorization")
    public Mono<RequestAuthorizationResponseDto> requestTransactionAuthorization(
            String transactionId,
            UUID xUserId,
            String paymentGatewayId,
            String lang,
            RequestAuthorizationRequestDto requestAuthorizationRequestDto
    ) {
        return getBaseTransactionView(transactionId, xUserId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .flatMap(transaction -> validateTransactionDetails(transaction, requestAuthorizationRequestDto))
                .flatMap(transaction -> processAuthRequest(transaction, requestAuthorizationRequestDto))
                .flatMap(args -> executeAuthPipeline(args, lang, requestAuthorizationRequestDto, paymentGatewayId));
    }

    private Mono<RequestAuthorizationResponseDto> executeAuthPipeline(
            Tuple2<BaseTransactionView, AuthorizationRequestSessionData> args,
            String lang,
            RequestAuthorizationRequestDto requestAuthRequestDto,
            String paymentGatewayId
    ) {
        BaseTransactionView transactionDocument = args.getT1();
        AuthorizationRequestSessionData authorizationRequestSessionData = args.getT2();

        log.info("Requesting authorization for transactionId: {}", transactionDocument.getTransactionId());

        AuthorizationRequestData authData = createAuthRequestData(
                transactionDocument, authorizationRequestSessionData, requestAuthRequestDto, paymentGatewayId);

        TransactionRequestAuthorizationCommand transactionRequestAuthCommand = new TransactionRequestAuthorizationCommand(
                transactionsUtils.getRptIds(transactionDocument).stream().map(RptId::new).toList(),
                lang,
                authData
        );

        return executeHandlerBasedOnTransactionType(transactionDocument, transactionRequestAuthCommand, authData)
                .doOnSuccess(response -> invalidatePaymentRequestCache(transactionDocument));
    }

    private AuthorizationRequestData createAuthRequestData(
            BaseTransactionView transactionDocument,
            AuthorizationRequestSessionData authSessionData,
            RequestAuthorizationRequestDto requestAuthRequestDto,
            String paymentGatewayId) {

        BundleDto bundle = authSessionData.bundle().orElseThrow();
        String paymentMethodName = authSessionData.paymentMethodName();
        String paymentMethodDescription = authSessionData.paymentMethodDescription();
        Optional<String> sessionId = authSessionData.npgSessionId();
        String brand = authSessionData.brand();
        Optional<String> contractId = authSessionData.npgContractId();
        String asset = authSessionData.asset();
        Map<String, String> brandAssets = authSessionData.brandAssets();

        return new AuthorizationRequestData(
                new TransactionId(transactionDocument.getTransactionId()),
                mapTransactionDocToV2PaymentNoticeList(transactionDocument),
                transactionsUtils.getEmail(transactionDocument),
                requestAuthRequestDto.getFee(),
                requestAuthRequestDto.getPaymentInstrumentId(),
                requestAuthRequestDto.getPspId(),
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
                requestAuthRequestDto.getDetails(),
                asset,
                Optional.ofNullable(brandAssets),
                bundle.getIdBundle()
        );
    }

    private Mono<RequestAuthorizationResponseDto> executeHandlerBasedOnTransactionType(
            BaseTransactionView transactionDocument,
            TransactionRequestAuthorizationCommand transactionRequestAuthCommand,
            AuthorizationRequestData authData) {

        if (transactionDocument instanceof Transaction) {
            return requestAuthHandlerV2
                    .handle(transactionRequestAuthCommand)
                    .doOnNext(res -> logAuthRequested(transactionDocument.getTransactionId()))
                    .flatMap(res -> authorizationProjectionHandlerV2
                            .handle(authData)
                            .thenReturn(res));
        } else {
            return Mono.error(new NotImplementedException(
                    "Handling for transaction document: [%s] not implemented yet".formatted(transactionDocument.getClass())
            ));
        }
    }

    private void logAuthRequested(String transactionId) {
        log.info("Requested authorization for transaction: {}", transactionId);
    }

    private void invalidatePaymentRequestCache(BaseTransactionView transactionDocument) {
        transactionsUtils.getPaymentNotices(transactionDocument).forEach(this::invalidateRptIdCache);
    }

    private void invalidateRptIdCache(it.pagopa.ecommerce.commons.documents.PaymentNotice paymentNotice) {
        log.info("Invalidate cache for RptId : {}", paymentNotice.getRptId());
        paymentRequestInfoRedisTemplateWrapper.deleteById(paymentNotice.getRptId());
    }

    private List<PaymentNotice> mapTransactionDocToV2PaymentNoticeList(BaseTransactionView transactionDocument) {
        return transactionsUtils.getPaymentNotices(transactionDocument).stream()
                .map(this::mapV1PaymentNoticeToV2)
                .toList();
    }

    private PaymentNotice mapV1PaymentNoticeToV2(it.pagopa.ecommerce.commons.documents.PaymentNotice paymentNotice) {
        return new PaymentNotice(
                new PaymentToken(paymentNotice.getPaymentToken()),
                new RptId(paymentNotice.getRptId()),
                new TransactionAmount(paymentNotice.getAmount()),
                new TransactionDescription(paymentNotice.getDescription()),
                new PaymentContextCode(paymentNotice.getPaymentContextCode()),
                mapV1PaymentTransferInfoListToV2(paymentNotice.getTransferList()),
                paymentNotice.isAllCCP(),
                new CompanyName(paymentNotice.getCompanyName()),
                paymentNotice.getCreditorReferenceId()
        );
    }

    private List<PaymentTransferInfo> mapV1PaymentTransferInfoListToV2(List<PaymentTransferInformation> transferList) {
        return transferList.stream()
                .map(this::mapV1PaymentTransferInfoToV2)
                .toList();
    }

    private PaymentTransferInfo mapV1PaymentTransferInfoToV2(PaymentTransferInformation transferInfoV1) {
        return new PaymentTransferInfo(
                transferInfoV1.getPaFiscalCode(),
                transferInfoV1.getDigitalStamp(),
                transferInfoV1.getTransferAmount(),
                transferInfoV1.getTransferCategory()
        );
    }

    private Mono<Tuple2<BaseTransactionView, AuthorizationRequestSessionData>> processAuthRequest(
                                                                                                  BaseTransactionView transaction,
                                                                                                  RequestAuthorizationRequestDto requestAuthorizationRequestDto
    ) {
        log.info("Authorization psp validation for transactionId: {}", transaction.getTransactionId());

        String clientId = transactionsUtils.getClientId(transaction);

        return retrieveInformationFromAuthorizationRequest(requestAuthorizationRequestDto, clientId)
                .flatMap(
                        paymentSessionData -> calculateTransactionFee(
                                transaction,
                                requestAuthorizationRequestDto,
                                paymentSessionData
                        )
                )
                .map(data -> createAuthSessionData(requestAuthorizationRequestDto, data))
                .filter(authSessionData -> authSessionData.bundle().isPresent())
                .switchIfEmpty(
                        createUnsatisfiablePspRequestError(
                                transaction.getTransactionId(),
                                requestAuthorizationRequestDto
                        )
                )
                .map(authSessionData -> Tuples.of(transaction, authSessionData));
    }

    private Mono<Tuple2<CalculateFeeResponseDto, PaymentSessionData>> calculateTransactionFee(
                                                                                              BaseTransactionView transaction,
                                                                                              RequestAuthorizationRequestDto requestAuthorizationRequestDto,
                                                                                              PaymentSessionData paymentSessionData
    ) {

        List<it.pagopa.ecommerce.commons.documents.PaymentNotice> paymentNotices = transactionsUtils
                .getPaymentNotices(transaction);

        return ecommercePaymentMethodsClient
                .calculateFee(
                        requestAuthorizationRequestDto.getPaymentInstrumentId(),
                        transaction.getTransactionId(),
                        createCalculateFeeRequest(
                                transaction,
                                requestAuthorizationRequestDto,
                                paymentSessionData,
                                paymentNotices
                        ),
                        Integer.MAX_VALUE
                )
                .map(calculateFeeResponseDto -> Tuples.of(calculateFeeResponseDto, paymentSessionData));
    }

    private CalculateFeeRequestDto createCalculateFeeRequest(
                                                             BaseTransactionView transaction,
                                                             RequestAuthorizationRequestDto requestAuthRequestDto,
                                                             PaymentSessionData paymentSessionData,
                                                             List<it.pagopa.ecommerce.commons.documents.PaymentNotice> paymentNotices
    ) {
        return new CalculateFeeRequestDto()
                .touchpoint(transactionsUtils.getEffectiveClientId(transaction))
                .bin(paymentSessionData.cardBin())
                .idPspList(List.of(requestAuthRequestDto.getPspId()))
                .paymentNotices(mapV1PaymentNoticesToV2(paymentNotices))
                .isAllCCP(transactionsUtils.isAllCcp(transaction, 0));
    }

    private List<PaymentNoticeDto> mapV1PaymentNoticesToV2(List<it.pagopa.ecommerce.commons.documents.PaymentNotice> paymentNotices) {
        return paymentNotices
                .stream()
                .map(this::convertPaymentNoticeToDto)
                .toList();
    }

    private PaymentNoticeDto convertPaymentNoticeToDto(it.pagopa.ecommerce.commons.documents.PaymentNotice paymentNotice) {
        return new PaymentNoticeDto()
                .paymentAmount(paymentNotice.getAmount().longValue())
                .primaryCreditorInstitution(paymentNotice.getRptId().substring(0, 11))
                .transferList(mapV1TransferInfoListToV2TransferListItemDtoList(paymentNotice.getTransferList()));
    }

    private List<TransferListItemDto> mapV1TransferInfoListToV2TransferListItemDtoList(List<PaymentTransferInformation> transferList) {
        return transferList
                .stream()
                .map(this::mapV1TransferInfoToV2TransferListItemDto)
                .toList();
    }

    private TransferListItemDto mapV1TransferInfoToV2TransferListItemDto(PaymentTransferInformation transferInfo) {
        return new TransferListItemDto()
                .creditorInstitution(transferInfo.getPaFiscalCode())
                .digitalStamp(transferInfo.getDigitalStamp())
                .transferCategory(transferInfo.getTransferCategory());
    }

    private AuthorizationRequestSessionData createAuthSessionData(
                                                                  RequestAuthorizationRequestDto requestAuthRequestDto,
                                                                  Tuple2<CalculateFeeResponseDto, PaymentSessionData> data
    ) {

        CalculateFeeResponseDto calculateFeeResponse = data.getT1();
        PaymentSessionData paymentSessionData = data.getT2();

        return new AuthorizationRequestSessionData(
                calculateFeeResponse.getPaymentMethodName(),
                calculateFeeResponse.getPaymentMethodDescription(),
                findMatchingBundle(calculateFeeResponse, requestAuthRequestDto),
                paymentSessionData.brand(),
                Optional.ofNullable(paymentSessionData.sessionId()),
                Optional.ofNullable(paymentSessionData.contractId()),
                calculateFeeResponse.getAsset(),
                calculateFeeResponse.getBrandAssets()
        );
    }

    private Optional<BundleDto> findMatchingBundle(
                                                   CalculateFeeResponseDto calculateFeeResponse,
                                                   RequestAuthorizationRequestDto requestAuthRequestDto
    ) {
        return calculateFeeResponse.getBundles().stream()
                .filter(psp -> isMatchingPspAndFee(psp, requestAuthRequestDto)).findFirst();
    }

    private boolean isMatchingPspAndFee(
                                        BundleDto psp,
                                        RequestAuthorizationRequestDto requestAuthRequestDto
    ) {
        return requestAuthRequestDto.getPspId().equals(psp.getIdPsp())
                && Long.valueOf(requestAuthRequestDto.getFee()).equals(psp.getTaxPayerFee());
    }

    private <T> Mono<T> createUnsatisfiablePspRequestError(
                                                           String transactionId,
                                                           RequestAuthorizationRequestDto requestAuthRequestDto
    ) {
        return Mono.error(
                new UnsatisfiablePspRequestException(
                        new PaymentToken(transactionId),
                        requestAuthRequestDto.getLanguage(),
                        requestAuthRequestDto.getFee()
                )
        );
    }

    private Mono<BaseTransactionView> validateTransactionDetails(
                                                                 BaseTransactionView transaction,
                                                                 RequestAuthorizationRequestDto requestAuthorizationRequestDto
    ) {
        String transactionId = transaction.getTransactionId();
        Integer amountTotal = transactionsUtils.getTransactionTotalAmount(transaction);
        Boolean isAllCCP = transactionsUtils.isAllCcp(transaction, 0);

        log.info("Authorization request amount validation for transactionId: {}", transactionId);

        boolean amountMismatch = !amountTotal.equals(requestAuthorizationRequestDto.getAmount());
        boolean allCCPMismatch = !isAllCCP.equals(requestAuthorizationRequestDto.getIsAllCCP());

        if (amountMismatch) {
            return Mono.error(
                    new TransactionAmountMismatchException(
                            requestAuthorizationRequestDto.getAmount(),
                            amountTotal
                    )
            );
        } else if (allCCPMismatch) {
            return Mono.error(
                    new PaymentNoticeAllCCPMismatchException(
                            transactionsUtils.getRptId(transaction, 0),
                            requestAuthorizationRequestDto.getIsAllCCP(),
                            isAllCCP
                    )
            );
        }
        return Mono.just(transaction);
    }

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
