package it.pagopa.transactions.projections.handlers;

import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.Transaction;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class TransactionProjectionHandlerTest {

    @InjectMocks
    private TransactionsProjectionHandler transactionsProjectionHandler;

    @Mock
    private TransactionsViewRepository viewEventStoreRepository;

    @Test
    void shouldHandleTransaction(){

        NewTransactionResponseDto data  = new NewTransactionResponseDto()
                .paymentToken("token")
                .rptId("77777777777302016723749670035")
                .reason("reason")
                .amount(1);

        Transaction transaction = new Transaction(
                data.getPaymentToken(),
                data.getRptId(),
                data.getReason(),
                data.getAmount(),
                TransactionStatusDto.INITIALIZED);
        /**
         * Preconditions
         */
        Mockito.when(viewEventStoreRepository.save(Mockito.any(Transaction.class))).thenReturn(Mono.just(transaction));

        /**
         * Test
         */
        Transaction result = transactionsProjectionHandler.handle(data).block();

        /**
        * Assertions
         */
        assertEquals(transaction, result);
    }
}
