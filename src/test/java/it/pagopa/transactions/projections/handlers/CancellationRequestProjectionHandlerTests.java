package it.pagopa.transactions.projections.handlers;
import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserCanceledEvent;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.ZonedDateTime;

import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
public class CancellationRequestProjectionHandlerTests {

    @InjectMocks
    private CancellationRequestProjectionHandler cancellationRequestProjectionHandler;

    @Mock
    private TransactionsViewRepository transactionsViewRepository;

    @Test
    void shouldHandleProjection() {
        Transaction transaction = TransactionTestUtils
                .transactionDocument(TransactionStatusDto.ACTIVATED, ZonedDateTime.now());

        TransactionUserCanceledEvent transactionUserCanceledEvent = new TransactionUserCanceledEvent(
                transaction.getTransactionId()
        );

        Transaction expected = new Transaction(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getFeeTotal(),
                transaction.getEmail(),
                TransactionStatusDto.CANCELLATION_REQUESTED,
                Transaction.ClientId.CHECKOUT,
                transaction.getCreationDate()
        );

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId()))
                .thenReturn(Mono.just(transaction));
        Mockito.when(transactionsViewRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(cancellationRequestProjectionHandler.handle(transactionUserCanceledEvent))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldReturnTransactionNotFoundExceptionOnTransactionNotFound() {
        Transaction transaction = TransactionTestUtils
                .transactionDocument(TransactionStatusDto.ACTIVATED, ZonedDateTime.now());

        TransactionUserCanceledEvent transactionUserCanceledEvent = new TransactionUserCanceledEvent(
                transaction.getTransactionId()
        );

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId())).thenReturn(Mono.empty());

        StepVerifier.create(cancellationRequestProjectionHandler.handle(transactionUserCanceledEvent))
                .expectError(TransactionNotFoundException.class)
                .verify();
    }
}
