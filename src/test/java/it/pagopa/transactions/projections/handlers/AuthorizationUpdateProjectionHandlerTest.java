package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedEvent;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManager;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.OutcomeVposGatewayDto;
import it.pagopa.generated.transactions.server.model.OutcomeXpayGatewayDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class AuthorizationUpdateProjectionHandlerTest {

    @InjectMocks
    private AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandler;

    @Mock
    private TransactionsViewRepository viewRepository;

    @Mock
    private ConfidentialDataManager confidentialDataManager;

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

        TransactionAuthorizationCompletedData statusAuthCompleted = new TransactionAuthorizationCompletedData(
                ((OutcomeXpayGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getAuthorizationCode(),
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
                transaction.getTransactionActivatedData().getIdCart()
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

        TransactionAuthorizationCompletedData statusAuthCompleted = new TransactionAuthorizationCompletedData(
                ((OutcomeVposGatewayDto) updateAuthorizationRequest.getOutcomeGateway()).getAuthorizationCode(),
                "rrn",
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
                transaction.getTransactionActivatedData().getIdCart()
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
