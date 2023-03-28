package it.pagopa.transactions.projections.handlers;

import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserReceiptAddErrorEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserReceiptAddedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserReceiptData;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.ZonedDateTime;

import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class TransactionUserReceiptProjectionHandlerTest {

    @InjectMocks
    private TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandler;

    @Mock
    private TransactionsViewRepository viewRepository;

    @Test
    void shouldHandleTransactionWithOKOutcome() {
        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());

        it.pagopa.ecommerce.commons.documents.v1.Transaction expectedDocument = new it.pagopa.ecommerce.commons.documents.v1.Transaction(
                transaction.getTransactionId().value().toString(),
                transaction.getTransactionActivatedData().getPaymentNotices(),
                null,
                transaction.getEmail(),
                TransactionStatusDto.NOTIFIED_OK,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                transaction.getCreationDate().toString()
        );

        TransactionUserReceiptAddedEvent event = new TransactionUserReceiptAddedEvent(
                transaction.getTransactionId().value().toString(),
                new TransactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
        );

        /*
         * Preconditions
         */
        Mockito.when(viewRepository.findById(transaction.getTransactionId().value().toString()))
                .thenReturn(Mono.just(it.pagopa.ecommerce.commons.documents.v1.Transaction.from(transaction)));

        Mockito.when(viewRepository.save(expectedDocument)).thenReturn(Mono.just(expectedDocument));
        Hooks.onOperatorDebug();
        /*
         * Test
         */
        StepVerifier.create(transactionUserReceiptProjectionHandler.handle(Either.right(Mono.just(event))))
                .expectNext(expectedDocument)
                .verifyComplete();

        /*
         * Assertions
         */
        Mockito.verify(viewRepository, Mockito.times(1))
                .save(
                        argThat(
                                savedTransaction -> savedTransaction.getStatus()
                                        .equals(TransactionStatusDto.NOTIFIED_OK)
                        )
                );
    }

    @Test
    void shouldHandleTransactionWithKOOutcome() {
        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());

        it.pagopa.ecommerce.commons.documents.v1.Transaction expectedDocument = new it.pagopa.ecommerce.commons.documents.v1.Transaction(
                transaction.getTransactionId().value().toString(),
                transaction.getTransactionActivatedData().getPaymentNotices(),
                null,
                transaction.getEmail(),
                TransactionStatusDto.REFUND_REQUESTED,
                transaction.getClientId(),
                transaction.getCreationDate().toString()
        );

        TransactionUserReceiptAddedEvent event = TransactionTestUtils
                .transactionUserReceiptAddedEvent(TransactionUserReceiptData.Outcome.KO);

        /*
         * Preconditions
         */
        Mockito.when(viewRepository.findById(transaction.getTransactionId().value().toString()))
                .thenReturn(Mono.just(it.pagopa.ecommerce.commons.documents.v1.Transaction.from(transaction)));

        Mockito.when(viewRepository.save(expectedDocument)).thenReturn(Mono.just(expectedDocument));

        /*
         * Test
         */
        StepVerifier.create(transactionUserReceiptProjectionHandler.handle(Either.right(Mono.just(event))))
                .expectNext(expectedDocument)
                .verifyComplete();

        /*
         * Assertions
         */
        Mockito.verify(viewRepository, Mockito.times(1))
                .save(
                        argThat(
                                savedTransaction -> savedTransaction.getStatus()
                                        .equals(TransactionStatusDto.REFUND_REQUESTED)
                        )
                );
    }

    @Test
    void shouldHandleTransactionWithNotificationErrorOutcomeOK() {
        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());

        it.pagopa.ecommerce.commons.documents.v1.Transaction expectedDocument = new it.pagopa.ecommerce.commons.documents.v1.Transaction(
                transaction.getTransactionId().value().toString(),
                transaction.getTransactionActivatedData().getPaymentNotices(),
                null,
                transaction.getEmail(),
                TransactionStatusDto.NOTIFICATION_ERROR,
                transaction.getClientId(),
                transaction.getCreationDate().toString()
        );

        TransactionUserReceiptAddErrorEvent event = TransactionTestUtils
                .transactionUserReceiptAddErrorEvent(TransactionUserReceiptData.Outcome.OK);

        /*
         * Preconditions
         */
        Mockito.when(viewRepository.findById(transaction.getTransactionId().value().toString()))
                .thenReturn(Mono.just(it.pagopa.ecommerce.commons.documents.v1.Transaction.from(transaction)));

        Mockito.when(viewRepository.save(expectedDocument)).thenReturn(Mono.just(expectedDocument));

        /*
         * Test
         */
        StepVerifier.create(transactionUserReceiptProjectionHandler.handle(Either.left(Mono.just(event))))
                .expectNext(expectedDocument)
                .verifyComplete();

        /*
         * Assertions
         */
        Mockito.verify(viewRepository, Mockito.times(1))
                .save(
                        argThat(
                                savedTransaction -> savedTransaction.getStatus()
                                        .equals(TransactionStatusDto.NOTIFICATION_ERROR)
                        )
                );
    }

    @Test
    void shouldHandleTransactionWithNotificationErrorOutcomeKO() {
        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());

        it.pagopa.ecommerce.commons.documents.v1.Transaction expectedDocument = new it.pagopa.ecommerce.commons.documents.v1.Transaction(
                transaction.getTransactionId().value().toString(),
                transaction.getTransactionActivatedData().getPaymentNotices(),
                null,
                transaction.getEmail(),
                TransactionStatusDto.NOTIFICATION_ERROR,
                transaction.getClientId(),
                transaction.getCreationDate().toString()
        );

        TransactionUserReceiptAddErrorEvent event = TransactionTestUtils
                .transactionUserReceiptAddErrorEvent(TransactionUserReceiptData.Outcome.KO);

        /*
         * Preconditions
         */
        Mockito.when(viewRepository.findById(transaction.getTransactionId().value().toString()))
                .thenReturn(Mono.just(it.pagopa.ecommerce.commons.documents.v1.Transaction.from(transaction)));

        Mockito.when(viewRepository.save(expectedDocument)).thenReturn(Mono.just(expectedDocument));

        /*
         * Test
         */
        StepVerifier.create(transactionUserReceiptProjectionHandler.handle(Either.left(Mono.just(event))))
                .expectNext(expectedDocument)
                .verifyComplete();

        /*
         * Assertions
         */
        Mockito.verify(viewRepository, Mockito.times(1))
                .save(
                        argThat(
                                savedTransaction -> savedTransaction.getStatus()
                                        .equals(TransactionStatusDto.NOTIFICATION_ERROR)
                        )
                );
    }
}
