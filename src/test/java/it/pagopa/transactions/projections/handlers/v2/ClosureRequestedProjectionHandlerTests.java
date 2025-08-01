package it.pagopa.transactions.projections.handlers.v2;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.TransactionClosureRequestedEvent;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import java.time.ZoneId;
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

    private TransactionsViewRepository transactionsViewRepository = Mockito.mock();

    private ClosureRequestedProjectionHandler closureRequestedProjectionHandler;

    @Test
    void shouldHandleProjection() {
        closureRequestedProjectionHandler = new ClosureRequestedProjectionHandler(
                transactionsViewRepository,
                true
        );

        Transaction transaction = TransactionTestUtils
                .transactionDocument(TransactionStatusDto.AUTHORIZATION_COMPLETED, ZonedDateTime.now());

        ZonedDateTime fixedEventTime = ZonedDateTime.of(2025, 7, 25, 14, 47, 31, 0, ZoneId.of("Europe/Rome"));

        TransactionClosureRequestedEvent transactionClosureRequestedEvent = new TransactionClosureRequestedEvent(
                transaction.getTransactionId()
        );

        TransactionClosureRequestedEvent spyEvent = Mockito.spy(transactionClosureRequestedEvent);
        Mockito.when(spyEvent.getCreationDate()).thenReturn(fixedEventTime.toString());

        Transaction expected = getTransaction(transaction);
        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId()))
                .thenReturn(Mono.just(transaction));
        Mockito.when(transactionsViewRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(closureRequestedProjectionHandler.handle(spyEvent))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldHandleProjectionWithoutSavingWhenViewUpdateDisabled() {
        closureRequestedProjectionHandler = new ClosureRequestedProjectionHandler(
                transactionsViewRepository,
                false
        );

        Transaction transaction = TransactionTestUtils
                .transactionDocument(TransactionStatusDto.AUTHORIZATION_COMPLETED, ZonedDateTime.now());

        ZonedDateTime fixedEventTime = ZonedDateTime.of(2025, 7, 25, 14, 47, 31, 0, ZoneId.of("Europe/Rome"));

        TransactionClosureRequestedEvent transactionClosureRequestedEvent = new TransactionClosureRequestedEvent(
                transaction.getTransactionId()
        );

        TransactionClosureRequestedEvent spyEvent = Mockito.spy(transactionClosureRequestedEvent);
        Mockito.when(spyEvent.getCreationDate()).thenReturn(fixedEventTime.toString());

        Transaction expected = getTransaction(transaction);
        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId()))
                .thenReturn(Mono.just(transaction));
        Mockito.when(transactionsViewRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(closureRequestedProjectionHandler.handle(spyEvent))
                .expectNext(expected)
                .verifyComplete();
        Mockito.verify(transactionsViewRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void shouldReturnTransactionNotFoundExceptionOnTransactionNotFound() {
        closureRequestedProjectionHandler = new ClosureRequestedProjectionHandler(
                transactionsViewRepository,
                true
        );
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

    @Test
    void shouldReturnTransactionNotFoundExceptionOnTransactionNotFoundWithoutSavingWhenViewUpdateDisabled() {
        closureRequestedProjectionHandler = new ClosureRequestedProjectionHandler(
                transactionsViewRepository,
                false
        );
        Transaction transaction = TransactionTestUtils
                .transactionDocument(TransactionStatusDto.AUTHORIZATION_COMPLETED, ZonedDateTime.now());

        TransactionClosureRequestedEvent transactionClosureRequestedEvent = new TransactionClosureRequestedEvent(
                transaction.getTransactionId()
        );

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId())).thenReturn(Mono.empty());

        StepVerifier.create(closureRequestedProjectionHandler.handle(transactionClosureRequestedEvent))
                .expectError(TransactionNotFoundException.class)
                .verify();

        Mockito.verify(transactionsViewRepository, Mockito.never()).save(Mockito.any());

    }

    private static Transaction getTransaction(Transaction transaction) {
        ZonedDateTime fixedEventTime = ZonedDateTime.of(2025, 7, 25, 14, 47, 31, 0, ZoneId.of("Europe/Rome"));

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
                transaction.getPspId(),
                fixedEventTime.toInstant().toEpochMilli()
        );
    }
}
