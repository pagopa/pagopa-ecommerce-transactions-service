package it.pagopa.transactions.controllers.v1;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import it.pagopa.ecommerce.commons.annotations.Warmup;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.exceptions.JWTTokenGenerationException;
import it.pagopa.generated.transactions.server.api.TransactionsApi;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.mdcutilities.TransactionTracingUtils;
import it.pagopa.transactions.services.v1.TransactionsService;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.UUIDUtils;
import it.pagopa.transactions.utils.UpdateTransactionStatusTracerUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import javax.validation.ConstraintViolationException;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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

    @ExceptionHandler(
        {
                CallNotPermittedException.class
        }
    )
    public Mono<ResponseEntity<ProblemJsonDto>> openStateHandler() {
        log.error("Error - OPEN circuit breaker");
        return Mono.just(
                new ResponseEntity<>(
                        new ProblemJsonDto()
                                .status(502)
                                .title("Bad Gateway")
                                .detail("Upstream service temporary unavailable. Open circuit breaker."),
                        HttpStatus.BAD_GATEWAY
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
                .flatMap(ntr -> {
                    log.info(
                            "newTransaction rptIDs {} ",
                            String.join(
                                    ",",
                                    ntr.getPaymentNotices().stream().map(PaymentNoticeInfoDto::getRptId).toList()
                            )

                    );
                    return transactionsService.newTransaction(ntr, xClientId, transactionId);
                })
                .map(ResponseEntity::ok)
                .contextWrite(
                        context -> TransactionTracingUtils.setTransactionInfoIntoReactorContext(
                                new TransactionTracingUtils.TransactionInfo(
                                        transactionId,
                                        new HashSet<>(),
                                        exchange.getRequest().getMethodValue(),
                                        exchange.getRequest().getURI().getPath()
                                ),
                                context
                        )
                );
    }

    @Override
    public Mono<ResponseEntity<TransactionInfoDto>> getTransactionInfo(
                                                                       String transactionId,
                                                                       ServerWebExchange exchange
    ) {
        return transactionsService.getTransactionInfo(transactionId)
                .doOnNext(t -> log.info("getTransactionInfo for transactionId: {} ", transactionId))
                .map(ResponseEntity::ok)
                .contextWrite(
                        context -> TransactionTracingUtils.setTransactionInfoIntoReactorContext(
                                new TransactionTracingUtils.TransactionInfo(
                                        new TransactionId(transactionId),
                                        new HashSet<>(),
                                        exchange.getRequest().getMethodValue(),
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
                                                                                                 String xPgsId,
                                                                                                 ServerWebExchange exchange
    ) {
        return requestAuthorizationRequestDto
                .doOnNext(t -> log.info("requestTransactionAuthorization for transactionId: {} ", transactionId))
                .flatMap(
                        requestAuthorizationRequest -> transactionsService
                                .requestTransactionAuthorization(transactionId, xPgsId, requestAuthorizationRequest)
                )
                .map(ResponseEntity::ok)
                .contextWrite(
                        context -> TransactionTracingUtils.setTransactionInfoIntoReactorContext(
                                new TransactionTracingUtils.TransactionInfo(
                                        new TransactionId(transactionId),
                                        new HashSet<>(),
                                        exchange.getRequest().getMethodValue(),
                                        exchange.getRequest().getURI().getPath()
                                ),
                                context
                        )
                );
    }

    @Override
    public Mono<ResponseEntity<TransactionInfoDto>> updateTransactionAuthorization(
            String transactionId,
            Mono<UpdateAuthorizationRequestDto> updateAuthorizationRequestDto,
            ServerWebExchange exchange
    ) {
        return uuidUtils.uuidFromBase64(transactionId).fold(
                Mono::error,
                transactionIdDecoded -> updateAuthorizationRequestDto
                        .doOnNext(
                                t -> log.info(
                                        "updateTransactionAuthorization for transactionId: {}, decoded transaction id: {} ",
                                        transactionId,
                                        transactionIdDecoded
                                )
                        )
                        .flatMap(
                                updateAuthorizationRequest -> {
                                    Tuple2<UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger, Optional<String>> authDetails = switch (updateAuthorizationRequest.getOutcomeGateway()) {
                                        case OutcomeXpayGatewayDto ignored ->
                                                Tuples.of(UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.PGS_XPAY, Optional.empty());
                                        case OutcomeVposGatewayDto ignored ->
                                                Tuples.of(UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.PGS_VPOS, Optional.empty());
                                        case OutcomeNpgGatewayDto ignored ->
                                                Tuples.of(UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.NPG, Optional.empty());
                                        case OutcomeRedirectGatewayDto outcomeRedirectGatewayDto ->
                                                Tuples.of(UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.REDIRECT, Optional.of(outcomeRedirectGatewayDto.getPspId()));
                                        default ->
                                                throw new InvalidRequestException("Input outcomeGateway not map to any trigger: [%s]".formatted(updateAuthorizationRequest.getOutcomeGateway()));
                                    };
                                    return transactionsService
                                            .updateTransactionAuthorization(
                                                    transactionIdDecoded,
                                                    updateAuthorizationRequest
                                            )
                                            .doOnNext(
                                                    ignored -> updateTransactionStatusTracerUtils
                                                            .traceStatusUpdateOperation(
                                                                    new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdate(
                                                                            UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.OK,
                                                                            authDetails.getT1(),
                                                                            authDetails.getT2()
                                                                    )
                                                            )
                                            )
                                            .doOnError(exception -> {
                                                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome outcome = exceptionToUpdateStatusOutcome(
                                                        exception
                                                );
                                                updateTransactionStatusTracerUtils.traceStatusUpdateOperation(
                                                        new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdate(
                                                                outcome,
                                                                authDetails.getT1(),
                                                                authDetails.getT2()
                                                        )
                                                );
                                            });
                                }
                        )
                        .map(ResponseEntity::ok)
                        .contextWrite(
                                context -> TransactionTracingUtils.setTransactionInfoIntoReactorContext(
                                        new TransactionTracingUtils.TransactionInfo(
                                                new TransactionId(transactionIdDecoded),
                                                new HashSet<>(),
                                                exchange.getRequest().getMethodValue(),
                                                exchange.getRequest().getURI().getPath()                                      ),
                                        context
                                )
                        )
        );
    }

    @Override
    public Mono<ResponseEntity<AddUserReceiptResponseDto>> addUserReceipt(
                                                                          String transactionId,
                                                                          Mono<AddUserReceiptRequestDto> addUserReceiptRequestDto,
                                                                          ServerWebExchange exchange
    ) {
        return addUserReceiptRequestDto
                .doOnNext(t -> log.info("addUserReceipt for transactionId: {} ", transactionId))
                .flatMap(
                        addUserReceiptRequest -> transactionsService
                                .addUserReceipt(transactionId, addUserReceiptRequest)
                                .map(
                                        _v -> new AddUserReceiptResponseDto()
                                                .outcome(AddUserReceiptResponseDto.OutcomeEnum.OK)
                                )
                                .doOnNext(
                                        ignored -> updateTransactionStatusTracerUtils.traceStatusUpdateOperation(
                                                new UpdateTransactionStatusTracerUtils.NodoStatusUpdate(
                                                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.OK
                                                )
                                        )
                                )
                                .doOnError(exception -> {
                                    UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome outcome = exceptionToUpdateStatusOutcome(
                                            exception
                                    );
                                    updateTransactionStatusTracerUtils.traceStatusUpdateOperation(
                                            new UpdateTransactionStatusTracerUtils.NodoStatusUpdate(outcome)
                                    );
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
                                        exchange.getRequest().getMethodValue(),
                                        exchange.getRequest().getURI().getPath()
                                ),
                                context
                        )
                );
    }

    /**
     * This method maps input throwable to proper {@link UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome} enumeration
     *
     * @param throwable the caught throwable
     * @return the mapped outcome to be traced
     */
    private UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome exceptionToUpdateStatusOutcome(Throwable throwable) {
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

    @Override
    public Mono<ResponseEntity<Void>> requestTransactionUserCancellation(
                                                                         String transactionId,
                                                                         ServerWebExchange exchange
    ) {
        return transactionsService.cancelTransaction(transactionId)
                .contextWrite(
                        context -> TransactionTracingUtils.setTransactionInfoIntoReactorContext(
                                new TransactionTracingUtils.TransactionInfo(
                                        new TransactionId(transactionId),
                                        new HashSet<>(),
                                        exchange.getRequest().getMethodValue(),
                                        exchange.getRequest().getURI().getPath()
                                ),
                                context
                        )
                )
                .thenReturn(ResponseEntity.accepted().build());
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
        UpdateTransactionStatusTracerUtils.StatusUpdateInfo statusUpdateInfo = null;
        if (contextPath.endsWith("auth-requests")) {
            String gatewayHeader = Optional.ofNullable(request.getHeaders().get("x-payment-gateway-type"))
                    .filter(Predicate.not(List::isEmpty))
                    .map(headers -> headers.get(0))
                    .orElse("");
            UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger trigger = switch (gatewayHeader) {
                case "XPAY" -> UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.PGS_XPAY;
                case "VPOS" -> UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.PGS_VPOS;
                case "NPG" -> UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.NPG;
                case "REDIRECT" -> UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.REDIRECT;
                default -> UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.UNKNOWN;
            };
            statusUpdateInfo = new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdate(
                    UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.INVALID_REQUEST,
                    trigger,
                    Optional.empty()
            );
        } else if (contextPath.endsWith("user-receipts")) {
            statusUpdateInfo = new UpdateTransactionStatusTracerUtils.NodoStatusUpdate(
                    UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.INVALID_REQUEST
            );
        }
        if (statusUpdateInfo != null) {
            updateTransactionStatusTracerUtils.traceStatusUpdateOperation(statusUpdateInfo);
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

    @ExceptionHandler(TransactionAmountMismatchException.class)
    ResponseEntity<ProblemJsonDto> amountMismatchErrorHandler(TransactionAmountMismatchException exception) {
        log.warn(
                "Got invalid input: {}. Request amount: [{}], transaction amount: [{}]",
                exception.getMessage(),
                exception.getRequestAmount(),
                exception.getTransactionAmount()
        );
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
                                                                           PaymentNoticeAllCCPMismatchException exception
    ) {
        log.warn(
                "Got invalid input: {}. RptID: [{}] request allCCP: [{}], payment notice allCCP: [{}]",
                exception.getMessage(),
                exception.getRptId(),
                exception.getRequestAllCCP(),
                exception.getPaymentNoticeAllCCP()
        );
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
                JWTTokenGenerationException.class
        }
    )
    ResponseEntity<ProblemJsonDto> jwtTokenGenerationError(JWTTokenGenerationException exception) {
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
            case String s && Arrays.stream(PartyConfigurationFaultDto.values()).anyMatch(z -> z.getValue().equals(s)) ->
                    new ResponseEntity<>(
                            new PartyConfigurationFaultPaymentProblemJsonDto()
                                    .title("EC error")
                                    .faultCodeCategory(FaultCategoryDto.PAYMENT_UNAVAILABLE)
                                    .faultCodeDetail(PartyConfigurationFaultDto.fromValue(s)), HttpStatus.BAD_GATEWAY);
            case String s && Arrays.stream(ValidationFaultDto.values()).anyMatch(z -> z.getValue().equals(s)) ->
                    new ResponseEntity<>(
                            new ValidationFaultPaymentProblemJsonDto()
                                    .title("Validation Fault")
                                    .faultCodeCategory(FaultCategoryDto.PAYMENT_UNKNOWN)
                                    .faultCodeDetail(ValidationFaultDto.fromValue(s)), HttpStatus.NOT_FOUND);
            case String s && Arrays.stream(GatewayFaultDto.values()).anyMatch(z -> z.getValue().equals(s)) ->
                    new ResponseEntity<>(
                            new GatewayFaultPaymentProblemJsonDto()
                                    .title("Payment unavailable")
                                    .faultCodeCategory(FaultCategoryDto.GENERIC_ERROR)
                                    .faultCodeDetail(GatewayFaultDto.fromValue(s)), HttpStatus.BAD_GATEWAY);
            case String s && Arrays.stream(PartyTimeoutFaultDto.values()).anyMatch(z -> z.getValue().equals(s)) ->
                    new ResponseEntity<>(
                            new PartyTimeoutFaultPaymentProblemJsonDto()
                                    .title("Gateway Timeout")
                                    .faultCodeCategory(FaultCategoryDto.GENERIC_ERROR)
                                    .faultCodeDetail(PartyTimeoutFaultDto.fromValue(s)), HttpStatus.GATEWAY_TIMEOUT);
            case String s && Arrays.stream(PaymentStatusFaultDto.values()).anyMatch(z -> z.getValue().equals(s)) ->
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

    @Warmup
    public void postNewTransactionWarmupMethod() {
        WebClient
                .create()
                .post()
                .uri("http://localhost:8080/transactions")
                .header("X-Client-Id", TransactionInfoDto.ClientIdEnum.CHECKOUT.toString())
                .bodyValue(transactionsUtils.buildWarmupRequestV1())
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(30));

    }

}
