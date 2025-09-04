package it.pagopa.transactions.controllers.v1;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import it.pagopa.ecommerce.commons.annotations.Warmup;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.ReactiveExclusiveLockDocumentWrapper;
import it.pagopa.ecommerce.commons.repositories.ExclusiveLockDocument;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;
import it.pagopa.generated.transactions.server.api.TransactionsApi;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.mdcutilities.TransactionTracingUtils;
import it.pagopa.transactions.services.v1.TransactionsService;
import it.pagopa.transactions.utils.SpanLabelOpenTelemetry;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.UUIDUtils;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import jakarta.validation.ConstraintViolationException;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import reactor.util.context.Context;

@RestController("TransactionsControllerV1")
@Slf4j
public class TransactionsController implements TransactionsApi {

    @Autowired
    private TransactionsService transactionsService;

    @Autowired
    private TransactionsUtils transactionsUtils;

    @Autowired
    private UUIDUtils uuidUtils;

    @Autowired
    private UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils;

    @Autowired
    private OpenTelemetryUtils openTelemetryUtils;

    @Autowired
    private ReactiveExclusiveLockDocumentWrapper reactiveExclusiveLockDocumentWrapper;

    @Value("${security.apiKey.primary}")
    private String primaryKey;

    @ExceptionHandler(
        {
                CallNotPermittedException.class
        }
    )
    public Mono<ResponseEntity<ProblemJsonDto>> openStateHandler(CallNotPermittedException error) {
        log.error("Error - OPEN circuit breaker", error);
        return Mono.just(
                new ResponseEntity<>(
                        new ProblemJsonDto()
                                .status(502)
                                .title("Bad Gateway")
                                .detail("Upstream service temporary unavailable. Open circuit breaker."),
                        HttpStatus.BAD_GATEWAY
                )
        ).doOnNext(
                ignored -> openTelemetryUtils.addErrorSpanWithException(
                        SpanLabelOpenTelemetry.CIRCUIT_BREAKER_OPEN_SPAN_NAME
                                .formatted(error.getCausingCircuitBreakerName()),
                        error
                )
        );
    }

    @Override
    public Mono<ResponseEntity<NewTransactionResponseDto>> newTransaction(
                                                                          ClientIdDto xClientId,
                                                                          Mono<NewTransactionRequestDto> newTransactionRequest,
                                                                          ServerWebExchange exchange
    ) {
        TransactionId transactionId = new TransactionId(UUID.randomUUID());
        return newTransactionRequest
                .flatMap(ntr -> transactionsService.newTransaction(ntr, xClientId, transactionId))
                .map(ResponseEntity::ok)
                .contextWrite(
                        context -> TransactionTracingUtils.setTransactionInfoIntoReactorContext(
                                new TransactionTracingUtils.TransactionInfo(
                                        transactionId,
                                        new HashSet<>(),
                                        exchange.getRequest().getMethod().name(),
                                        exchange.getRequest().getURI().getPath()
                                ),
                                context
                        )
                );
    }

    @Override
    public Mono<ResponseEntity<TransactionInfoDto>> getTransactionInfo(
                                                                       String transactionId,
                                                                       UUID xUserId,
                                                                       ServerWebExchange exchange
    ) {
        return transactionsService.getTransactionInfo(transactionId, xUserId)
                .doOnNext(t -> log.info("GetTransactionInfo for transactionId completed: [{}]", transactionId))
                .map(ResponseEntity::ok)
                .contextWrite(
                        context -> TransactionTracingUtils.setTransactionInfoIntoReactorContext(
                                new TransactionTracingUtils.TransactionInfo(
                                        new TransactionId(transactionId),
                                        new HashSet<>(),
                                        exchange.getRequest().getMethod().name(),
                                        exchange.getRequest().getURI().getPath()
                                ),
                                context
                        )
                );
    }

    @Override
    public Mono<ResponseEntity<RequestAuthorizationResponseDto>> requestTransactionAuthorization(
                                                                                                 String transactionId,
                                                                                                 Mono<RequestAuthorizationRequestDto> requestAuthorizationRequestDto,
                                                                                                 UUID xUserId,
                                                                                                 String xPgsId,
                                                                                                 String lang,
                                                                                                 ServerWebExchange exchange
    ) {
        return requestAuthorizationRequestDto
                .doOnNext(logTransactionRequestFor(transactionId))
                .flatMap(request -> authorizeTransaction(transactionId, xUserId, xPgsId, lang, request))
                .map(ResponseEntity::ok)
                .contextWrite(context -> addTransactionContext(context, transactionId, exchange));
    }

