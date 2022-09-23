package it.pagopa.transactions.services;

import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.commands.TransactionInitializeCommand;
import it.pagopa.transactions.commands.handlers.TransactionInizializeHandler;
import it.pagopa.transactions.documents.TransactionInitData;
import it.pagopa.transactions.documents.TransactionInitEvent;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.projections.handlers.TransactionsProjectionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.time.ZonedDateTime;
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

        TransactionInitEvent event = new TransactionInitEvent(
                TRANSACTION_ID.toString(),
                TEST_RPTID,
                TEST_TOKEN,
                ZonedDateTime.now().toString(),
                new TransactionInitData(
                        "desc",
                        0,
                        TEST_EMAIL,
                        "faultCode",
                        "faultCodeString"
                )
        );

      TransactionInitialized transaction = new TransactionInitialized(
              new TransactionId(TRANSACTION_ID),
              new PaymentToken(TEST_TOKEN),
              new RptId(TEST_RPTID),
              new TransactionDescription("desc"),
              new TransactionAmount(0),
              new Email(TEST_EMAIL),
              TransactionStatusDto.INITIALIZED
      );
        /**
         * Preconditions
         */
        Mockito.when(transactionInizializeHandler.handle(Mockito.any(TransactionInitializeCommand.class))).thenReturn(Mono.just(Tuples.of(response, event)));
        Mockito.when(transactionsProjectionHandler.handle(event)).thenReturn(Mono.just(transaction));

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
