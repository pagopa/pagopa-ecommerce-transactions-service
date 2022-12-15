package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.Transaction;
import it.pagopa.ecommerce.commons.documents.TransactionClosureErrorEvent;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
public class ClosureErrorProjectionHandlerTest {
    @Mock
    private TransactionsViewRepository transactionsViewRepository;

    @InjectMocks
    private ClosureErrorProjectionHandler closureErrorProjectionHandler;

    @Test
    void shouldHandleProjection() {
        Transaction transaction = transactionDocument();

        TransactionClosureErrorEvent closureErrorEvent = new TransactionClosureErrorEvent(
          transaction.getTransactionId(),
          transaction.getRptId(),
          transaction.getPaymentToken()
        );

        Transaction expected = new Transaction(
                transaction.getTransactionId(),
                transaction.getPaymentToken(),
                transaction.getRptId(),
                transaction.getDescription(),
                transaction.getAmount(),
                transaction.getEmail(),
                TransactionStatusDto.CLOSURE_ERROR,
                transaction.getCreationDate()
        );

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId())).thenReturn(Mono.just(transaction));
        Mockito.when(transactionsViewRepository.save(any())).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(closureErrorProjectionHandler.handle(closureErrorEvent))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldReturnTransactionNotFoundExceptionOnTransactionNotFound() {
        Transaction transaction = transactionDocument();

        TransactionClosureErrorEvent closureErrorEvent = new TransactionClosureErrorEvent(
                transaction.getTransactionId(),
                transaction.getRptId(),
                transaction.getPaymentToken()
        );

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId())).thenReturn(Mono.empty());

        StepVerifier.create(closureErrorProjectionHandler.handle(closureErrorEvent))
                .expectError(TransactionNotFoundException.class)
                .verify();
    }

    @NotNull
    private Transaction transactionDocument() {
        return new Transaction(
                UUID.randomUUID().toString(),
                "paymentToken",
                "77777777777302016723749670035",
                "description",
                100,
                "foo@example.com",
                TransactionStatusDto.AUTHORIZED
        );
    }
}
