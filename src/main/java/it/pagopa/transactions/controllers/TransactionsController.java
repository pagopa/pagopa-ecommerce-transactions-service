package it.pagopa.transactions.controllers;

import it.pagopa.transactions.server.api.TransactionsApi;
import it.pagopa.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.transactions.services.TransactionsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
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
}
