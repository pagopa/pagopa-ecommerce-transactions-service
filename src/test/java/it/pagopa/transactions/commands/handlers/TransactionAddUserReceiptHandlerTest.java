package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentResponseDto;
import it.pagopa.generated.notifications.v1.dto.NotificationEmailResponseDto;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestDto;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestPaymentsDto;
import it.pagopa.generated.transactions.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.client.NotificationsServiceClient;
import it.pagopa.transactions.commands.TransactionAddUserReceiptCommand;
import it.pagopa.transactions.commands.data.AddUserReceiptData;
import it.pagopa.transactions.documents.*;
import it.pagopa.transactions.domain.*;
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
class TransactionAddUserReceiptHandlerTest {

    @InjectMocks
    private TransactionAddUserReceiptHandler updateStatusHandler;

    @Mock
    private TransactionsEventStoreRepository<TransactionAddReceiptData> transactionEventStoreRepository;

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
                        TransactionStatusDto.CLOSED,
                        "authorizationCode"
                )
        );

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(new AddUserReceiptRequestPaymentsDto()
                        .paymentToken("paymentToken")
                        .companyName("companyName")
                        .creditorReferenceId("creditorReferenceId")
                        .description("description")
                        .debtor("debtor")
                        .fiscalCode("fiscalCode")
                        .officeName("officeName")
                );

        AddUserReceiptData addUserReceiptData = new AddUserReceiptData(
                transaction,
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand addUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getRptId(), addUserReceiptData);

        TransactionAddReceiptData transactionAddReceiptData = new TransactionAddReceiptData(TransactionStatusDto.NOTIFIED);

        TransactionUserReceiptAddedEvent event = new TransactionUserReceiptAddedEvent(
                transactionId.toString(),
                transaction.getRptId().toString(),
                transaction.getTransactionActivatedData().getPaymentToken(),
                transactionAddReceiptData);

        Flux<TransactionEvent<Object>> events = ((Flux) Flux.just(transactionActivatedEvent, authorizationRequestedEvent, authorizationStatusUpdatedEvent, closureSentEvent, event));

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(notificationsServiceClient.sendSuccessEmail(any())).thenReturn(Mono.just(new NotificationEmailResponseDto().outcome("OK")));

        /* test */
        StepVerifier.create(updateStatusHandler.handle(addUserReceiptCommand))
                .expectNext(event)
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
                        TransactionStatusDto.CLOSED,
                        "authorizationCode"
                )
        );

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.KO)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(new AddUserReceiptRequestPaymentsDto()
                        .paymentToken("paymentToken")
                        .companyName("companyName")
                        .creditorReferenceId("creditorReferenceId")
                        .description("description")
                        .debtor("debtor")
                        .fiscalCode("fiscalCode")
                        .officeName("officeName")
                );

        AddUserReceiptData addUserReceiptData = new AddUserReceiptData(
                transaction,
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand transactionAddUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getRptId(), addUserReceiptData);

        TransactionAddReceiptData transactionAddReceiptData = new TransactionAddReceiptData(TransactionStatusDto.NOTIFIED);

        TransactionUserReceiptAddedEvent event = new TransactionUserReceiptAddedEvent(
                transactionId.toString(),
                transaction.getRptId().toString(),
                transaction.getTransactionActivatedData().getPaymentToken(),
                transactionAddReceiptData);

        Flux<TransactionEvent<Object>> events = ((Flux) Flux.just(transactionActivatedEvent, authorizationRequestedEvent, authorizationStatusUpdatedEvent, closureSentEvent, event));

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(notificationsServiceClient.sendKoEmail(any())).thenReturn(Mono.just(new NotificationEmailResponseDto().outcome("OK")));

        /* test */
        StepVerifier.create(updateStatusHandler.handle(transactionAddUserReceiptCommand))
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

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(new AddUserReceiptRequestPaymentsDto()
                        .paymentToken("paymentToken")
                        .companyName("companyName")
                        .creditorReferenceId("creditorReferenceId")
                        .description("description")
                        .debtor("debtor")
                        .fiscalCode("fiscalCode")
                        .officeName("officeName")
                );

        AddUserReceiptData addUserReceiptData = new AddUserReceiptData(
                transaction,
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand requestStatusCommand = new TransactionAddUserReceiptCommand(
                transaction.getRptId(), addUserReceiptData);

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
