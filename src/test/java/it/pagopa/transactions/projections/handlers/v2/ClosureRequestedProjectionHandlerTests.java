package it.pagopa.transactions.projections.handlers.v2;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.TransactionClosureRequestedEvent;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
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
public class ClosureRequestedProjectionHandlerTests {

    @InjectMocks
    private ClosureRequestedProjectionHandler closureRequestedProjectionHandler;

    @Mock
    private TransactionsViewRepository transactionsViewRepository;

    @Test
    void shouldHandleProjection() {
        Transaction transaction = TransactionTestUtils
                .transactionDocument(TransactionStatusDto.AUTHORIZATION_COMPLETED, ZonedDateTime.now());

        TransactionClosureRequestedEvent transactionClosureRequestedEvent = new TransactionClosureRequestedEvent(
                transaction.getTransactionId()
        );

        Transaction expected = new Transaction(
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
                null
        );

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId()))
                .thenReturn(Mono.just(transaction));
        Mockito.when(transactionsViewRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(closureRequestedProjectionHandler.handle(transactionClosureRequestedEvent))
                .expectNext(expected)
                .verifyComplete();
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
}
