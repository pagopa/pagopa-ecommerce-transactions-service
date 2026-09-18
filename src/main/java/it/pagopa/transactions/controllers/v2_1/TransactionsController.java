package it.pagopa.transactions.controllers.v2_1;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import it.pagopa.ecommerce.commons.annotations.Warmup;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.generated.transactions.server.model.TransactionInfoDto;
import it.pagopa.generated.transactions.v2_1.server.api.V21Api;
import it.pagopa.generated.transactions.v2_1.server.model.*;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.services.v2_1.TransactionsService;
import it.pagopa.transactions.utils.SpanLabelOpenTelemetry;
import it.pagopa.transactions.utils.TransactionsUtils;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static it.pagopa.transactions.utils.TransactionsUtils.nodeErrorToV2_1TransactionsResponseEntityMapping;

@RestController("TransactionsControllerV2_1")
@Slf4j
public class TransactionsController implements V21Api {

    @Autowired
    private TransactionsService transactionsService;

    @Autowired
    private TransactionsUtils transactionsUtils;

    @Autowired
    private OpenTelemetryUtils openTelemetryUtils;

    @Value("${security.apiKey.primary}")
    private String primaryKey;

    private static final String BAD_GATEWAY_DESC = "Bad gateway";

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
                        ntr -> transactionsService
                                .newTransaction(ntr, xClientId, correlationId, transactionId, xUserId)
                                .doOnNext(
                                        response -> LogTracingUtils.loggerTracingUtils()
                                                .success()
                                                .attributes(
                                                        Map.of(
                                                                LogTracingUtils.AttributeKeys.CTX_RPT_IDS,
                                                                ntr.getPaymentNotices().stream()
                                                                        .map(PaymentNoticeInfoDto::getRptId).toList()
                                                                        .toString()
                                                        )
                                                )
                                                .details(
                                                        Map.of(
                                                                "id_cart",
                                                                Objects.toString(response.getIdCart())
                                                        )
                                                )
                                                .logInfo(log, "New transaction created successfully")
                                )
                )
                .contextWrite(
                        ctx -> LogTracingUtils.enrichContextForEvent(
                                Map.of(
                                        LogTracingUtils.AttributeKeys.CTX_TRANSACTION_ID,
                                        transactionId.value()
                                ),
                                ctx
                        )
                )
                .map(ResponseEntity::ok);
    }

    @ExceptionHandler(AlreadyProcessedException.class)
    ResponseEntity<ProblemJsonDto> alreadyProcessedHandler(AlreadyProcessedException exception) {
        LogTracingUtils.loggerTracingUtils()
                .failure()
                .details(
                        Map.of(
                                "payment_type_code",
                                exception.paymentTypeCode().orElse("{paymentTypeCode-not-found}"),
                                "is_wallet_payment",
                                exception.walletPayment().orElse(false).toString(),
                                "transaction_status",
                                exception.transactionStatus().orElse("{transactionStatus-not-found}")
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

    @ExceptionHandler(BadGatewayException.class)
    ResponseEntity<ProblemJsonDto> badGatewayHandler(BadGatewayException exception) {
        LogTracingUtils.loggerTracingUtils()
                .failure()
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
        LogTracingUtils.loggerTracingUtils()
                .failure()
                .dependency(LogTracingUtils.JWT_ISSUER_DEPENDENCY)
                .details(
                        Map.of(
                                "status",
                                exception.status.toString(),
                                "reason",
                                exception.reason
                        )
                )
                .logError(log, exception, "Error while interacting with jwt-issuer");

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
        ResponseEntity<?> response = nodeErrorToV2_1TransactionsResponseEntityMapping.getOrDefault(
                faultCode,
                new ResponseEntity<>(
                        new GatewayFaultPaymentProblemJsonDto()
                                .title(BAD_GATEWAY_DESC)
                                .faultCodeCategory(
                                        GatewayFaultPaymentProblemJsonDto.FaultCodeCategoryEnum.GENERIC_ERROR
                                )
                                .faultCodeDetail(faultCode),
                        HttpStatus.BAD_GATEWAY
                )
        );

        LogTracingUtils.loggerTracingUtils()
                .failure()
                .dependency(LogTracingUtils.NODO_DEPENDENCY)
                .details(
                        Map.of(
                                "fault_code",
                                e.getFaultCode(),
                                "mapped_status_code",
                                response.getStatusCode().toString()
                        )
                )
                .logError(log, e, "Error while interacting with NODO - ActivatePaymentNoticeV2");

        return response;
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

    @ExceptionHandler(DigitalStampNotAllowedForClientException.class)
    ResponseEntity<ValidationFaultPaymentDataErrorProblemJsonDto> digitalStampNotAllowedHandler(
                                                                                                DigitalStampNotAllowedForClientException exception
    ) {
        LogTracingUtils.loggerTracingUtils()
                .failure()
                .dependency(LogTracingUtils.NODO_DEPENDENCY)
                .attributes(
                        Map.of(LogTracingUtils.AttributeKeys.CTX_CLIENT_ID, exception.getClientId())
                )
                .logError(log, exception, "This client can't pay notices with digital stamps");

        return new ResponseEntity<>(
                new ValidationFaultPaymentDataErrorProblemJsonDto()
                        .faultCodeCategory(
                                ValidationFaultPaymentDataErrorProblemJsonDto.FaultCodeCategoryEnum.PAYMENT_DATA_ERROR
                        )
                        .faultCodeDetail(ValidationFaultPaymentDataErrorDto.PPT_DOMINIO_SCONOSCIUTO),
                HttpStatus.NOT_FOUND
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

    @Warmup
    public void postNewTransactionWarmupMethod() {
        IntStream.range(0, 3).forEach(
                idx -> {
                    NewTransactionResponseDto newTransactionResponseDto = WebClient
                            .create()
                            .post()
                            .uri("http://localhost:8080/v2.1/transactions")
                            .header("X-Client-Id", NewTransactionResponseDto.ClientIdEnum.CHECKOUT.toString())
                            .header("x-correlation-id", UUID.randomUUID().toString())
                            .header("x-api-key", primaryKey)
                            .bodyValue(transactionsUtils.buildWarmupRequestV2_1())
                            .retrieve()
                            .bodyToMono(NewTransactionResponseDto.class)
                            .block(Duration.ofSeconds(30));
                    WebClient
                            .create()
                            .get()
                            .uri(
                                    "http://localhost:8080/transactions/{transactionId}",
                                    newTransactionResponseDto.getTransactionId()
                            )
                            .header("X-Client-Id", TransactionInfoDto.ClientIdEnum.CHECKOUT.toString())
                            .header("x-api-key", primaryKey)
                            .retrieve()
                            .toBodilessEntity()
                            .block(Duration.ofSeconds(30));
                }
        );

    }
}