    /**
     * Creates a consumer that logs a transaction request for a specific transaction
     * ID.
     *
     * @param transactionId The ID of the transaction being requested
     * @return A consumer that logs the transaction request
     */
    private Consumer<RequestAuthorizationRequestDto> logTransactionRequestFor(String transactionId) {
        return request -> logTransactionRequest(transactionId);
    }

    private void logTransactionRequest(String transactionId) {
        log.info("RequestTransactionAuthorization for transactionId: [{}]", transactionId);
    }

    /**
     * Creates a function that authorizes a transaction with the specified
     * parameters.
     *
     * @param transactionId The ID of the transaction to authorize
     * @param xUserId       The user ID for the authorization
     * @param xPgsId        The payment gateway ID
     * @param lang          The language code
     * @return A function that processes the authorization request
     */
    private Mono<RequestAuthorizationResponseDto> authorizeTransaction(
                                                                       String transactionId,
                                                                       UUID xUserId,
                                                                       String xPgsId,
                                                                       String lang,
                                                                       RequestAuthorizationRequestDto request
    ) {
        return transactionsService.requestTransactionAuthorization(
                transactionId,
                xUserId,
                xPgsId,
                lang,
                request
        );
    }

    /**
     * Creates a function that adds transaction context to a reactor context.
     *
     * @param transactionId The ID of the transaction
     * @param exchange      The server web exchange
     * @return A function that adds transaction context
     */
    private Context addTransactionContext(
                                          Context context,
                                          String transactionId,
                                          ServerWebExchange exchange
    ) {
        TransactionTracingUtils.TransactionInfo transactionInfo = createTransactionInfo(transactionId, exchange);
        return TransactionTracingUtils.setTransactionInfoIntoReactorContext(transactionInfo, context);
    }

    private TransactionTracingUtils.TransactionInfo createTransactionInfo(
                                                                          String transactionId,
                                                                          ServerWebExchange exchange
    ) {
        return new TransactionTracingUtils.TransactionInfo(
                new TransactionId(transactionId),
                new HashSet<>(),
                exchange.getRequest().getMethod().name(),
                exchange.getRequest().getURI().getPath()
        );
    }

    @Override
    public Mono<ResponseEntity<TransactionInfoDto>> updateTransactionAuthorization(
                                                                                   String base64TransactionId,
                                                                                   Mono<UpdateAuthorizationRequestDto> updateAuthorizationRequestDto,
                                                                                   ServerWebExchange exchange
    ) {
        return uuidUtils.uuidFromBase64(base64TransactionId).fold(
                Mono::error,
                transactionIdDecoded -> {
                    log.info(
                            "UpdateTransactionAuthorization for transactionId: [{}], decoded transaction id: [{}]",
                            base64TransactionId,
                            transactionIdDecoded
                    );
                    return updateAuthorizationRequestDto.flatMap(
                            updateAuthorizationRequest -> handleUpdateAuthorizationRequest(
                                    new TransactionId(transactionIdDecoded),
                                    updateAuthorizationRequest,
                                    exchange
                            )
                    )
                            .map(ResponseEntity::ok);
                }
        );
    }

    public Mono<TransactionInfoDto> handleUpdateAuthorizationRequest(
            TransactionId domainTransactionId,
            UpdateAuthorizationRequestDto updateAuthorizationRequestDto,
            ServerWebExchange exchange
    ) {
        return Mono.defer(() -> {
            ExclusiveLockDocument lockDocument = new ExclusiveLockDocument(
                    "PATCH-auth-request-%s".formatted(domainTransactionId.value()),
                    "transactions-service"
            );

            return reactiveExclusiveLockDocumentWrapper.saveIfAbsent(lockDocument)
                    .flatMap(lockAcquired -> {
                        log.info(
                                "UpdateTransactionAuthorization lock acquired for transactionId: [{}] with key: [{}]: [{}]",
                                domainTransactionId.value(),
                                lockDocument.id(),
                                lockAcquired
                        );
                        if (!lockAcquired) {
                            return Mono.error(new LockNotAcquiredException(domainTransactionId, lockDocument));
                        }

                        return transactionsService.updateTransactionAuthorization(
                                domainTransactionId.uuid(),
                                updateAuthorizationRequestDto
                        );
                    })
                    .contextWrite(ctx ->
                            TransactionTracingUtils.setTransactionInfoIntoReactorContext(
                                    new TransactionTracingUtils.TransactionInfo(
                                            domainTransactionId,
                                            new HashSet<>(),
                                            exchange.getRequest().getMethod().name(),
                                            exchange.getRequest().getURI().getPath()
                                    ),
                                    ctx
                            )
                    );
        });
    }


