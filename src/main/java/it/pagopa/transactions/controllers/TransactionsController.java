package it.pagopa.transactions.controllers;

import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.generated.transactions.server.api.TransactionsApi;

import it.pagopa.transactions.services.TransactionsService;

import javax.validation.Valid;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

@RestController
@Slf4j
public class TransactionsController implements TransactionsApi {

    @Autowired
    private TransactionsService transactionsService;

    @Override
    public Mono<ResponseEntity<NewTransactionResponseDto>> newTransaction(Mono<NewTransactionRequestDto> newTransactionRequest, ServerWebExchange exchange) {
        return newTransactionRequest
                .flatMap(transactionsService::newTransaction)
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<TransactionInfoDto>> getTransactionInfo(String transactionId, ServerWebExchange exchange) {
        return transactionsService.getTransactionInfo(transactionId).map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<RequestAuthorizationResponseDto>> requestTransactionAuthorization(String transactionId, Mono<RequestAuthorizationRequestDto> requestAuthorizationRequestDto, ServerWebExchange exchange) {
        return requestAuthorizationRequestDto
                .flatMap(requestAuthorizationRequest -> transactionsService.requestTransactionAuthorization(transactionId, requestAuthorizationRequest))
                .map(ResponseEntity::ok);
    }


    @Override
    public Mono<ResponseEntity<TransactionInfoDto>> updateTransactionAuthorization(String transactionId, Mono<UpdateAuthorizationRequestDto> updateAuthorizationRequestDto, ServerWebExchange exchange) {
        return updateAuthorizationRequestDto
                .flatMap(updateAuthorizationRequest -> transactionsService.updateTransactionAuthorization(transactionId, updateAuthorizationRequest))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<ActivationResultResponseDto>> transactionActivationResult(String paymentContextCode, Mono<ActivationResultRequestDto> activationResultRequestDto, ServerWebExchange exchange) {
        return activationResultRequestDto
                .flatMap(activationResultRequest -> transactionsService.activateTransaction(paymentContextCode, activationResultRequest))
                .map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<AddUserReceiptResponseDto>> addUserReceipt(String transactionId, Mono<AddUserReceiptRequestDto> addUserReceiptRequestDto, ServerWebExchange exchange) {
        return addUserReceiptRequestDto
                .flatMap(addUserReceiptRequest ->
                        transactionsService.addUserReceipt(transactionId, addUserReceiptRequest)
                                .map(_v -> new AddUserReceiptResponseDto().outcome(AddUserReceiptResponseDto.OutcomeEnum.OK))
                                .onErrorReturn(new AddUserReceiptResponseDto().outcome(AddUserReceiptResponseDto.OutcomeEnum.KO))
                )
                .map(ResponseEntity::ok);
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    private ResponseEntity<ProblemJsonDto> transactionNotFoundHandler(TransactionNotFoundException exception) {
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(404)
                        .title("Transaction not found")
                        .detail("Transaction for payment token '%s' not found".formatted(exception.getPaymentToken())),
                HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(UnsatisfiablePspRequestException.class)
    private ResponseEntity<ProblemJsonDto> unsatisfiablePspRequestHandler(UnsatisfiablePspRequestException exception) {
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(409)
                        .title("Cannot find a PSP with the requested parameters")
                        .detail("Cannot find a PSP with fee %d and language %s for transaction with payment token '%s'"
                                .formatted(exception.getRequestedFee() / 100, exception.getLanguage(), exception.getPaymentToken().value())),
                HttpStatus.CONFLICT);
    }

    @ExceptionHandler(AlreadyProcessedException.class)
    private ResponseEntity<ProblemJsonDto> alreadyProcessedHandler(AlreadyProcessedException exception) {
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(409)
                        .title("Transaction already processed")
                        .detail("Transaction for RPT id '%s' has been already processed".formatted(exception.getRptId().value())),
                HttpStatus.CONFLICT);
    }

    @ExceptionHandler(BadGatewayException.class)
    private ResponseEntity<ProblemJsonDto> badGatewayHandler(BadGatewayException exception) {
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(502)
                        .title("Bad gateway")
                        .detail(exception.getDetail()),
                HttpStatus.BAD_GATEWAY);
    }

    @ExceptionHandler(GatewayTimeoutException.class)
    private ResponseEntity<ProblemJsonDto> gatewayTimeoutHandler(GatewayTimeoutException exception) {
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(504)
                        .title("Gateway timeout")
                        .detail(null),
                HttpStatus.GATEWAY_TIMEOUT);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    private ResponseEntity<ProblemJsonDto> validationExceptionHandler(WebExchangeBindException exception) {
        String errorMessage = exception.getAllErrors().stream().map(ObjectError::toString).collect(Collectors.joining(", "));

        log.warn("Got invalid input: {}", errorMessage);

        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(400)
                        .title("Bad request")
                        .detail("Invalid request: %s".formatted(errorMessage)),
                HttpStatus.BAD_REQUEST
        );
    }
}
