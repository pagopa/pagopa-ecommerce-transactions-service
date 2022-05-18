package it.pagopa.transactions.services;

import it.pagopa.transactions.documents.Transaction;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.server.model.TransactionInfoDto;
import it.pagopa.transactions.server.model.TransactionStatusDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@TestPropertySource(locations = "classpath:application-tests.properties")
public class TransactionServiceTests {
    @Mock
    private TransactionsViewRepository repository;

    @InjectMocks
    private TransactionsService transactionsService;

    @Test
    void getTransactionReturnsTransactionData() {
        final String PAYMENT_TOKEN = "aaa";
        final Transaction transaction = new Transaction(PAYMENT_TOKEN, "rptId", "reason", 100, TransactionStatusDto.INITIALIZED);
        final TransactionInfoDto expected = new TransactionInfoDto()
                .amount(transaction.getAmount())
                .recipientIban(null)
                .reason("reason")
                .beneficiary(null)
                .installments(null)
                .paymentToken(PAYMENT_TOKEN)
                .authToken(null)
                .rptId("rptId")
                .status(TransactionStatusDto.INITIALIZED);


        when(repository.findById(PAYMENT_TOKEN)).thenReturn(Mono.just(transaction));

        assertEquals(
                transactionsService.getTransactionInfo(PAYMENT_TOKEN).block(),
                expected
        );
    }

    @Test
    void getTransactionThrowsOnTransactionNotFound() {
        final String PAYMENT_TOKEN = "aaa";
        when(repository.findById(PAYMENT_TOKEN)).thenReturn(Mono.empty());

        assertThrows(
                TransactionNotFoundException.class,
                () -> transactionsService.getTransactionInfo(PAYMENT_TOKEN).block()
        );
    }

}