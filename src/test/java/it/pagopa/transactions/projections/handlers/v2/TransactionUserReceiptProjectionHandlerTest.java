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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
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
        Transaction transaction = TransactionTestUtils
                .transactionDocument(TransactionStatusDto.AUTHORIZATION_COMPLETED, ZonedDateTime.now());

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
        expectedDocument.setSendPaymentResultOutcome(TransactionUserReceiptData.Outcome.OK);

        TransactionUserReceiptRequestedEvent event = TransactionTestUtils
                .transactionUserReceiptRequestedEvent(
                        TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
                );

        /*
         * Preconditions
         */
        Mockito.when(viewRepository.findById(transaction.getTransactionId()))
                .thenReturn(Mono.just(transaction));

        Mockito.when(viewRepository.save(expectedDocument)).thenReturn(Mono.just(expectedDocument));

        /*
         * Test
         */
        StepVerifier.create(transactionUserReceiptProjectionHandler.handle(event))
                .expectNext(expectedDocument)
                .verifyComplete();

        /*
         * Assertions
         */
        Mockito.verify(viewRepository, Mockito.times(1))
                .save(
                        argThat(
                                savedTransaction -> ((Transaction) savedTransaction).getStatus()
                                        .equals(TransactionStatusDto.NOTIFICATION_REQUESTED)
                        )
                );
    }

    @Test
    void shouldHandleTransactionWithKOOutcome() {
        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());

        Transaction expectedDocument = new Transaction(
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
        expectedDocument.setSendPaymentResultOutcome(TransactionUserReceiptData.Outcome.KO);

        TransactionUserReceiptRequestedEvent event = TransactionTestUtils
                .transactionUserReceiptRequestedEvent(
                        TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.KO)
                );

        /*
         * Preconditions
         */
        Mockito.when(viewRepository.findById(transaction.getTransactionId().value()))
                .thenReturn(Mono.just(Transaction.from(transaction)));

        Mockito.when(viewRepository.save(expectedDocument)).thenReturn(Mono.just(expectedDocument));

        /*
         * Test
         */
        StepVerifier.create(transactionUserReceiptProjectionHandler.handle(event))
                .expectNext(expectedDocument)
                .verifyComplete();

        /*
         * Assertions
         */
        Mockito.verify(viewRepository, Mockito.times(1))
                .save(
                        argThat(
                                savedTransaction -> ((Transaction) savedTransaction).getStatus()
                                        .equals(TransactionStatusDto.NOTIFICATION_REQUESTED)
                        )
                );
    }
}
