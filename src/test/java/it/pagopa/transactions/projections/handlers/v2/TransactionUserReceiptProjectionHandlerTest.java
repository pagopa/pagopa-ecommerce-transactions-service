package it.pagopa.transactions.projections.handlers.v2;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptRequestedEvent;
import it.pagopa.ecommerce.commons.domain.v2.TransactionActivated;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.ZonedDateTime;

import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class TransactionUserReceiptProjectionHandlerTest {

    private TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandler;

    @Mock
    private TransactionsViewRepository transactionsViewRepository;

    @Test
    void shouldHandleTransactionWithOKOutcome() {

        transactionUserReceiptProjectionHandler = new TransactionUserReceiptProjectionHandler(
                transactionsViewRepository,
                true
        );
        Transaction transaction = TransactionTestUtils
                .transactionDocument(TransactionStatusDto.AUTHORIZATION_COMPLETED, ZonedDateTime.now());

        Transaction expectedDocument = getTransaction(transaction);
        expectedDocument.setSendPaymentResultOutcome(TransactionUserReceiptData.Outcome.OK);

        TransactionUserReceiptRequestedEvent event = TransactionTestUtils
                .transactionUserReceiptRequestedEvent(
                        TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
                );

        /*
         * Preconditions
         */
        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId()))
                .thenReturn(Mono.just(transaction));

        Mockito.when(transactionsViewRepository.save(expectedDocument)).thenReturn(Mono.just(expectedDocument));

        /*
         * Test
         */
        StepVerifier.create(transactionUserReceiptProjectionHandler.handle(event))
                .expectNext(expectedDocument)
                .verifyComplete();

        /*
         * Assertions
         */
        Mockito.verify(transactionsViewRepository, Mockito.times(1))
                .save(
                        argThat(
                                savedTransaction -> ((Transaction) savedTransaction).getStatus()
                                        .equals(TransactionStatusDto.NOTIFICATION_REQUESTED)
                        )
                );
    }

    @Test
    void shouldHandleTransactionWithOKOutcomeWithoutSavingWhenViewUpdateDisabled() {

        transactionUserReceiptProjectionHandler = new TransactionUserReceiptProjectionHandler(
                transactionsViewRepository,
                false // flag disabilitato
        );

        Transaction transaction = TransactionTestUtils
                .transactionDocument(TransactionStatusDto.AUTHORIZATION_COMPLETED, ZonedDateTime.now());

        Transaction expectedDocument = getTransaction(transaction);
        expectedDocument.setSendPaymentResultOutcome(TransactionUserReceiptData.Outcome.OK);

        TransactionUserReceiptRequestedEvent event = TransactionTestUtils
                .transactionUserReceiptRequestedEvent(
                        TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
                );

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId()))
                .thenReturn(Mono.just(transaction));

        StepVerifier.create(transactionUserReceiptProjectionHandler.handle(event))
                .expectNext(expectedDocument)
                .verifyComplete();

        Mockito.verify(transactionsViewRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void shouldHandleTransactionWithKOOutcome() {
        transactionUserReceiptProjectionHandler = new TransactionUserReceiptProjectionHandler(
                transactionsViewRepository,
                true
        );

        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());

        Transaction expectedDocument = getTransaction(transaction);
        expectedDocument.setSendPaymentResultOutcome(TransactionUserReceiptData.Outcome.KO);

        TransactionUserReceiptRequestedEvent event = TransactionTestUtils
                .transactionUserReceiptRequestedEvent(
                        TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.KO)
                );

        /*
         * Preconditions
         */
        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId().value()))
                .thenReturn(Mono.just(Transaction.from(transaction)));

        Mockito.when(transactionsViewRepository.save(expectedDocument)).thenReturn(Mono.just(expectedDocument));

        /*
         * Test
         */
        StepVerifier.create(transactionUserReceiptProjectionHandler.handle(event))
                .expectNext(expectedDocument)
                .verifyComplete();

        /*
         * Assertions
         */
        Mockito.verify(transactionsViewRepository, Mockito.times(1))
                .save(
                        argThat(
                                savedTransaction -> ((Transaction) savedTransaction).getStatus()
                                        .equals(TransactionStatusDto.NOTIFICATION_REQUESTED)
                        )
                );
    }

    @Test
    void shouldHandleTransactionWithKOOutcomeWithoutSavingWhenViewUpdateDisabled() {
        transactionUserReceiptProjectionHandler = new TransactionUserReceiptProjectionHandler(
                transactionsViewRepository,
                false // flag disabilitato
        );

        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());

        Transaction expectedDocument = getTransaction(transaction);
        expectedDocument.setSendPaymentResultOutcome(TransactionUserReceiptData.Outcome.KO);

        TransactionUserReceiptRequestedEvent event = TransactionTestUtils
                .transactionUserReceiptRequestedEvent(
                        TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.KO)
                );

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId().value()))
                .thenReturn(Mono.just(Transaction.from(transaction)));

        StepVerifier.create(transactionUserReceiptProjectionHandler.handle(event))
                .expectNext(expectedDocument)
                .verifyComplete();

        Mockito.verify(transactionsViewRepository, Mockito.never()).save(Mockito.any());
    }

    private static Transaction getTransaction(TransactionActivated transaction) {
        return new Transaction(
                transaction.getTransactionId().value(),
                transaction.getTransactionActivatedData().getPaymentNotices(),
                null,
                transaction.getEmail(),
                TransactionStatusDto.NOTIFICATION_REQUESTED,
                transaction.getClientId(),
                transaction.getCreationDate().toString(),
                transaction.getTransactionActivatedData().getIdCart(),
                null,
                TransactionTestUtils.USER_ID,
                null,
                null
        );
    }

    private static Transaction getTransaction(Transaction transaction) {
        Transaction expectedDocument = new Transaction(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                null,
                transaction.getEmail(),
                TransactionStatusDto.NOTIFICATION_REQUESTED,
                Transaction.ClientId.CHECKOUT,
                transaction.getCreationDate(),
                transaction.getIdCart(),
                "rrn",
                TransactionTestUtils.USER_ID,
                null,
                null
        );
        return expectedDocument;
    }

}