    @Override
    public Mono<ResponseEntity<AddUserReceiptResponseDto>> addUserReceipt(
                                                                          String transactionId,
                                                                          Mono<AddUserReceiptRequestDto> addUserReceiptRequestDto,
                                                                          ServerWebExchange exchange
    ) {
        return addUserReceiptRequestDto
                .doOnNext(t -> log.info("AddUserReceipt for transactionId: [{}]", transactionId))
                .flatMap(
                        addUserReceiptRequest -> transactionsService
                                .addUserReceipt(transactionId, addUserReceiptRequest)
                                .map(
                                        _v -> new AddUserReceiptResponseDto()
                                                .outcome(AddUserReceiptResponseDto.OutcomeEnum.OK)
                                )
                                .doOnError(exception -> {
                                    SendPaymentResultOutcomeInfo outcomeInfo = exceptionToUpdateSendPaymentResultOutcomeInfo(
                                            exception
                                    );
                                    switch (outcomeInfo.outcome()) {
                                        case INVALID_REQUEST, WRONG_TRANSACTION_STATUS -> updateTransactionStatusTracerUtils
                                                .traceStatusUpdateOperation(
                                                        new UpdateTransactionStatusTracerUtils.SendPaymentResultNodoStatusUpdate(
                                                                outcomeInfo.outcome(),
                                                                outcomeInfo.pspId().get(),
                                                                outcomeInfo.paymentTypeCode().get(),
                                                                outcomeInfo.clientId().get(),
                                                                outcomeInfo.walletPayment().get(),
                                                                outcomeInfo.gatewayOutcomeResult().get()
                                                        )
                                                );
                                        case PROCESSING_ERROR, TRANSACTION_NOT_FOUND -> updateTransactionStatusTracerUtils
                                                .traceStatusUpdateOperation(
                                                        new UpdateTransactionStatusTracerUtils.ErrorStatusTransactionUpdate(
                                                                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusType.SEND_PAYMENT_RESULT_OUTCOME,
                                                                UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.NODO,
                                                                outcomeInfo.outcome()
                                                        )
                                                );
                                    }

                                    log.error("Got error while trying to add user receipt", exception);
                                })
                                .onErrorMap(SendPaymentResultException::new)
                )
                .map(ResponseEntity::ok)
                .contextWrite(
                        context -> TransactionTracingUtils.setTransactionInfoIntoReactorContext(
                                new TransactionTracingUtils.TransactionInfo(
                                        new TransactionId(transactionId),
                                        new HashSet<>(),
                                        exchange.getRequest().getMethod().name(),
                                        exchange.getRequest().getURI().getPath()
                                ),
                                context
                        )
                );
    }

    /**
     * This method maps input throwable to proper {@link UpdateTransactionStatusTracerUtils.SendPaymentResultNodoStatusUpdate} operation outcome record
     *
     * @param throwable the caught throwable
     * @return the mapped outcome to be traced
     */
    private SendPaymentResultOutcomeInfo exceptionToUpdateSendPaymentResultOutcomeInfo(Throwable throwable) {
        SendPaymentResultOutcomeInfo outcomeInfo = switch (throwable) {
            case AlreadyProcessedException exception -> new SendPaymentResultOutcomeInfo(
                    UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.WRONG_TRANSACTION_STATUS,
                    Optional.of(exception.getTransactionId()),
                    exception.pspId(),
                    exception.paymentTypeCode(),
                    exception.clientId().map(Transaction.ClientId::valueOf),
                    exception.walletPayment(),
                    exception.gatewayOutcomeResult()
            );
            case TransactionNotFoundException ignored -> new SendPaymentResultOutcomeInfo(
                    UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.TRANSACTION_NOT_FOUND,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()
            );
            case InvalidRequestException exception -> new SendPaymentResultOutcomeInfo(
                    UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.INVALID_REQUEST,
                    Optional.of(exception.getTransactionId()),
                    exception.pspId(),
                    exception.paymentTypeCode(),
                    exception.clientId().map(Transaction.ClientId::valueOf),
                    exception.walletPayment(),
                    exception.gatewayOutcomeResult()
            );
            default -> new SendPaymentResultOutcomeInfo(
                    UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.PROCESSING_ERROR,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()
            );
        };
        log.error("Exception processing request. [{}] mapped to [{}]", throwable, outcomeInfo);
        return outcomeInfo;
    }

