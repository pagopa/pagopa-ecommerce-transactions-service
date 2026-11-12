package it.pagopa.transactions.controllers.v2;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.generated.transactions.v2.server.api.V2Api;
import it.pagopa.generated.transactions.v2.server.model.*;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.services.v1.TransactionsService;
import it.pagopa.transactions.utils.SpanLabelOpenTelemetry;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

import static it.pagopa.transactions.utils.TransactionsUtils.nodeErrorToV2TransactionsResponseEntityMapping;

@RestController("TransactionsControllerV2")
@Slf4j
public class TransactionsController implements V2Api {

    @Qualifier(TransactionsService.QUALIFIER_NAME)
    @Autowired
    private TransactionsService transactionsService;

    @Autowired
    private OpenTelemetryUtils openTelemetryUtils;

    private static final String BAD_GATEWAY_DESC = "Bad gateway";

    @Override
    public Mono<ResponseEntity<UpdateAuthorizationResponseDto>> updateTransactionAuthorization(
                                                                                               String transactionId,
                                                                                               Mono<UpdateAuthorizationRequestDto> updateAuthorizationRequestDto,
                                                                                               ServerWebExchange exchange
    ) {
        return updateAuthorizationRequestDto
                .flatMap(
                        updateAuthorizationRequest -> transactionsService.updateTransactionAuthorization(
                                new TransactionId(transactionId).uuid(),
                                updateAuthorizationRequest
                        )
                )
                .map(
                        transactionStatus -> new UpdateAuthorizationResponseDto()
                                .status(TransactionStatusDto.fromValue(transactionStatus.toString()))
                )
                .contextWrite(
                        ctx -> LogTracingUtils.enrichContextForEvent(
                                Map.of(
                                        LogTracingUtils.AttributeKeys.CTX_TRANSACTION_ID,
                                        transactionId
                                ),
                                ctx
                        )
                )
                .map(ResponseEntity::ok);
    }

    @ExceptionHandler(ConflictStatusException.class)
    ResponseEntity<ProblemJsonDto> alreadyProcessedHandler(ConflictStatusException exception) {
        LogTracingUtils.loggerTracingUtils()
                .failure()
                .details(
                        Map.of(
                                "payment_type_code",
                                exception.paymentTypeCode().orElse("{paymentTypeCode-not-found}"),
                                "is_wallet_payment",
                                exception.walletPayment().orElse(false).toString(),
                                "transaction_status",
                                exception.transactionStatus().orElse("{transactionStatus-not-found}"),
                                "downstream_http_status",
                                exception.downstreamHttpStatus().orElse("{httpStatus-not-found}"),
                                "downstream_http_response_body",
                                exception.downstreamResponseBody().orElse("{httpResponseBody-not-found}")
                        )
                )
                .attributes(
                        Map.of(
                                LogTracingUtils.AttributeKeys.CTX_CLIENT_ID,
                                exception.clientId().orElse("{clientId-not-found}"),
                                LogTracingUtils.AttributeKeys.CTX_TRANSACTION_ID,
                                exception.getTransactionId().value(),
                                LogTracingUtils.AttributeKeys.PSP_ID,
                                exception.pspId().orElse(LogTracingUtils.AttributeKeys.PSP_ID.getDefaultValue())
                        )
                )
                .logError(log, exception, "Already processed");

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

    @ExceptionHandler(LockNotAcquiredException.class)
    ResponseEntity<ProblemJsonDto> lockNotAcquiredExceptionHandler(LockNotAcquiredException exception) {
        LogTracingUtils.loggerTracingUtils()
                .failure()
                .attributes(
                        Map.of(
                                LogTracingUtils.AttributeKeys.CTX_TRANSACTION_ID,
                                exception.getTransactionId().value()
                        )
                )
                .details(
                        Map.of(
                                "lock_document_id",
                                exception.getExclusiveLockDocument().id()
                        )
                )
                .logError(log, exception, "Error acquiring lock");

        HttpStatus httpStatus = HttpStatus.UNPROCESSABLE_ENTITY;
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(httpStatus.value())
                        .title("Error acquiring lock to perform operation")
                        .detail(exception.getMessage()),
                httpStatus
        );
    }

    @ExceptionHandler(BadGatewayException.class)
    ResponseEntity<ProblemJsonDto> badGatewayHandler(BadGatewayException exception) {
        LogTracingUtils.loggerTracingUtils()
                .failure()
                .details(
                        Map.of(
                                "downstream_http_status",
                                exception.downstreamHttpStatus().orElse("{httpStatus-not-found}"),
                                "downstream_http_response_body",
                                exception.downstreamResponseBody().orElse("{httpResponseBody-not-found}")
                        )
                )
                .logError(log, exception, BAD_GATEWAY_DESC);

        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(502)
                        .title(BAD_GATEWAY_DESC)
                        .detail(exception.getDetail()),
                HttpStatus.BAD_GATEWAY
        );
    }

    @ExceptionHandler(NotImplementedException.class)
    ResponseEntity<ProblemJsonDto> notImplemented(NotImplementedException exception) {
        LogTracingUtils.loggerTracingUtils()
                .failure()
                .logError(log, exception, "Not implemented");

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
        LogTracingUtils.loggerTracingUtils()
                .failure()
                .logError(log, exception, "Gateway timeout");

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

        LogTracingUtils.loggerTracingUtils()
                .failure()
                .details(
                        Map.of("message", errorMessage)
                )
                .logError(log, exception, "Got invalid input");

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
        LogTracingUtils.loggerTracingUtils()
                .failure()
                .logError(log, exception, "Got invalid input");

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
                JwtIssuerResponseException.class
        }
    )
    ResponseEntity<ProblemJsonDto> jwtTokenGenerationError(JwtIssuerResponseException exception) {
        HttpStatus httpStatus = HttpStatus.INTERNAL_SERVER_ERROR;
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(httpStatus.value())
                        .title(httpStatus.getReasonPhrase())
                        .detail("Internal server error: cannot generate JWT token"),
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }

    @ExceptionHandler(
        {
                InvalidNodoResponseException.class,
        }
    )
    ResponseEntity<ProblemJsonDto> invalidNodoResponse(InvalidNodoResponseException exception) {
        LogTracingUtils.loggerTracingUtils()
                .failure()
                .dependency(LogTracingUtils.NODO_DEPENDENCY)
                .details(
                        Map.of("error_description", exception.getErrorDescription())
                )
                .logError(log, exception, "Error while interacting with NODO");

        HttpStatus httpStatus = HttpStatus.BAD_GATEWAY;
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(httpStatus.value())
                        .title(httpStatus.getReasonPhrase())
                        .detail(exception.getErrorDescription()),
                httpStatus
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemJsonDto> genericException(Exception exception) {
        LogTracingUtils.loggerTracingUtils()
                .failure()
                .logError(log, exception, "Unhandled exception");

        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(500)
                        .title("Internal Server Error")
                        .detail(exception.getMessage()),
                HttpStatus.INTERNAL_SERVER_ERROR
        );
    }

    @ExceptionHandler(
        {
                CallNotPermittedException.class
        }
    )
    public Mono<ResponseEntity<it.pagopa.generated.transactions.v2.server.model.ProblemJsonDto>> openStateHandler(
                                                                                                                  CallNotPermittedException error
    ) {
        LogTracingUtils.loggerTracingUtils()
                .failure()
                .logError(log, error, "OPEN circuit breaker");

        return Mono.just(
                new ResponseEntity<>(
                        new it.pagopa.generated.transactions.v2.server.model.ProblemJsonDto()
                                .status(502)
                                .title(BAD_GATEWAY_DESC)
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
}
