package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
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

import java.time.ZonedDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class ClosureSendProjectionHandlerTests {
    @Mock
    private TransactionsViewRepository transactionsViewRepository;

    @InjectMocks
    private ClosureSendProjectionHandler closureSendProjectionHandler;

    private String transactionId = TransactionTestUtils.TRANSACTION_ID;

    @Test
    void shouldHandleProjectionForClosedEvent() {
        Transaction transaction = transactionDocument();

        TransactionClosedEvent event = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);

        Transaction expected = new Transaction(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getFeeTotal(),
                transaction.getEmail(),
                TransactionStatusDto.CLOSED,
                Transaction.ClientId.CHECKOUT,
                transaction.getCreationDate()
        );

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId()))
                .thenReturn(Mono.just(transaction));
        Mockito.when(transactionsViewRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(closureSendProjectionHandler.handle(event))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldHandleProjectionForClosureFailedEvent() {
        Transaction transaction = transactionDocument();

        TransactionClosureFailedEvent event = TransactionTestUtils
                .transactionClosureFailedEvent(TransactionClosureData.Outcome.OK);

        Transaction expected = new Transaction(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getFeeTotal(),
                transaction.getEmail(),
                TransactionStatusDto.UNAUTHORIZED,
                Transaction.ClientId.CHECKOUT,
                transaction.getCreationDate()
        );

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId()))
                .thenReturn(Mono.just(transaction));
        Mockito.when(transactionsViewRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(closureSendProjectionHandler.handle(event))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldThrowExceptionHandlingProjectionForUnexpectedEvent() {
        Transaction transaction = transactionDocument();

        TransactionUserCanceledEvent event = TransactionTestUtils.transactionUserCanceledEvent();

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId()))
                .thenReturn(Mono.just(transaction));

        StepVerifier.create(closureSendProjectionHandler.handle(event))
                .expectErrorMatches(t -> t instanceof IllegalArgumentException);
    }

    @Test
    void shouldThrowTransactionNotFoundExceptionForUnknownTransactionId() {
        Transaction transaction = transactionDocument();

        TransactionUserCanceledEvent event = TransactionTestUtils.transactionUserCanceledEvent();

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId()))
                .thenReturn(Mono.empty());

        StepVerifier.create(closureSendProjectionHandler.handle(event))
                .expectErrorMatches(t -> t instanceof TransactionNotFoundException);
    }

    @NotNull
    private Transaction transactionDocument() {
        return new Transaction(
                transactionId,
                List.of(
                        new PaymentNotice(
                                "paymentToken",
                                "77777777777302016723749670035",
                                "description",
                                100,
                                null
                        )
                ),
                0,
                "foo@example.com",
                TransactionStatusDto.AUTHORIZATION_COMPLETED,
                Transaction.ClientId.CHECKOUT,
                ZonedDateTime.now().toString()
        );
    }
}