    @Override
    public Mono<ResponseEntity<Void>> requestTransactionUserCancellation(
                                                                         String transactionId,
                                                                         UUID xUserId,
                                                                         ServerWebExchange exchange
    ) {
        return transactionsService.cancelTransaction(transactionId, xUserId)
                .contextWrite(
                        context -> TransactionTracingUtils.setTransactionInfoIntoReactorContext(
                                new TransactionTracingUtils.TransactionInfo(
                                        new TransactionId(transactionId),
                                        new HashSet<>(),
                                        exchange.getRequest().getMethod().name(),
                                        exchange.getRequest().getURI().getPath()
                                ),
                                context
                        )
                )
                .thenReturn(ResponseEntity.accepted().build());
    }

    @Override
    public Mono<ResponseEntity<TransactionOutcomeInfoDto>> getTransactionOutcomes(
                                                                                  String transactionId,
                                                                                  UUID xUserId,
                                                                                  ServerWebExchange exchange
    ) {
        return transactionsService.getTransactionOutcome(transactionId, xUserId)
                .doOnNext(t -> log.info("Get TransactionOutcomeInfo for transactionId completed: [{}]", transactionId))
                .map(ResponseEntity::ok)
                .contextWrite(
                        context -> TransactionTracingUtils.setTransactionInfoIntoReactorContext(
                                new TransactionTracingUtils.TransactionInfo(
                                        new TransactionId(transactionId),
                                        new HashSet<>(),
                                        exchange.getRequest().getMethod().name(),
                                        exchange.getRequest().getURI().getPath()
                                ),
                                context
                        )
                );
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    ResponseEntity<ProblemJsonDto> transactionNotFoundHandler(TransactionNotFoundException exception) {
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(404)
                        .title("Transaction not found")
                        .detail("Transaction for payment token '%s' not found".formatted(exception.getPaymentToken())),
                HttpStatus.NOT_FOUND
        );
    }

