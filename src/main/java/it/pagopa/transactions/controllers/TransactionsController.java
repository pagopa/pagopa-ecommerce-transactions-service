package it.pagopa.transactions.controllers;

import it.pagopa.transactions.server.api.TransactionsApi;
import it.pagopa.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.transactions.services.TransactionsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransactionsController implements TransactionsApi {

    @Autowired
    private TransactionsService transactionsService;

    @Override
    public ResponseEntity<NewTransactionResponseDto> newTransaction(NewTransactionRequestDto newTransactionRequestDto) {

        return ResponseEntity.ok(transactionsService.newTransaction(newTransactionRequestDto));
    }
}
