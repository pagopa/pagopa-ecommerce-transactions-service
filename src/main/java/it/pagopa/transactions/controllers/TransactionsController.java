package it.pagopa.transactions.controllers;

import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.server.api.TransactionsApi;
import it.pagopa.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.transactions.server.model.ProblemJsonDto;
import it.pagopa.transactions.server.model.TransactionInfoDto;
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

    @ExceptionHandler(TransactionNotFoundException.class)
    private ResponseEntity<ProblemJsonDto> transactionNotFoundHandler(TransactionNotFoundException exception) {
        return new ResponseEntity<>(
                new ProblemJsonDto()
                        .status(404)
                        .title("Transaction not found")
                        .detail("Transaction for payment token '%s' not found".formatted(exception.getPaymentToken())),
                HttpStatus.NOT_FOUND);
    }
}
