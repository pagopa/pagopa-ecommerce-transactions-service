package it.pagopa.transactions.controllers.v2;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import it.pagopa.ecommerce.commons.annotations.Warmup;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.exceptions.JWTTokenGenerationException;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.generated.transactions.v2.server.api.V2Api;
import it.pagopa.generated.transactions.v2.server.model.*;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.mdcutilities.TransactionTracingUtils;
import it.pagopa.transactions.services.v2.TransactionsService;
import it.pagopa.transactions.utils.SpanLabelOpenTelemetry;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import javax.validation.ConstraintViolationException;
import java.time.Duration;
import java.util.HashSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static it.pagopa.transactions.utils.TransactionsUtils.nodeErrorToV2TransactionsResponseEntityMapping;

@RestController("TransactionsControllerV2")
@Slf4j
public class TransactionsController implements V2Api {

    @Autowired
    private TransactionsService transactionsService;

    @Autowired
    private TransactionsUtils transactionsUtils;

    @Autowired
    private OpenTelemetryUtils openTelemetryUtils;

    @Autowired
    private it.pagopa.transactions.controllers.v1.TransactionsController transactionsControllerV1;

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
                                        exchange.getRequest().getMethodValue(),
                                        exchange.getRequest().getURI().getPath()
                                ),
                                context
                        )
                );
    }

    @Override
    public Mono<ResponseEntity<NewTransactionResponseDto>> newTransaction(
                                                                          ClientIdDto xClientId,
                                                                          UUID correlationId,
                                                                          Mono<NewTransactionRequestDto> newTransactionRequest,
                                                                          UUID xUserId,
                                                                          ServerWebExchange exchange
    ) {
        TransactionId transactionId = new TransactionId(UUID.randomUUID());
        return newTransactionRequest
                .flatMap(
                        ntr -> transactionsService.newTransaction(ntr, xClientId, correlationId, transactionId, xUserId)
                )
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
    public Mono<ResponseEntity<UpdateAuthorizationResponseDto>> updateTransactionAuthorization(
                                                                                               String transactionId,
                                                                                               Mono<UpdateAuthorizationRequestDto> updateAuthorizationRequestDto,
                                                                                               ServerWebExchange exchange
    ) {
        /*
         * v1 and v2 api version are the same, same input same logic -> same processing
         * the mainly diff here is that the input transaction id is not base64 encoded
         * in the v2 version. this fact is reflected in the below code too were, except
         * for the input transaction id handling, there are no differences between v1
         * and v2 versions, making v2 request be processed by v1 handler. Once migrated
         * b.e. to v2 version the v1 can be deleted altogether (no one will call this
         * api) and move all the logic from v1 service to the v2 referring only the v2
         * api version beans
         */
        return updateAuthorizationRequestDto
                .map(this::mapUpdateAuthRequestV2ToV1)
                .flatMap(
                        updateAuthorizationRequest -> transactionsControllerV1.handleUpdateAuthorizationRequest(
                                new TransactionId(transactionId),
                                updateAuthorizationRequest,
                                exchange
                        )
                )
                .map(
                        transactionInfo -> new UpdateAuthorizationResponseDto()
                                .status(TransactionStatusDto.fromValue(transactionInfo.getStatus().getValue()))
                )
                .map(ResponseEntity::ok);
    }

    private it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto mapUpdateAuthRequestV2ToV1(UpdateAuthorizationRequestDto updateAuthorizationRequestDto) {
        UpdateAuthorizationRequestOutcomeGatewayDto requestOutcomeGateway = updateAuthorizationRequestDto.getOutcomeGateway();
        it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestOutcomeGatewayDto convertedOutcomeGateway = switch (requestOutcomeGateway) {
            case OutcomeNpgGatewayDto o -> new it.pagopa.generated.transactions.server.model.OutcomeNpgGatewayDto()
                    .operationResult(it.pagopa.generated.transactions.server.model.OutcomeNpgGatewayDto.OperationResultEnum.valueOf(o.getOperationResult().toString()))
                    .orderId(o.getOrderId())
                    .operationId(o.getOperationId())
                    .authorizationCode(o.getAuthorizationCode())
                    .errorCode(o.getErrorCode())
                    .paymentEndToEndId(o.getPaymentEndToEndId())
                    .rrn(o.getRrn())
                    .validationServiceId(o.getValidationServiceId());
            case OutcomeRedirectGatewayDto o ->
                    new it.pagopa.generated.transactions.server.model.OutcomeRedirectGatewayDto()
                            .paymentGatewayType(o.getPaymentGatewayType())
                            .pspTransactionId(o.getPspTransactionId())
                            .outcome(it.pagopa.generated.transactions.server.model.AuthorizationOutcomeDto.valueOf(o.getOutcome().toString()))
                            .pspId(o.getPspId())
                            .authorizationCode(o.getAuthorizationCode())
                            .errorCode(o.getErrorCode());
            default ->
                    throw new NotImplementedException("Conversion from outcome gateway [%s] not implemented".formatted(requestOutcomeGateway.getPaymentGatewayType()));
        };
        return new it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto()
                .timestampOperation(updateAuthorizationRequestDto.getTimestampOperation())
                .outcomeGateway(convertedOutcomeGateway);
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

    @ExceptionHandler(WebExchangeBindException.class)
    ResponseEntity<ProblemJsonDto> validationExceptionHandler(WebExchangeBindException exception) {
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

    @ExceptionHandler(
        {
                InvalidRequestException.class,
                ConstraintViolationException.class,
                ServerWebInputException.class,
                MethodArgumentTypeMismatchException.class
        }
    )
    ResponseEntity<ProblemJsonDto> validationExceptionHandler(Exception exception) {
        log.warn("Got invalid input: {}", exception.getMessage());
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(400)
                        .title("Bad request")
                        .detail("Invalid request: %s".formatted(exception.getMessage())),
                HttpStatus.BAD_REQUEST
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

    @ExceptionHandler(NodoErrorException.class)
    public ResponseEntity<?> nodoErrorHandler(NodoErrorException e) {
        String faultCode = e.getFaultCode();
        ResponseEntity<?> response = nodeErrorToV2TransactionsResponseEntityMapping.getOrDefault(
                faultCode,
                new ResponseEntity<>(
                        new GatewayFaultPaymentProblemJsonDto()
                                .title("Bad gateway")
                                .faultCodeCategory(
                                        GatewayFaultPaymentProblemJsonDto.FaultCodeCategoryEnum.GENERIC_ERROR
                                )
                                .faultCodeDetail(faultCode),
                        HttpStatus.BAD_GATEWAY
                )
        );

        log.error(
                "Nodo error processing request with fault code: [" + faultCode + "] mapped to http status code: [" +
                        response.getStatusCode() + "]",
                e
        );
        return response;
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
        IntStream.range(0, 3).forEach(
                idx -> {
                    log.info("Performing warmup iteration: {}", idx);
                    NewTransactionResponseDto newTransactionResponseDto = WebClient
                            .create()
                            .post()
                            .uri("http://localhost:8080/v2/transactions")
                            .header("X-Client-Id", NewTransactionResponseDto.ClientIdEnum.CHECKOUT.toString())
                            .header("x-correlation-id", UUID.randomUUID().toString())
                            .bodyValue(transactionsUtils.buildWarmupRequestV2())
                            .retrieve()
                            .bodyToMono(NewTransactionResponseDto.class)
                            .block(Duration.ofSeconds(30));
                    WebClient
                            .create()
                            .get()
                            .uri(
                                    "http://localhost:8080/v2/transactions/{transactionId}",
                                    newTransactionResponseDto.getTransactionId()
                            )
                            .header("X-Client-Id", TransactionInfoDto.ClientIdEnum.CHECKOUT.toString())
                            .retrieve()
                            .toBodilessEntity()
                            .block(Duration.ofSeconds(30));
                }
        );

    }
}
