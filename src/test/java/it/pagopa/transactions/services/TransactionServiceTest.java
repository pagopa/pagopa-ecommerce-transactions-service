package it.pagopa.transactions.services;

import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.transactions.commands.TransactionsCommand;
import it.pagopa.transactions.commands.handlers.TransactionInizializeHandler;
import it.pagopa.transactions.projections.handlers.TransactionsProjectionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

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
    void shouldHandleNewTransaction(){
        String TEST_EMAIL = "j.doe@mail.com";
        String TEST_RPTID = "77777777777302016723749670035";
        String TEST_TOKEN = "token";

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(TEST_EMAIL)
                .rptId(TEST_RPTID);

        NewTransactionResponseDto response = new NewTransactionResponseDto()
                .amount(1)
                .rptId(TEST_RPTID)
                .paymentToken(TEST_TOKEN)
                .reason("")
                .authToken(TEST_TOKEN);
        /**
         * Preconditions
         */
        Mockito.when(transactionInizializeHandler.handle(Mockito.any(TransactionsCommand.class))).thenReturn(Mono.just(response));
        Mockito.when(transactionsProjectionHandler.handle(response)).thenReturn(Mono.empty());

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
