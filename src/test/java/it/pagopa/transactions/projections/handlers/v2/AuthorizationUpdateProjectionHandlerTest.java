package it.pagopa.transactions.projections.handlers.v2;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedEvent;
import it.pagopa.ecommerce.commons.documents.v2.activation.EmptyTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.RedirectTransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.domain.v2.TransactionActivated;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.time.ZonedDateTime;

import static org.mockito.ArgumentMatchers.argThat;

class AuthorizationUpdateProjectionHandlerTest {

    private final TransactionsViewRepository viewRepository = Mockito.mock(TransactionsViewRepository.class);

    private final int paymentTokenValidity = TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC;
    private final boolean transactionsviewUpdateEnabled = true;

    private final it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandler = new AuthorizationUpdateProjectionHandler(
            viewRepository,
            paymentTokenValidity,
            transactionsviewUpdateEnabled
    );

    private static final String expectedOperationTimeStamp = "2023-01-01T01:02:03";

    @Test
    void shouldHandleTransactionNpg() {
        AuthorizationUpdateProjectionHandler handler = new AuthorizationUpdateProjectionHandler(
                viewRepository,
                paymentTokenValidity,
                false
        );

        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());

        Transaction expectedDocument = getRrn(transaction);

        expectedDocument.setPaymentGateway(null);
        expectedDocument.setAuthorizationCode("authorizationCode");
        expectedDocument.setAuthorizationErrorCode(null);
        expectedDocument.setGatewayAuthorizationStatus("EXECUTED");

