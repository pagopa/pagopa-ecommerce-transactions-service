package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.TransactionClosureErrorEvent;
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
class ClosureErrorProjectionHandlerTest {
    @Mock
    private TransactionsViewRepository transactionsViewRepository;

    @InjectMocks
    private ClosureErrorProjectionHandler closureErrorProjectionHandler;

    @Test
    void shouldHandleProjection() {
        Transaction transaction = TransactionTestUtils
                .transactionDocument(TransactionStatusDto.AUTHORIZATION_COMPLETED, ZonedDateTime.now());

        TransactionClosureErrorEvent closureErrorEvent = new TransactionClosureErrorEvent(
                transaction.getTransactionId()
        );

        Transaction expected = new Transaction(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getFeeTotal(),
                transaction.getEmail(),
                TransactionStatusDto.CLOSURE_ERROR,
                Transaction.ClientId.CHECKOUT,
                transaction.getCreationDate()
        );

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId()))
                .thenReturn(Mono.just(transaction));
        Mockito.when(transactionsViewRepository.save(any()))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(closureErrorProjectionHandler.handle(closureErrorEvent))
                .expectNext(expected)
                .verifyComplete();
    }

    /*
     * expectNext( Transaction(transactionId=21adff9a-3679-4704-800e-ae08465b2cb5,
     * clientId=CHECKOUT,
     * email=Confidential[confidentialMetadata=AESMetadata{salt=[36, 62, 6, -119,
     * -6, 121, -55, 83, -73, 68, 56, 32, -81, -109, -82, -71],
     * iv=javax.crypto.spec.IvParameterSpec@5568c66f},
     * opaqueData=6z0M1JlS6m2XZKU2ryfSiwkPStx+e30eeH/0l/8vLeEhhBXch0JQ4i0kYiN0asY=],
     * status=CLOSURE_ERROR, feeTotal=null,
     * creationDate=2023-02-22T10:28:02.595734+01:00[Europe/Rome],
     * paymentNotices=[PaymentNotice(paymentToken=paymentToken,
     * rptId=77777777777111111111111111111, description=description, amount=100,
     * paymentContextCode=paymentContextCode)]))" failed (expected value:
     * Transaction(transactionId=21adff9a-3679-4704-800e-ae08465b2cb5,
     * clientId=CHECKOUT,
     * email=Confidential[confidentialMetadata=AESMetadata{salt=[36, 62, 6, -119,
     * -6, 121, -55, 83, -73, 68, 56, 32, -81, -109, -82, -71],
     * iv=javax.crypto.spec.IvParameterSpec@5568c66f},
     * opaqueData=6z0M1JlS6m2XZKU2ryfSiwkPStx+e30eeH/0l/8vLeEhhBXch0JQ4i0kYiN0asY=],
     * status=CLOSURE_ERROR, feeTotal=null,
     * creationDate=2023-02-22T10:28:02.595734+01:00[Europe/Rome],
     * paymentNotices=[PaymentNotice(paymentToken=paymentToken,
     * rptId=77777777777111111111111111111, description=description, amount=100,
     * paymentContextCode=paymentContextCode)]); actual value:
     * Transaction(transactionId=21adff9a-3679-4704-800e-ae08465b2cb5,
     * clientId=UNKNOWN,
     * email=Confidential[confidentialMetadata=AESMetadata{salt=[36, 62, 6, -119,
     * -6, 121, -55, 83, -73, 68, 56, 32, -81, -109, -82, -71],
     * iv=javax.crypto.spec.IvParameterSpec@5568c66f},
     * opaqueData=6z0M1JlS6m2XZKU2ryfSiwkPStx+e30eeH/0l/8vLeEhhBXch0JQ4i0kYiN0asY=],
     * status=CLOSURE_ERROR, feeTotal=null,
     * creationDate=2023-02-22T10:28:02.595734+01:00[Europe/Rome],
     * paymentNotices=[PaymentNotice(paymentToken=paymentToken,
     * rptId=77777777777111111111111111111, description=description, amount=100,
     * paymentContextCode=paymentContextCode)]))
     */

    @Test
    void shouldReturnTransactionNotFoundExceptionOnTransactionNotFound() {
        Transaction transaction = TransactionTestUtils
                .transactionDocument(TransactionStatusDto.AUTHORIZATION_COMPLETED, ZonedDateTime.now());

        TransactionClosureErrorEvent closureErrorEvent = new TransactionClosureErrorEvent(
                transaction.getTransactionId()
        );

        Mockito.when(transactionsViewRepository.findById(transaction.getTransactionId())).thenReturn(Mono.empty());

        StepVerifier.create(closureErrorProjectionHandler.handle(closureErrorEvent))
                .expectError(TransactionNotFoundException.class)
                .verify();
    }

}
