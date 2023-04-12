package it.pagopa.transactions.controllers;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import it.pagopa.ecommerce.commons.annotations.WarmupMethod;
import it.pagopa.generated.transactions.server.api.TransactionsApi;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.services.TransactionsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.Collectors;

@RestController
@Slf4j
public class TransactionsController implements TransactionsApi {
    @Autowired
    private TransactionsService transactionsService;

    @Value("${warmup.request.newTransaction.noticeCodePrefix}")
    private String warmUpNoticeCodePrefix;

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

        return newTransactionRequest
                .flatMap(ntr -> {
                    log.info(
                            "newTransaction rptIDs {} ",
                            String.join(
                                    ",",
                                    ntr.getPaymentNotices().stream().map(PaymentNoticeInfoDto::getRptId).toList()
                            )
                    );
                    return transactionsService.newTransaction(ntr, xClientId);
                })
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<TransactionInfoDto>> getTransactionInfo(
                                                                       String transactionId,
                                                                       ServerWebExchange exchange
    ) {
        return transactionsService.getTransactionInfo(transactionId)
                .doOnEach(t -> log.info("getTransactionInfo for transactionId: {} ", transactionId))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<RequestAuthorizationResponseDto>> requestTransactionAuthorization(
                                                                                                 String transactionId,
                                                                                                 Mono<RequestAuthorizationRequestDto> requestAuthorizationRequestDto,
                                                                                                 String xPgsId,
                                                                                                 ServerWebExchange exchange
    ) {
        return requestAuthorizationRequestDto
                .doOnEach(t -> log.info("requestTransactionAuthorization for transactionId: {} ", transactionId))
                .flatMap(
                        requestAuthorizationRequest -> transactionsService
                                .requestTransactionAuthorization(transactionId, xPgsId, requestAuthorizationRequest)
                )
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<TransactionInfoDto>> updateTransactionAuthorization(
                                                                                   String transactionId,
                                                                                   Mono<UpdateAuthorizationRequestDto> updateAuthorizationRequestDto,
                                                                                   ServerWebExchange exchange
    ) {
        return updateAuthorizationRequestDto
                .doOnEach(t -> log.info("updateTransactionAuthorization for transactionId: {} ", transactionId))
                .flatMap(
                        updateAuthorizationRequest -> transactionsService
                                .updateTransactionAuthorization(transactionId, updateAuthorizationRequest)
                )
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<AddUserReceiptResponseDto>> addUserReceipt(
                                                                          String transactionId,
                                                                          Mono<AddUserReceiptRequestDto> addUserReceiptRequestDto,
                                                                          ServerWebExchange exchange
    ) {
        return addUserReceiptRequestDto
                .doOnEach(t -> log.info("addUserReceipt for transactionId: {} ", transactionId))
                .flatMap(
                        addUserReceiptRequest -> transactionsService
                                .addUserReceipt(transactionId, addUserReceiptRequest)
                                .map(
                                        _v -> new AddUserReceiptResponseDto()
                                                .outcome(AddUserReceiptResponseDto.OutcomeEnum.OK)
                                )
                                .doOnError(e -> log.error("Got error while trying to add user receipt", e))
                                .onErrorReturn(
                                        new AddUserReceiptResponseDto()
                                                .outcome(AddUserReceiptResponseDto.OutcomeEnum.KO)
                                )
                )
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<Void>> requestTransactionUserCancellation(
                                                                         String transactionId,
                                                                         ServerWebExchange exchange
    ) {
        return transactionsService.cancelTransaction(transactionId)
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

    @WarmupMethod
    public void postNewTransactionWarmupMethod() {
        NewTransactionRequestDto newTransactionRequestDto = new NewTransactionRequestDto()
                .email("test@test.it")
                .paymentNotices(
                        Collections.singletonList(
                                new PaymentNoticeInfoDto()
                                        .rptId("77777777777%s13191830179260".formatted(warmUpNoticeCodePrefix))
                                        .amount(100)
                        )
                );
        WebClient
                .create()
                .post()
                .uri("http://localhost:8080/transactions")
                .header("X-Client-Id", TransactionInfoDto.ClientIdEnum.CHECKOUT.toString())
                .bodyValue(newTransactionRequestDto)
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(30));

    }
}