        TransactionAuthorizationCompletedData statusAuthCompleted = new TransactionAuthorizationCompletedData(
                "authorizationCode",
                "rrn",
                expectedOperationTimeStamp,
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.EXECUTED,
                        "operationId",
                        "paymentEndToEndId",
                        null,
                        null
                )

        );

        TransactionAuthorizationCompletedEvent event = new TransactionAuthorizationCompletedEvent(
                transaction.getTransactionId().value(),
                statusAuthCompleted
        );

        TransactionActivated expected = getExpected(transaction, expectedDocument);

        /*
         * Preconditions
         */
        Mockito.when(viewRepository.findById(transaction.getTransactionId().value()))
                .thenReturn(Mono.just(Transaction.from(transaction)));

        Mockito.when(viewRepository.save(expectedDocument)).thenReturn(Mono.just(expectedDocument));

        /*
         * Test
         */
        StepVerifier.create(authorizationUpdateProjectionHandler.handle(event))
                .expectNext(expected)
                .verifyComplete();

        /*
         * Assertions
         */
        Mockito.verify(viewRepository, Mockito.times(1)).save(
                argThat(
                        savedTransaction -> ((Transaction) savedTransaction).getStatus()
                                .equals(TransactionStatusDto.AUTHORIZATION_COMPLETED)
                )
        );
    }

    @Test
    void shouldHandleTransactionNpgWithoutSavingWhenViewUpdateDisabled() {
        AuthorizationUpdateProjectionHandler handler = new AuthorizationUpdateProjectionHandler(
                viewRepository,
                paymentTokenValidity,
                false
        );

        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());

        Transaction expectedDocument = getRrn(transaction);

        expectedDocument.setPaymentGateway(null);
        expectedDocument.setAuthorizationCode("authorizationCode");
        expectedDocument.setAuthorizationErrorCode(null);
        expectedDocument.setGatewayAuthorizationStatus("EXECUTED");

        TransactionAuthorizationCompletedData statusAuthCompleted = new TransactionAuthorizationCompletedData(
                "authorizationCode",
                "rrn",
                expectedOperationTimeStamp,
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.EXECUTED,
                        "operationId",
                        "paymentEndToEndId",
                        null,
                        null
                )
        );

        TransactionAuthorizationCompletedEvent event = new TransactionAuthorizationCompletedEvent(
                transaction.getTransactionId().value(),
                statusAuthCompleted
        );

        TransactionActivated expected = getExpected(transaction, expectedDocument);

        Mockito.when(viewRepository.findById(transaction.getTransactionId().value()))
                .thenReturn(Mono.just(Transaction.from(transaction)));

        StepVerifier.create(handler.handle(event))
                .expectNext(expected)
                .verifyComplete();

        Mockito.verify(viewRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void shouldHandleTransactionRedirection() {

        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());

        Transaction expectedDocument = getRrn(transaction);
        String authorizationErrorCode = "authorization error code";

        expectedDocument.setPaymentGateway(null);
        expectedDocument.setAuthorizationCode("authorizationCode");
        expectedDocument.setAuthorizationErrorCode(authorizationErrorCode);
        expectedDocument.setGatewayAuthorizationStatus("KO");

        TransactionAuthorizationCompletedData statusAuthCompleted = new TransactionAuthorizationCompletedData(
                "authorizationCode",
                "rrn",
                expectedOperationTimeStamp,
                new RedirectTransactionGatewayAuthorizationData(
                        RedirectTransactionGatewayAuthorizationData.Outcome.KO,
                        authorizationErrorCode
                )

        );

        TransactionAuthorizationCompletedEvent event = new TransactionAuthorizationCompletedEvent(
                transaction.getTransactionId().value(),
                statusAuthCompleted
        );

        TransactionActivated expected = getExpected(transaction, expectedDocument);

        /*
         * Preconditions
         */
        Mockito.when(viewRepository.findById(transaction.getTransactionId().value()))
                .thenReturn(Mono.just(Transaction.from(transaction)));

        Mockito.when(viewRepository.save(expectedDocument)).thenReturn(Mono.just(expectedDocument));

        /*
         * Test
         */
        StepVerifier.create(authorizationUpdateProjectionHandler.handle(event))
                .expectNext(expected)
                .verifyComplete();

        /*
         * Assertions
         */
        Mockito.verify(viewRepository, Mockito.times(1)).save(
                argThat(
                        savedTransaction -> ((Transaction) savedTransaction).getStatus()
                                .equals(TransactionStatusDto.AUTHORIZATION_COMPLETED)
                )
        );
    }

    @Test
    void shouldHandleTransactionRedirectionWithoutSavingWhenViewUpdateDisabled() {
        AuthorizationUpdateProjectionHandler handler = new AuthorizationUpdateProjectionHandler(
                viewRepository,
                paymentTokenValidity,
                false
        );

        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());

        Transaction expectedDocument = getRrn(transaction);
        String authorizationErrorCode = "authorization error code";

        expectedDocument.setPaymentGateway(null);
        expectedDocument.setAuthorizationCode("authorizationCode");
        expectedDocument.setAuthorizationErrorCode(authorizationErrorCode);
        expectedDocument.setGatewayAuthorizationStatus("KO");

        TransactionAuthorizationCompletedData statusAuthCompleted = new TransactionAuthorizationCompletedData(
                "authorizationCode",
                "rrn",
                expectedOperationTimeStamp,
                new RedirectTransactionGatewayAuthorizationData(
                        RedirectTransactionGatewayAuthorizationData.Outcome.KO,
                        authorizationErrorCode
                )
        );

        TransactionAuthorizationCompletedEvent event = new TransactionAuthorizationCompletedEvent(
                transaction.getTransactionId().value(),
                statusAuthCompleted
        );

        TransactionActivated expected = getExpected(transaction, expectedDocument);

        Mockito.when(viewRepository.findById(transaction.getTransactionId().value()))
                .thenReturn(Mono.just(Transaction.from(transaction)));

        StepVerifier.create(handler.handle(event))
                .expectNext(expected)
                .verifyComplete();
        Mockito.verify(viewRepository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void shouldSaveErrorCodeForNpgKOAuthorizationRequest() {

        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());

        Transaction expectedDocument = getRrn(transaction);

        expectedDocument.setPaymentGateway(null);
        expectedDocument.setAuthorizationCode("authorizationCode");
        expectedDocument.setAuthorizationErrorCode("errorCode");
        expectedDocument.setGatewayAuthorizationStatus("DECLINED");

        TransactionAuthorizationCompletedData statusAuthCompleted = new TransactionAuthorizationCompletedData(
                "authorizationCode",
                "rrn",
                expectedOperationTimeStamp,
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.DECLINED,
                        "operationId",
                        "paymentEndToEndId",
                        "errorCode",
                        null
                )

        );

        TransactionAuthorizationCompletedEvent event = new TransactionAuthorizationCompletedEvent(
                transaction.getTransactionId().value(),
                statusAuthCompleted
        );

        TransactionActivated expected = getExpected(transaction, expectedDocument);

        /*
         * Preconditions
         */
        Mockito.when(viewRepository.findById(transaction.getTransactionId().value()))
                .thenReturn(Mono.just(Transaction.from(transaction)));

        Mockito.when(viewRepository.save(expectedDocument)).thenReturn(Mono.just(expectedDocument));

        /*
         * Test
         */
        StepVerifier.create(authorizationUpdateProjectionHandler.handle(event))
                .expectNext(expected)
                .verifyComplete();

        /*
         * Assertions
         */
        Mockito.verify(viewRepository, Mockito.times(1)).save(
                argThat(
                        savedTransaction -> ((Transaction) savedTransaction).getStatus()
                                .equals(TransactionStatusDto.AUTHORIZATION_COMPLETED)
                )
        );
    }

    @Test
    void shouldNotSaveErrorCodeForNpgKOAuthorizationRequestWhenUpdateDisabled() {
        AuthorizationUpdateProjectionHandler handler = new AuthorizationUpdateProjectionHandler(
                viewRepository,
                paymentTokenValidity,
                false
        );

        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());

        Transaction expectedDocument = getRrn(transaction);

        expectedDocument.setPaymentGateway(null);
        expectedDocument.setAuthorizationCode("authorizationCode");
        expectedDocument.setAuthorizationErrorCode("errorCode");
        expectedDocument.setGatewayAuthorizationStatus("DECLINED");

        TransactionAuthorizationCompletedData statusAuthCompleted = new TransactionAuthorizationCompletedData(
                "authorizationCode",
                "rrn",
                expectedOperationTimeStamp,
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.DECLINED,
                        "operationId",
                        "paymentEndToEndId",
                        "errorCode",
                        null
                )
        );

        TransactionAuthorizationCompletedEvent event = new TransactionAuthorizationCompletedEvent(
                transaction.getTransactionId().value(),
                statusAuthCompleted
        );

        TransactionActivated expected = getExpected(transaction, expectedDocument);

        Mockito.when(viewRepository.findById(transaction.getTransactionId().value()))
                .thenReturn(Mono.just(Transaction.from(transaction)));

        StepVerifier.create(handler.handle(event))
                .expectNext(expected)
                .verifyComplete();

        Mockito.verify(viewRepository, Mockito.never()).save(Mockito.any());
    }

    private TransactionActivated getExpected(
                                             TransactionActivated transaction,
                                             Transaction expectedDocument
    ) {
        return new TransactionActivated(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                null,
                null,
                ZonedDateTime.parse(expectedDocument.getCreationDate()),
                Transaction.ClientId.CHECKOUT,
                transaction.getTransactionActivatedData().getIdCart(),
                paymentTokenValidity,
                new EmptyTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );
    }

    private static Transaction getRrn(TransactionActivated transaction) {
        return new Transaction(
                transaction.getTransactionId().value(),
                transaction.getTransactionActivatedData().getPaymentNotices(),
                null,
                transaction.getEmail(),
                TransactionStatusDto.AUTHORIZATION_COMPLETED,
                Transaction.ClientId.CHECKOUT,
                transaction.getCreationDate().toString(),
                transaction.getTransactionActivatedData().getIdCart(),
                "rrn",
                TransactionTestUtils.USER_ID,
                null,
                null
        );
    }
}
