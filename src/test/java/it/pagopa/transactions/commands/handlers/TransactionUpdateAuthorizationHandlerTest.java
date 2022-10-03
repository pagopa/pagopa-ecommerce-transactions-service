package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.transactions.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;
import it.pagopa.transactions.documents.TransactionAuthorizationStatusUpdateData;
import it.pagopa.transactions.documents.TransactionAuthorizationStatusUpdatedEvent;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class TransactionUpdateAuthorizationHandlerTest {

    @InjectMocks
    private TransactionUpdateAuthorizationHandler updateAuthorizationHandler;
    @Mock
    private TransactionsEventStoreRepository<TransactionAuthorizationStatusUpdateData> transactionEventStoreRepository;

    private TransactionId transactionId = new TransactionId(UUID.randomUUID());

    @Test
    void shouldSaveSuccessfulUpdate() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("rptId");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                email,
                TransactionStatusDto.AUTHORIZATION_REQUESTED
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction,
                updateAuthorizationRequest
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(transaction.getRptId(), updateAuthorizationStatusData);

        TransactionAuthorizationStatusUpdateData transactionAuthorizationStatusUpdateData = new TransactionAuthorizationStatusUpdateData(AuthorizationResultDto.KO, TransactionStatusDto.AUTHORIZED);

        TransactionAuthorizationStatusUpdatedEvent event = new TransactionAuthorizationStatusUpdatedEvent(
                transactionId.toString(),
                transaction.getRptId().toString(),
                transaction.getTransactionActivatedData().getPaymentToken(),
                transactionAuthorizationStatusUpdateData
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));

        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(authorizationStatusUpdatedEvent -> authorizationStatusUpdatedEvent.equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(argThat(eventArg -> eventArg.getData().getNewTransactionStatus().equals(TransactionStatusDto.AUTHORIZED)));
    }

    @Test
    void shouldRejectTransactionInInvalidState() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("rptId");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                email,
                TransactionStatusDto.ACTIVATED
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction,
                updateAuthorizationRequest
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(transaction.getRptId(), updateAuthorizationStatusData);

        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldSetTransactionStatusToAuthorizationFailedOnGatewayKO() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("rptId");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                email,
                TransactionStatusDto.AUTHORIZATION_REQUESTED
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(AuthorizationResultDto.KO)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction,
                updateAuthorizationRequest
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(transaction.getRptId(), updateAuthorizationStatusData);

        TransactionAuthorizationStatusUpdateData transactionAuthorizationStatusUpdateData = new TransactionAuthorizationStatusUpdateData(AuthorizationResultDto.KO, TransactionStatusDto.AUTHORIZATION_FAILED);

        TransactionAuthorizationStatusUpdatedEvent event = new TransactionAuthorizationStatusUpdatedEvent(
                transactionId.toString(),
                transaction.getRptId().toString(),
                transaction.getTransactionActivatedData().getPaymentToken(),
                transactionAuthorizationStatusUpdateData
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));

        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(authorizationStatusUpdatedEvent -> authorizationStatusUpdatedEvent.equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(argThat(eventArg -> eventArg.getData().getNewTransactionStatus().equals(TransactionStatusDto.AUTHORIZATION_FAILED)));
    }
}
