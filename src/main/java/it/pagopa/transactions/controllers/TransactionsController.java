package it.pagopa.transactions.controllers;

import it.pagopa.nodeforpsp.ActivatePaymentNoticeReq;
import it.pagopa.nodeforpsp.CtQrCode;
import it.pagopa.nodeforpsp.ObjectFactory;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.model.IdempotencyKey;
import it.pagopa.transactions.model.RptId;
import it.pagopa.transactions.repositories.TransactionTokens;
import it.pagopa.transactions.repositories.TransactionTokensRepository;
import it.pagopa.transactions.server.api.TransactionsApi;
import it.pagopa.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.transactions.services.TransactionsService;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Random;

@RestController
public class TransactionsController implements TransactionsApi {

    @Autowired
    TransactionsService transactionsService;

    @Override
    public ResponseEntity<NewTransactionResponseDto> newTransaction(NewTransactionRequestDto newTransactionRequestDto) {
        return ResponseEntity.ok(transactionsService.createTransactions(newTransactionRequestDto));
    }
}
