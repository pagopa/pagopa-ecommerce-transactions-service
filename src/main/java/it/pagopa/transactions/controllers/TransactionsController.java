package it.pagopa.transactions.controllers;

import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.exceptions.AlreadyAuthorizedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.generated.transactions.server.api.TransactionsApi;

import it.pagopa.transactions.services.TransactionsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
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
    public Mono<ResponseEntity<TransactionInfoDto>> getTransactionInfo(String paymentToken, ServerWebExchange exchange) {
        return transactionsService.getTransactionInfo(paymentToken).map(ResponseEntity::ok);
    }

    @Override
    public Mono<ResponseEntity<RequestAuthorizationResponseDto>> requestTransactionAuthorization(String paymentToken, Mono<RequestAuthorizationRequestDto> requestAuthorizationRequestDto, ServerWebExchange exchange) {
        return requestAuthorizationRequestDto
                .flatMap(requestAuthorizationRequest -> transactionsService.requestTransactionAuthorization(paymentToken, requestAuthorizationRequest))
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

    @ExceptionHandler(AlreadyAuthorizedException.class)
    private ResponseEntity<ProblemJsonDto> alreadyAuthorizedHandler(AlreadyAuthorizedException exception) {
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(409)
                        .title("Transaction already authorized")
                        .detail("Transaction for RPT id '%s' has been already authorized".formatted(exception.getRptId().value())),
                HttpStatus.CONFLICT);
    }
}
