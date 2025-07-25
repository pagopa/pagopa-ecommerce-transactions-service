package it.pagopa.transactions.projections.handlers.v2;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.TransactionClosureRequestedEvent;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.ZonedDateTime;

import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
public class ClosureRequestedProjectionHandlerTests {

    private Boolean transactionsviewUpdateEnabled = true;

    private TransactionsViewRepository transactionsViewRepository = Mockito.mock();

    private final ClosureRequestedProjectionHandler closureRequestedProjectionHandler = new ClosureRequestedProjectionHandler(
            transactionsViewRepository,
            transactionsviewUpdateEnabled
    );

    @Test
    void shouldHandleProjection() {
        Transaction transaction = TransactionTestUtils
                .transactionDocument(TransactionStatusDto.AUTHORIZATION_COMPLETED, ZonedDateTime.now());

        TransactionClosureRequestedEvent transactionClosureRequestedEvent = new TransactionClosureRequestedEvent(
                transaction.getTransactionId()
        );

        Transaction expected = getTransaction(transaction);

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId()))
                .thenReturn(Mono.just(transaction));
        Mockito.when(transactionsViewRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(closureRequestedProjectionHandler.handle(transactionClosureRequestedEvent))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldHandleProjectionWithoutSavingWhenViewUpdateDisabled() {
        ClosureRequestedProjectionHandler handler = new ClosureRequestedProjectionHandler(
                transactionsViewRepository,
                false
        );

        Transaction transaction = TransactionTestUtils
                .transactionDocument(TransactionStatusDto.AUTHORIZATION_COMPLETED, ZonedDateTime.now());

        TransactionClosureRequestedEvent transactionClosureRequestedEvent = new TransactionClosureRequestedEvent(
                transaction.getTransactionId()
        );

        Transaction expected = getTransaction(transaction);

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId()))
                .thenReturn(Mono.just(transaction));

        StepVerifier.create(handler.handle(transactionClosureRequestedEvent))
                .expectNext(expected)
                .verifyComplete();

        Mockito.verify(transactionsViewRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void shouldReturnTransactionNotFoundExceptionOnTransactionNotFound() {
        Transaction transaction = TransactionTestUtils
                .transactionDocument(TransactionStatusDto.AUTHORIZATION_COMPLETED, ZonedDateTime.now());

        TransactionClosureRequestedEvent transactionClosureRequestedEvent = new TransactionClosureRequestedEvent(
                transaction.getTransactionId()
        );

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId())).thenReturn(Mono.empty());

        StepVerifier.create(closureRequestedProjectionHandler.handle(transactionClosureRequestedEvent))
                .expectError(TransactionNotFoundException.class)
                .verify();
    }

    private static Transaction getTransaction(Transaction transaction) {
        return new Transaction(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getFeeTotal(),
                transaction.getEmail(),
                TransactionStatusDto.CLOSURE_REQUESTED,
                Transaction.ClientId.CHECKOUT,
                transaction.getCreationDate(),
                transaction.getIdCart(),
                transaction.getRrn(),
                TransactionTestUtils.USER_ID,
                transaction.getPaymentTypeCode(),
                transaction.getPspId()
        );
    }
}
