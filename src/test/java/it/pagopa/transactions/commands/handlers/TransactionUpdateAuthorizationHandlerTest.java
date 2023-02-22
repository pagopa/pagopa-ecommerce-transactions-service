package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestedEvent;
import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class TransactionUpdateAuthorizationHandlerTest {

    @InjectMocks
    private TransactionUpdateAuthorizationHandler updateAuthorizationHandler;
    @Mock
    private TransactionsEventStoreRepository<TransactionAuthorizationCompletedData> transactionEventStoreRepository;

    private TransactionId transactionId = new TransactionId(UUID.fromString(TransactionTestUtils.TRANSACTION_ID));

    @Test
    void shouldSaveSuccessfulUpdate() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                Arrays.asList(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode
                        )
                ),
                email,
                faultCode,
                faultCodeString,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction,
                updateAuthorizationRequest
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                updateAuthorizationStatusData
        );

        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent event = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(AuthorizationResultDto.OK);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));

        Mockito.when(transactionEventStoreRepository.findByTransactionId(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(activatedEvent, authorizationRequestedEvent));

        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(authorizationStatusUpdatedEvent -> authorizationStatusUpdatedEvent.equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1))
                .save(
                        argThat(
                                eventArg -> TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT
                                        .equals(eventArg.getEventCode())
                        )
                );
    }

    @Test
    void shouldRejectTransactionInInvalidState() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode
                        )
                ),
                email,
                faultCode,
                faultCodeString,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction,
                updateAuthorizationRequest
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                updateAuthorizationStatusData
        );
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent transactionAuthorizationCompletedEvent = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(AuthorizationResultDto.OK);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(
                (Flux) Flux.just(activatedEvent, authorizationRequestedEvent, transactionAuthorizationCompletedEvent)
        );

        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldSetTransactionStatusToAuthorizationFailedOnGatewayKO() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new it.pagopa.ecommerce.commons.domain.v1.PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode
                        )
                ),
                email,
                faultCode,
                faultCodeString,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.KO)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction,
                updateAuthorizationRequest
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                updateAuthorizationStatusData
        );

        TransactionAuthorizationCompletedData transactionAuthorizationCompletedData = new TransactionAuthorizationCompletedData(
                "authorizationCode",
                AuthorizationResultDto.KO
        );

        TransactionAuthorizationCompletedEvent event = new TransactionAuthorizationCompletedEvent(
                transactionId.toString(),
                transactionAuthorizationCompletedData
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent transactionAuthorizationCompletedEvent = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(AuthorizationResultDto.OK);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.findByTransactionId(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(activatedEvent, authorizationRequestedEvent));

        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(authorizationStatusUpdatedEvent -> authorizationStatusUpdatedEvent.equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
    }
}
