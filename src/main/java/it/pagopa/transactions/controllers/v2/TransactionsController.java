package it.pagopa.transactions.controllers.v2;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import it.pagopa.ecommerce.commons.annotations.Warmup;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.generated.transactions.v2.server.api.V2Api;
import it.pagopa.generated.transactions.v2.server.model.*;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.mdcutilities.TransactionTracingUtils;
import it.pagopa.transactions.services.v2.TransactionsService;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.validation.ConstraintViolationException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@RestController("TransactionsControllerV2")
@Slf4j
public class TransactionsController implements V2Api {

    @Autowired
    private TransactionsService transactionsService;

    @Autowired
    private TransactionsUtils transactionsUtils;

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
                                        new HashSet<>()
                                ),
                                context
                        )
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
                ConstraintViolationException.class
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

    private static final Map<String, ResponseEntity<?>> nodoErrorToResponseEntityMapping;

    static {
        Map<String, ResponseEntity<?>> errorMapping = new HashMap<>();
        // nodo error code to 404 response mapping
        errorMapping.putAll(
                Arrays.stream(ValidationFaultDto.values()).collect(
                        Collectors.toMap(
                                ValidationFaultDto::toString,
                                error -> new ResponseEntity<>(
                                        new ValidationFaultPaymentProblemJsonDto()
                                                .title("Validation Fault")
                                                .faultCodeCategory(FaultCategoryDto.PAYMENT_UNKNOWN)
                                                .faultCodeDetail(error),
                                        HttpStatus.NOT_FOUND
                                )
                        )
                )
        );
        // nodo error code to 409 response mapping
        errorMapping.putAll(
                Arrays.stream(PaymentStatusFaultDto.values()).collect(
                        Collectors.toMap(
                                PaymentStatusFaultDto::toString,
                                error -> new ResponseEntity<>(
                                        new PaymentStatusFaultPaymentProblemJsonDto()
                                                .title("Payment Status Fault")
                                                .faultCodeCategory(FaultCategoryDto.PAYMENT_UNAVAILABLE)
                                                .faultCodeDetail(error),
                                        HttpStatus.CONFLICT
                                )
                        )
                )
        );
        // nodo error code to 502 response mapping
        errorMapping.putAll(
                Arrays.stream(GatewayFaultDto.values()).collect(
                        Collectors.toMap(
                                GatewayFaultDto::toString,
                                error -> new ResponseEntity<>(
                                        new GatewayFaultPaymentProblemJsonDto()
                                                .title("Payment unavailable")
                                                .faultCodeCategory(FaultCategoryDto.GENERIC_ERROR)
                                                .faultCodeDetail(error),
                                        HttpStatus.BAD_GATEWAY
                                )
                        )
                )
        );
        // nodo error code to 503 response mapping
        errorMapping.putAll(
                Arrays.stream(PartyConfigurationFaultDto.values()).collect(
                        Collectors.toMap(
                                PartyConfigurationFaultDto::toString,
                                error -> new ResponseEntity<>(
                                        new PartyConfigurationFaultPaymentProblemJsonDto()
                                                .title("EC error")
                                                .faultCodeCategory(FaultCategoryDto.PAYMENT_UNAVAILABLE)
                                                .faultCodeDetail(error),
                                        HttpStatus.SERVICE_UNAVAILABLE
                                )
                        )
                )
        );
        // nodo error code to 504 response mapping
        errorMapping.putAll(
                Arrays.stream(PartyTimeoutFaultDto.values()).collect(
                        Collectors.toMap(
                                PartyTimeoutFaultDto::toString,
                                error -> new ResponseEntity<>(
                                        new PartyTimeoutFaultPaymentProblemJsonDto()
                                                .title("Gateway Timeout")
                                                .faultCodeCategory(FaultCategoryDto.GENERIC_ERROR)
                                                .faultCodeDetail(error),
                                        HttpStatus.GATEWAY_TIMEOUT
                                )
                        )
                )
        );
        nodoErrorToResponseEntityMapping = Collections.unmodifiableMap(errorMapping);
    }

    @ExceptionHandler(
        {
                NodoErrorException.class,
        }
    )
    ResponseEntity<?> nodoErrorHandler(NodoErrorException exception) {
        String faultCode = exception.getFaultCode();
        ResponseEntity<?> errorResponse = nodoErrorToResponseEntityMapping.getOrDefault(
                faultCode,
                new ResponseEntity<>(
                        new ProblemJsonDto().title("Bad gateway"),
                        HttpStatus.BAD_GATEWAY
                )
        );
        log.error(
                "Nodo error processing request with fault code: [%s] mapped to http status code: [%s]"
                        .formatted(faultCode, errorResponse.getStatusCode()),
                exception
        );
        return errorResponse;
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
                .uri("http://localhost:8080/v2/transactions")
                .header("X-Client-Id", NewTransactionResponseDto.ClientIdEnum.CHECKOUT.toString())
                .bodyValue(transactionsUtils.buildWarmupRequestV2())
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(30));

    }
}
