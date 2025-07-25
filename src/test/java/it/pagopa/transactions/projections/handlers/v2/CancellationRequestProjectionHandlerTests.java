package it.pagopa.transactions.projections.handlers.v2;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.TransactionUserCanceledEvent;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.ZonedDateTime;

import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
public class CancellationRequestProjectionHandlerTests {

    private CancellationRequestProjectionHandler cancellationRequestProjectionHandler;

    @Mock
    private TransactionsViewRepository transactionsViewRepository;

    @Test
    void shouldHandleProjection() {
        cancellationRequestProjectionHandler = new CancellationRequestProjectionHandler(
                transactionsViewRepository,
                true
        );

        Transaction transaction = TransactionTestUtils
                .transactionDocument(TransactionStatusDto.ACTIVATED, ZonedDateTime.now());

        TransactionUserCanceledEvent transactionUserCanceledEvent = new TransactionUserCanceledEvent(
                transaction.getTransactionId()
        );

        Transaction expected = getExpected(transaction);

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId()))
                .thenReturn(Mono.just(transaction));
        Mockito.when(transactionsViewRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(cancellationRequestProjectionHandler.handle(transactionUserCanceledEvent))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldNotSaveWhenViewUpdateDisabled() {
        cancellationRequestProjectionHandler = new CancellationRequestProjectionHandler(
                transactionsViewRepository,
                false
        );

        Transaction transaction = TransactionTestUtils
                .transactionDocument(TransactionStatusDto.ACTIVATED, ZonedDateTime.now());

        TransactionUserCanceledEvent transactionUserCanceledEvent = new TransactionUserCanceledEvent(
                transaction.getTransactionId()
        );

        Transaction expected = getExpected(transaction);

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId()))
                .thenReturn(Mono.just(transaction));

        // Notare che non facciamo il mock di save perché non dovrebbe essere chiamato

        StepVerifier.create(cancellationRequestProjectionHandler.handle(transactionUserCanceledEvent))
                .expectNext(expected)
                .verifyComplete();

        Mockito.verify(transactionsViewRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void shouldReturnTransactionNotFoundExceptionOnTransactionNotFound() {

        cancellationRequestProjectionHandler = new CancellationRequestProjectionHandler(
                transactionsViewRepository,
                true
        );
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

    private static Transaction getExpected(Transaction transaction) {
        return new Transaction(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getFeeTotal(),
                transaction.getEmail(),
                TransactionStatusDto.CANCELLATION_REQUESTED,
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
