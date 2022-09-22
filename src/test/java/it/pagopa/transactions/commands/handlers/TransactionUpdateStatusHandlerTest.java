package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.transactions.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.UpdateTransactionStatusRequestDto;
import it.pagopa.transactions.commands.TransactionUpdateStatusCommand;
import it.pagopa.transactions.commands.data.UpdateTransactionStatusData;
import it.pagopa.transactions.documents.TransactionStatusUpdateData;
import it.pagopa.transactions.documents.TransactionStatusUpdatedEvent;
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
class TransactionUpdateStatusHandlerTest {

    @InjectMocks
    private TransactionUpdateStatusHandler updateStatusHandler;
    @Mock
    private TransactionsEventStoreRepository<TransactionStatusUpdateData> transactionEventStoreRepository;

    private TransactionId transactionId = new TransactionId(UUID.randomUUID());

    @Test
    void shouldSaveSuccessfulUpdate() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("rptId");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);

        TransactionInitialized transaction = new TransactionInitialized(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                TransactionStatusDto.CLOSED);

        UpdateTransactionStatusRequestDto updateTransactionRequest = new UpdateTransactionStatusRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        UpdateTransactionStatusData updateTransactionStatusData = new UpdateTransactionStatusData(
                transaction,
                updateTransactionRequest);

        TransactionUpdateStatusCommand requestAuthorizationCommand = new TransactionUpdateStatusCommand(
                transaction.getRptId(), updateTransactionStatusData);

        TransactionStatusUpdateData transactionAuthorizationStatusUpdateData = new TransactionStatusUpdateData(
                AuthorizationResultDto.OK, TransactionStatusDto.AUTHORIZED);

        TransactionStatusUpdatedEvent event = new TransactionStatusUpdatedEvent(
                transactionId.toString(),
                transaction.getRptId().toString(),
                transaction.getPaymentToken().toString(),
                transactionAuthorizationStatusUpdateData);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));

        /* test */
        StepVerifier.create(updateStatusHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(transacttionStatusUpdatedEvent -> transacttionStatusUpdatedEvent
                        .equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(argThat(eventArg -> eventArg
                .getData().getNewTransactionStatus().equals(TransactionStatusDto.NOTIFIED)));
    }

    @Test
    void shouldSaveKoUpdate() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("rptId");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);

        TransactionInitialized transaction = new TransactionInitialized(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                TransactionStatusDto.CLOSED);

        UpdateTransactionStatusRequestDto updateTransactionRequest = new UpdateTransactionStatusRequestDto()
                .authorizationResult(AuthorizationResultDto.KO)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        UpdateTransactionStatusData updateTransactionStatusData = new UpdateTransactionStatusData(
                transaction,
                updateTransactionRequest);

        TransactionUpdateStatusCommand requestAuthorizationCommand = new TransactionUpdateStatusCommand(
                transaction.getRptId(), updateTransactionStatusData);

        TransactionStatusUpdateData transactionAuthorizationStatusUpdateData = new TransactionStatusUpdateData(
                AuthorizationResultDto.KO, TransactionStatusDto.CLOSED);

        TransactionStatusUpdatedEvent event = new TransactionStatusUpdatedEvent(
                transactionId.toString(),
                transaction.getRptId().toString(),
                transaction.getPaymentToken().toString(),
                transactionAuthorizationStatusUpdateData);

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));

        /* test */
        StepVerifier.create(updateStatusHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(transacttionStatusUpdatedEvent -> transacttionStatusUpdatedEvent
                        .equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(argThat(eventArg -> eventArg
                .getData().getNewTransactionStatus().equals(TransactionStatusDto.NOTIFIED_FAILED)));
    }

    @Test
    void shouldRejectTransactionInInvalidState() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("rptId");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);

        TransactionInitialized transaction = new TransactionInitialized(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                TransactionStatusDto.INITIALIZED);

        UpdateTransactionStatusRequestDto updateStatusRequest = new UpdateTransactionStatusRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        UpdateTransactionStatusData updateAuthorizationStatusData = new UpdateTransactionStatusData(
                transaction,
                updateStatusRequest);

        TransactionUpdateStatusCommand requestStatusCommand = new TransactionUpdateStatusCommand(
                transaction.getRptId(), updateAuthorizationStatusData);

        /* test */
        StepVerifier.create(updateStatusHandler.handle(requestStatusCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }
}
