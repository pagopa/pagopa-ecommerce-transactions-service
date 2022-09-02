package it.pagopa.transactions.services;

import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.commands.TransactionInitializeCommand;
import it.pagopa.transactions.commands.TransactionsCommand;
import it.pagopa.transactions.commands.handlers.TransactionInizializeHandler;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.projections.handlers.TransactionsProjectionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @InjectMocks
    private TransactionsService transactionsService;

    @Mock
    private TransactionInizializeHandler transactionInizializeHandler;

    @Mock
    private TransactionsProjectionHandler transactionsProjectionHandler;

    @Test
    void shouldHandleNewTransaction() {
        String TEST_EMAIL = "j.doe@mail.com";
        String TEST_RPTID = "77777777777302016723749670035";
        String TEST_TOKEN = "token";
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(TEST_EMAIL)
                .rptId(TEST_RPTID);

        NewTransactionResponseDto response = new NewTransactionResponseDto()
                .amount(1)
                .rptId(TEST_RPTID)
                .paymentToken(TEST_TOKEN)
                .reason("")
                .authToken(TEST_TOKEN);

      Transaction transaction = new Transaction(
              new TransactionId(TRANSACTION_ID),
              new PaymentToken(TEST_TOKEN),
              new RptId(TEST_RPTID),
              new TransactionDescription("desc"),
              new TransactionAmount(0),
              TransactionStatusDto.INITIALIZED
      );
        /**
         * Preconditions
         */
        Mockito.when(transactionInizializeHandler.handle(Mockito.any(TransactionInitializeCommand.class))).thenReturn(Mono.just(response));
        Mockito.when(transactionsProjectionHandler.handle(response)).thenReturn(Mono.just(transaction));

        /**
         * Test
         */
        NewTransactionResponseDto responseDto = transactionsService.newTransaction(transactionRequestDto).block();

        /**
         * Assertions
         */
        assertEquals(transactionRequestDto.getRptId(), responseDto.getRptId());
    }
}
