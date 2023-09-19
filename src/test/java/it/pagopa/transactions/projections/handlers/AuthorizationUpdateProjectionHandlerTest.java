package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedEvent;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.OutcomeVposGatewayDto;
import it.pagopa.generated.transactions.server.model.OutcomeXpayGatewayDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

import static org.mockito.ArgumentMatchers.argThat;

class AuthorizationUpdateProjectionHandlerTest {

    private final TransactionsViewRepository viewRepository = Mockito.mock(TransactionsViewRepository.class);

    private final int paymentTokenValidity = TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC;

    private final AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandler = new AuthorizationUpdateProjectionHandler(
            viewRepository,
            paymentTokenValidity
    );

    private static final String expectedOperationTimestamp = "2023-01-01T01:02:03";

    @Test
    void shouldHandleTransactionXpay() {
        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());

        it.pagopa.ecommerce.commons.documents.v1.Transaction expectedDocument = new it.pagopa.ecommerce.commons.documents.v1.Transaction(
                transaction.getTransactionId().value(),
                transaction.getTransactionActivatedData().getPaymentNotices(),
                null,
                transaction.getEmail(),
                TransactionStatusDto.AUTHORIZATION_COMPLETED,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                transaction.getCreationDate().toString(),
                transaction.getTransactionActivatedData().getIdCart(),
                null
        );

        expectedDocument.setPaymentGateway(null);
        expectedDocument.setAuthorizationCode("authorizationCode");
        expectedDocument.setAuthorizationErrorCode(null);

        TransactionAuthorizationCompletedData statusAuthCompleted = new TransactionAuthorizationCompletedData(
                ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getAuthorizationCode(),
                null,
                expectedOperationTimestamp,
                null,
                it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto
                        .fromValue(
                                ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getOutcome()
                                        .toString()
                        )
        );

        TransactionAuthorizationCompletedEvent event = new TransactionAuthorizationCompletedEvent(
                transaction.getTransactionId().value(),
                statusAuthCompleted
        );

        TransactionActivated expected = new TransactionActivated(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                null,
                null,
                ZonedDateTime.parse(expectedDocument.getCreationDate()),
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                transaction.getTransactionActivatedData().getIdCart(),
                paymentTokenValidity
        );

        /*
         * Preconditions
         */
        Mockito.when(viewRepository.findById(transaction.getTransactionId().value()))
                .thenReturn(Mono.just(it.pagopa.ecommerce.commons.documents.v1.Transaction.from(transaction)));

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
                        savedTransaction -> savedTransaction.getStatus()
                                .equals(TransactionStatusDto.AUTHORIZATION_COMPLETED)
                )
        );
    }

    @Test
    void shouldHandleTransactionVpos() {
        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeVposGatewayDto()
                                .outcome(OutcomeVposGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());

        it.pagopa.ecommerce.commons.documents.v1.Transaction expectedDocument = new it.pagopa.ecommerce.commons.documents.v1.Transaction(
                transaction.getTransactionId().value(),
                transaction.getTransactionActivatedData().getPaymentNotices(),
                null,
                transaction.getEmail(),
                TransactionStatusDto.AUTHORIZATION_COMPLETED,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                transaction.getCreationDate().toString(),
                transaction.getTransactionActivatedData().getIdCart(),
                "rrn"
        );

        expectedDocument.setPaymentGateway(null);
        expectedDocument.setAuthorizationCode("authorizationCode");
        expectedDocument.setAuthorizationErrorCode(null);

        TransactionAuthorizationCompletedData statusAuthCompleted = new TransactionAuthorizationCompletedData(
                ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getAuthorizationCode(),
                "rrn",
                expectedOperationTimestamp,
                null,
                it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto
                        .fromValue(
                                ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getOutcome()
                                        .toString()
                        )
        );

        TransactionAuthorizationCompletedEvent event = new TransactionAuthorizationCompletedEvent(
                transaction.getTransactionId().value(),
                statusAuthCompleted
        );

        TransactionActivated expected = new TransactionActivated(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                null,
                null,
                ZonedDateTime.parse(expectedDocument.getCreationDate()),
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                transaction.getTransactionActivatedData().getIdCart(),
                paymentTokenValidity
        );

        /*
         * Preconditions
         */
        Mockito.when(viewRepository.findById(transaction.getTransactionId().value()))
                .thenReturn(Mono.just(it.pagopa.ecommerce.commons.documents.v1.Transaction.from(transaction)));

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
                        savedTransaction -> savedTransaction.getStatus()
                                .equals(TransactionStatusDto.AUTHORIZATION_COMPLETED)
                )
        );
    }
}