    @ExceptionHandler(UnsatisfiablePspRequestException.class)
    ResponseEntity<ProblemJsonDto> unsatisfiablePspRequestHandler(UnsatisfiablePspRequestException exception) {
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(409)
                        .title("Cannot find a PSP with the requested parameters")
                        .detail(
                                "Cannot find a PSP with fee %d and language %s for transaction with payment token '%s'"
                                        .formatted(
                                                exception.getRequestedFee() / 100,
                                                exception.getLanguage(),
                                                exception.getPaymentToken().value()
                                        )
                        ),
                HttpStatus.CONFLICT
        );
    }

    @ExceptionHandler(AlreadyProcessedException.class)
    ResponseEntity<ProblemJsonDto> alreadyProcessedHandler(AlreadyProcessedException exception) {
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(409)
                        .title("Transaction already processed")
                        .detail(
                                "Transaction with id '%s' has been already processed"
                                        .formatted(exception.getTransactionId().value())
                        ),
                HttpStatus.CONFLICT
        );
    }

    @ExceptionHandler(BadGatewayException.class)
    ResponseEntity<ProblemJsonDto> badGatewayHandler(BadGatewayException exception) {
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(502)
                        .title("Bad gateway")
                        .detail(exception.getDetail()),
                HttpStatus.BAD_GATEWAY
        );
    }

    @ExceptionHandler(NotImplementedException.class)
    ResponseEntity<ProblemJsonDto> notImplemented(NotImplementedException exception) {
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(501)
                        .title("Not implemented")
                        .detail(exception.getMessage()),
                HttpStatus.NOT_IMPLEMENTED
        );
    }

    @ExceptionHandler(GatewayTimeoutException.class)
    ResponseEntity<ProblemJsonDto> gatewayTimeoutHandler(GatewayTimeoutException exception) {
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(504)
                        .title("Gateway timeout")
                        .detail(null),
                HttpStatus.GATEWAY_TIMEOUT
        );
    }

    private void traceInvalidRequestException(ServerHttpRequest request) {
        String contextPath = request.getPath().value();
        UpdateTransactionStatusTracerUtils.StatusUpdateInfo errorStatusUpdateInfo = null;
        if (contextPath.endsWith("auth-requests")) {
            /*
             * for PATCH auth-requests gateway information is passed into
             * `x-payment-gateway-type` header, for POST auth-requests into `x-pgs-id`
             */
            Optional<String> gatewayTypeHeader = Optional.ofNullable(request.getHeaders().get("x-payment-gateway-type"))
                    .filter(Predicate.not(List::isEmpty)).map(headers -> headers.get(0));
            Optional<String> pgsIdHeader = Optional.ofNullable(request.getHeaders().get("x-pgs-id"))
                    .filter(Predicate.not(List::isEmpty)).map(headers -> headers.get(0));
            String gatewayHeader = gatewayTypeHeader.orElse(pgsIdHeader.orElse(""));
            UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger trigger = switch (gatewayHeader) {
                case "NPG" -> UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.NPG;
                case "REDIRECT" -> UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.REDIRECT;
                default -> UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.UNKNOWN;
            };

            if (Objects.equals(request.getMethod(), HttpMethod.PATCH)) {
                errorStatusUpdateInfo = new UpdateTransactionStatusTracerUtils.ErrorStatusTransactionUpdate(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusType.AUTHORIZATION_OUTCOME,
                        trigger,
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.INVALID_REQUEST
                );
            } else if (Objects.equals(request.getMethod(), HttpMethod.POST)) {
                errorStatusUpdateInfo = new UpdateTransactionStatusTracerUtils.ErrorStatusTransactionUpdate(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusType.AUTHORIZATION_REQUESTED,
                        trigger,
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.INVALID_REQUEST
                );
            }
        } else if (contextPath.endsWith("user-receipts")) {
            errorStatusUpdateInfo = new UpdateTransactionStatusTracerUtils.ErrorStatusTransactionUpdate(
                    UpdateTransactionStatusTracerUtils.UpdateTransactionStatusType.SEND_PAYMENT_RESULT_OUTCOME,
                    UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.NODO,
                    UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.INVALID_REQUEST
            );
        }
        if (errorStatusUpdateInfo != null) {
            updateTransactionStatusTracerUtils.traceStatusUpdateOperation(errorStatusUpdateInfo);
        }
    }

    @ExceptionHandler(WebExchangeBindException.class)
    ResponseEntity<ProblemJsonDto> validationExceptionHandler(
                                                              WebExchangeBindException exception,
                                                              ServerWebExchange exchange
    ) {
        traceInvalidRequestException(exchange.getRequest());
        String errorMessage = exception.getAllErrors().stream().map(ObjectError::toString)
                .collect(Collectors.joining(", "));

        log.warn("Got invalid input: {}", errorMessage);

        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(400)
                        .title("Bad request")
                        .detail("Invalid request: %s".formatted(errorMessage)),
                HttpStatus.BAD_REQUEST
        );
    }

    @ExceptionHandler(SendPaymentResultException.class)
    ResponseEntity<ProblemJsonDto> sendPaymentResultExceptionHandler(SendPaymentResultException exception) {
        log.warn("Got error during sendPaymentResult", exception);

        ProblemJsonDto responseBody = switch (exception.cause) {
            case TransactionNotFoundException e -> new ProblemJsonDto()
                    .status(404)
                    .title("Transaction not found")
                    .detail(e.getMessage());
            case AlreadyProcessedException e -> new ProblemJsonDto()
                    .status(422)
                    .title("Operation conflict")
                    .detail(e.getMessage());
            case BadGatewayException e -> new ProblemJsonDto()
                    .status(422)
                    .title("Bad gateway")
                    .detail(e.getMessage());
            default -> new ProblemJsonDto()
                    .status(422)
                    .title("Unprocessable entity")
                    .detail(exception.cause.getMessage());
        };

        return new ResponseEntity<>(
                responseBody,
                HttpStatus.valueOf(responseBody.getStatus())
        );
    }

    @ExceptionHandler(
        {
                InvalidRequestException.class,
                ConstraintViolationException.class,
                ServerWebInputException.class
        }
    )
    ResponseEntity<ProblemJsonDto> validationExceptionHandler(
                                                              Exception exception,
                                                              ServerWebExchange exchange
    ) {
        log.warn("Got invalid input: {}", exception.getMessage());
        traceInvalidRequestException(exchange.getRequest());
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(400)
                        .title("Bad request")
                        .detail("Invalid request: %s".formatted(exception.getMessage())),
                HttpStatus.BAD_REQUEST
        );
    }

    @ExceptionHandler(PaymentMethodNotFoundException.class)
    ResponseEntity<ProblemJsonDto> paymentMethodNotFoundException(PaymentMethodNotFoundException exception) {
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(404)
                        .title("Payment method not found")
                        .detail(exception.getMessage()),
                HttpStatus.NOT_FOUND
        );
    }

    @ExceptionHandler(TransactionAmountMismatchException.class)
    ResponseEntity<ProblemJsonDto> amountMismatchErrorHandler(
                                                              TransactionAmountMismatchException exception,
                                                              ServerWebExchange exchange
    ) {

        log.warn(
                "Got invalid input: {}. Request amount: [{}], transaction amount: [{}]",
                exception.getMessage(),
                exception.getRequestAmount(),
                exception.getTransactionAmount()
        );
        traceInvalidRequestException(exchange.getRequest());
        HttpStatus httpStatus = HttpStatus.CONFLICT;
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(httpStatus.value())
                        .title(httpStatus.getReasonPhrase())
                        .detail("Invalid request: %s".formatted(exception.getMessage())),
                httpStatus
        );
    }

    @ExceptionHandler(PaymentNoticeAllCCPMismatchException.class)
    ResponseEntity<ProblemJsonDto> paymentNoticeAllCCPMismatchErrorHandler(
                                                                           PaymentNoticeAllCCPMismatchException exception,
                                                                           ServerWebExchange exchange
    ) {
        log.warn(
                "Got invalid input: {}. RptID: [{}] request allCCP: [{}], payment notice allCCP: [{}]",
                exception.getMessage(),
                exception.getRptId(),
                exception.getRequestAllCCP(),
                exception.getPaymentNoticeAllCCP()
        );
        traceInvalidRequestException(exchange.getRequest());
        HttpStatus httpStatus = HttpStatus.CONFLICT;
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(httpStatus.value())
                        .title(httpStatus.getReasonPhrase())
                        .detail("Invalid request: %s".formatted(exception.getMessage())),
                httpStatus
        );
    }

    @ExceptionHandler(
        {
                JwtIssuerResponseException.class
        }
    )
    ResponseEntity<ProblemJsonDto> jwtTokenGenerationError(JwtIssuerResponseException exception) {
        log.warn(exception.getMessage());
        HttpStatus httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(httpStatus.value())
                        .title(httpStatus.getReasonPhrase())
                        .detail("Internal server error: cannot generate JWT token"),
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }

    @ExceptionHandler({
            NodoErrorException.class,
    })
    ResponseEntity<?> nodoErrorHandler(NodoErrorException exception) {

        return switch (exception.getFaultCode()) {
            case String s when Arrays.stream(PartyConfigurationFaultDto.values()).anyMatch(z -> z.getValue().equals(s)) ->
                    new ResponseEntity<>(
                            new PartyConfigurationFaultPaymentProblemJsonDto()
                                    .title("EC error")
                                    .faultCodeCategory(FaultCategoryDto.PAYMENT_UNAVAILABLE)
                                    .faultCodeDetail(PartyConfigurationFaultDto.fromValue(s)), HttpStatus.BAD_GATEWAY);
            case String s when Arrays.stream(ValidationFaultDto.values()).anyMatch(z -> z.getValue().equals(s)) ->
                    new ResponseEntity<>(
                            new ValidationFaultPaymentProblemJsonDto()
                                    .title("Validation Fault")
                                    .faultCodeCategory(FaultCategoryDto.PAYMENT_UNKNOWN)
                                    .faultCodeDetail(ValidationFaultDto.fromValue(s)), HttpStatus.NOT_FOUND);
            case String s when Arrays.stream(GatewayFaultDto.values()).anyMatch(z -> z.getValue().equals(s)) ->
                    new ResponseEntity<>(
                            new GatewayFaultPaymentProblemJsonDto()
                                    .title("Payment unavailable")
                                    .faultCodeCategory(FaultCategoryDto.GENERIC_ERROR)
                                    .faultCodeDetail(GatewayFaultDto.fromValue(s)), HttpStatus.BAD_GATEWAY);
            case String s when Arrays.stream(PartyTimeoutFaultDto.values()).anyMatch(z -> z.getValue().equals(s)) ->
                    new ResponseEntity<>(
                            new PartyTimeoutFaultPaymentProblemJsonDto()
                                    .title("Gateway Timeout")
                                    .faultCodeCategory(FaultCategoryDto.GENERIC_ERROR)
                                    .faultCodeDetail(PartyTimeoutFaultDto.fromValue(s)), HttpStatus.GATEWAY_TIMEOUT);
            case String s when Arrays.stream(PaymentStatusFaultDto.values()).anyMatch(z -> z.getValue().equals(s)) ->
                    new ResponseEntity<>(
                            new PaymentStatusFaultPaymentProblemJsonDto()
                                    .title("Payment Status Fault")
                                    .faultCodeCategory(FaultCategoryDto.PAYMENT_UNAVAILABLE)
                                    .faultCodeDetail(PaymentStatusFaultDto.fromValue(s)), HttpStatus.CONFLICT);
            default -> new ResponseEntity<>(
                    new ProblemJsonDto().title("Bad gateway"), HttpStatus.BAD_GATEWAY);
        };
    }

    @ExceptionHandler(
        {
                InvalidNodoResponseException.class,
        }
    )
    ResponseEntity<ProblemJsonDto> invalidNodoResponse(InvalidNodoResponseException exception) {
        log.warn(exception.getMessage());
        HttpStatus httpStatus = HttpStatus.BAD_GATEWAY;
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(httpStatus.value())
                        .title(httpStatus.getReasonPhrase())
                        .detail(exception.getErrorDescription()),
                httpStatus
        );
    }

    @ExceptionHandler(NpgNotRetryableErrorException.class)
    ResponseEntity<ProblemJsonDto> npgNotRetryableErrorException(NpgNotRetryableErrorException exception) {
        log.warn(exception.getMessage());
        HttpStatus httpStatus = HttpStatus.UNPROCESSABLE_ENTITY;
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(httpStatus.value())
                        .title(httpStatus.getReasonPhrase())
                        .detail(exception.getDetail()),
                httpStatus
        );
    }

    @ExceptionHandler(LockNotAcquiredException.class)
    ResponseEntity<ProblemJsonDto> lockNotAcquiredExceptionHandler(LockNotAcquiredException exception) {
        HttpStatus httpStatus = HttpStatus.UNPROCESSABLE_ENTITY;
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(httpStatus.value())
                        .title("Error acquiring lock to perform operation")
                        .detail(exception.getMessage()),
                httpStatus
        );
    }

    @Warmup
    public void postNewTransactionWarmupMethod() {
        IntStream.range(0, 3).forEach(
                idx -> {
                    log.info("Performing warmup iteration: {}", idx);
                    NewTransactionResponseDto newTransactionResponseDto = WebClient
                            .create()
                            .post()
                            .uri("http://localhost:8080/transactions")
                            .header("X-Client-Id", TransactionInfoDto.ClientIdEnum.CHECKOUT.toString())
                            .header("x-api-key", primaryKey)
                            .bodyValue(transactionsUtils.buildWarmupRequestV1())
                            .retrieve()
                            .bodyToMono(NewTransactionResponseDto.class)
                            .block(Duration.ofSeconds(30));
                    WebClient
                            .create()
                            .get()
                            .uri(
                                    "http://localhost:8080/transactions/{transactionId}",
                                    newTransactionResponseDto.getTransactionId()
                            ).header("x-api-key", primaryKey)
                            .retrieve()
                            .toBodilessEntity()
                            .block(Duration.ofSeconds(30));
                }
        );
    }
}
