package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentResponseDto;
import it.pagopa.generated.notifications.v1.dto.NotificationEmailResponseDto;
import it.pagopa.generated.transactions.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.generated.transactions.server.model.UpdateTransactionStatusRequestDto;
import it.pagopa.transactions.client.NotificationsServiceClient;
import it.pagopa.transactions.commands.TransactionUpdateStatusCommand;
import it.pagopa.transactions.commands.data.ClosureSendData;
import it.pagopa.transactions.commands.data.UpdateTransactionStatusData;
import it.pagopa.transactions.documents.*;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.domain.pojos.BaseTransactionWithPaymentToken;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class TransactionUpdateStatusHandlerTest {

    @InjectMocks
    private TransactionUpdateStatusHandler updateStatusHandler;
    @Mock
    private TransactionsEventStoreRepository<TransactionStatusUpdateData> transactionEventStoreRepository;

    @Mock
    private TransactionsEventStoreRepository<Object> eventStoreRepository;

    @Mock
    NotificationsServiceClient notificationsServiceClient;

    private TransactionId transactionId = new TransactionId(UUID.randomUUID());

    @Test
    void shouldSaveSuccessfulUpdate() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                email,
                faultCode,
                faultCodeString,
                TransactionStatusDto.CLOSED);

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionActivatedData(
                        description.value(),
                        amount.value(),
                        email.value(),
                        faultCode,
                        faultCodeString,
                        "paymentToken"
                ));

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode"
                )
        );

        TransactionAuthorizationStatusUpdatedEvent authorizationStatusUpdatedEvent = new TransactionAuthorizationStatusUpdatedEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionAuthorizationStatusUpdateData(
                        AuthorizationResultDto.OK,
                        TransactionStatusDto.AUTHORIZED
                )
        );

        TransactionClosureSentEvent closureSentEvent = new TransactionClosureSentEvent(
                transactionId.toString(),
                transactionActivatedEvent.getRptId(),
                transactionActivatedEvent.getData().getPaymentToken(),
                new TransactionClosureSendData(
                        ClosePaymentResponseDto.OutcomeEnum.OK,
                        TransactionStatusDto.CLOSED
                )
        );

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
                transaction.getTransactionActivatedData().getPaymentToken(),
                transactionAuthorizationStatusUpdateData);

        Flux<TransactionEvent<Object>> events = ((Flux) Flux.just(transactionActivatedEvent, authorizationRequestedEvent, authorizationStatusUpdatedEvent, closureSentEvent, event));

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(notificationsServiceClient.sendSuccessEmail(any())).thenReturn(Mono.just(new NotificationEmailResponseDto().outcome("OK")));

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
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                email,
                faultCode,
                faultCodeString,
                TransactionStatusDto.CLOSED);

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionActivatedData(
                        description.value(),
                        amount.value(),
                        email.value(),
                        faultCode,
                        faultCodeString,
                        "paymentToken"
                ));

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode"
                )
        );

        TransactionAuthorizationStatusUpdatedEvent authorizationStatusUpdatedEvent = new TransactionAuthorizationStatusUpdatedEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionAuthorizationStatusUpdateData(
                        AuthorizationResultDto.OK,
                        TransactionStatusDto.AUTHORIZED
                )
        );

        TransactionClosureSentEvent closureSentEvent = new TransactionClosureSentEvent(
                transactionId.toString(),
                transactionActivatedEvent.getRptId(),
                transactionActivatedEvent.getData().getPaymentToken(),
                new TransactionClosureSendData(
                        ClosePaymentResponseDto.OutcomeEnum.OK,
                        TransactionStatusDto.CLOSED
                )
        );

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
                transaction.getTransactionActivatedData().getPaymentToken(),
                transactionAuthorizationStatusUpdateData);

        Flux<TransactionEvent<Object>> events = ((Flux) Flux.just(transactionActivatedEvent, authorizationRequestedEvent, authorizationStatusUpdatedEvent, closureSentEvent, event));

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(notificationsServiceClient.sendKoEmail(any())).thenReturn(Mono.just(new NotificationEmailResponseDto().outcome("OK")));

        /* test */
        StepVerifier.create(updateStatusHandler.handle(requestAuthorizationCommand))
                .expectNext(event)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(argThat(eventArg -> eventArg
                .getData().getNewTransactionStatus().equals(TransactionStatusDto.NOTIFIED_FAILED)));
    }

    @Test
    void shouldRejectTransactionInInvalidState() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                paymentToken,
                rptId,
                description,
                amount,
                email,
                faultCode,
                faultCodeString,
                TransactionStatusDto.ACTIVATED);

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionActivatedData(
                        description.value(),
                        amount.value(),
                        email.value(),
                        faultCode,
                        faultCodeString,
                        "paymentToken"
                ));

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode"
                )
        );

        TransactionAuthorizationStatusUpdatedEvent authorizationStatusUpdatedEvent = new TransactionAuthorizationStatusUpdatedEvent(
                transactionId.value().toString(),
                rptId.value(),
                paymentToken.value(),
                new TransactionAuthorizationStatusUpdateData(
                        AuthorizationResultDto.OK,
                        TransactionStatusDto.AUTHORIZED
                )
        );

        UpdateTransactionStatusRequestDto updateStatusRequest = new UpdateTransactionStatusRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        UpdateTransactionStatusData updateAuthorizationStatusData = new UpdateTransactionStatusData(
                transaction,
                updateStatusRequest);

        TransactionUpdateStatusCommand requestStatusCommand = new TransactionUpdateStatusCommand(
                transaction.getRptId(), updateAuthorizationStatusData);

        Flux<TransactionEvent<Object>> events = ((Flux) Flux.just(transactionActivatedEvent, authorizationRequestedEvent, authorizationStatusUpdatedEvent));

        /* preconditions */
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);

        /* test */
        StepVerifier.create(updateStatusHandler.handle(requestStatusCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }
}
