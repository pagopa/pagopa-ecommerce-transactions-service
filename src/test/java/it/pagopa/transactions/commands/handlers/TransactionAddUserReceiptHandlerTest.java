package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.domain.v1.Email;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.domain.v1.TransactionEventCode;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.notifications.v1.dto.NotificationEmailResponseDto;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestDto;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestPaymentsInnerDto;
import it.pagopa.transactions.client.NotificationsServiceClient;
import it.pagopa.transactions.commands.TransactionAddUserReceiptCommand;
import it.pagopa.transactions.commands.data.AddUserReceiptData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import it.pagopa.transactions.utils.Queues;
import it.pagopa.transactions.utils.TransactionsUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

import static it.pagopa.ecommerce.commons.v1.TransactionTestUtils.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class TransactionAddUserReceiptHandlerTest {

    private TransactionAddUserReceiptHandler updateStatusHandler;

    @Mock
    private TransactionsEventStoreRepository<TransactionUserReceiptData> userReceiptDataEventRepository;

    @Mock
    TransactionsEventStoreRepository<TransactionRefundedData> refundedRequestedEventStoreRepository;

    private TransactionsEventStoreRepository<Object> eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final TransactionsUtils transactionsUtils = new TransactionsUtils(eventStoreRepository);

    @Mock
    NotificationsServiceClient notificationsServiceClient;

    @Captor
    ArgumentCaptor<NotificationsServiceClient.SuccessTemplateRequest> successTemplateMailCaptor;

    @Captor
    ArgumentCaptor<NotificationsServiceClient.KoTemplateRequest> koTemplateMailCaptor;

    @Mock
    private ConfidentialMailUtils confidentialMailUtils;

    @Mock
    private QueueAsyncClient transactionRefundQueueClient;

    @BeforeEach
    private void initTest() {
        updateStatusHandler = new TransactionAddUserReceiptHandler(
                userReceiptDataEventRepository,
                refundedRequestedEventStoreRepository,
                notificationsServiceClient,
                confidentialMailUtils,
                transactionRefundQueueClient,
                transactionsUtils
        );
    }

    @Test
    void shouldSaveSuccessfulUpdateWithStatusClosed() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                AuthorizationResultDto.OK
        );

        TransactionClosedEvent closureSentEvent = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        TransactionActivated transaction = transactionActivated(ZonedDateTime.now().toString());

        AddUserReceiptData addUserReceiptData = new AddUserReceiptData(
                transaction,
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand addUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                addUserReceiptData
        );

        TransactionUserReceiptAddedEvent event = transactionUserReceiptAddedEvent(
                TransactionUserReceiptData.Outcome.OK
        );

        Flux<TransactionEvent<Object>> events = ((Flux) Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closureSentEvent
        ));

        /* preconditions */
        Mockito.when(userReceiptDataEventRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionId(TRANSACTION_ID)).thenReturn(events);
        Mockito.when(notificationsServiceClient.sendSuccessEmail(successTemplateMailCaptor.capture()))
                .thenReturn(Mono.just(new NotificationEmailResponseDto().outcome("OK")));
        Mockito.when(confidentialMailUtils.toEmail(EMAIL)).thenReturn(Mono.just(new Email(EMAIL_STRING)));

        /* test */
        StepVerifier.create(updateStatusHandler.handle(addUserReceiptCommand))
                .expectNext(event)
                .verifyComplete();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_USER_RECEIPT_ADDED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
        NotificationsServiceClient.SuccessTemplateRequest successTemplateRequest = successTemplateMailCaptor
                .getAllValues().get(0);
        assertEquals(successTemplateRequest.to(), EMAIL_STRING);
        assertEquals(successTemplateRequest.templateParameters().getUser().getEmail(), EMAIL_STRING);
    }

    @Test
    void shouldSaveSuccessfulUpdateWithStatusClosureFailed() throws Exception {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                AuthorizationResultDto.OK
        );

        TransactionClosedEvent closureSentEvent = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        TransactionActivated transaction = transactionActivated(ZonedDateTime.now().toString());

        AddUserReceiptData addUserReceiptData = new AddUserReceiptData(
                transaction,
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand addUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                addUserReceiptData
        );

        TransactionUserReceiptAddedEvent event = TransactionTestUtils
                .transactionUserReceiptAddedEvent(TransactionUserReceiptData.Outcome.OK);

        Flux<TransactionEvent<Object>> events = ((Flux) Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closureSentEvent
        ));

        /* preconditions */
        Mockito.when(userReceiptDataEventRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionId(TRANSACTION_ID)).thenReturn(events);
        Mockito.when(notificationsServiceClient.sendSuccessEmail(successTemplateMailCaptor.capture()))
                .thenReturn(Mono.just(new NotificationEmailResponseDto().outcome("OK")));
        Mockito.when(confidentialMailUtils.toEmail(EMAIL)).thenReturn(Mono.just(new Email(EMAIL_STRING)));

        /* test */
        StepVerifier.create(updateStatusHandler.handle(addUserReceiptCommand))
                .expectNext(event)
                .verifyComplete();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_USER_RECEIPT_ADDED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
        NotificationsServiceClient.SuccessTemplateRequest successTemplateRequest = successTemplateMailCaptor
                .getAllValues().get(0);

        assertEquals(successTemplateRequest.to(), EMAIL_STRING);
        assertEquals(successTemplateRequest.templateParameters().getUser().getEmail(), EMAIL_STRING);
    }

    @Test
    void shouldSaveKoUpdate() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                AuthorizationResultDto.OK
        );

        TransactionClosedEvent closureSentEvent = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.KO)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        TransactionActivated transaction = transactionActivated(ZonedDateTime.now().toString());

        AddUserReceiptData addUserReceiptData = new AddUserReceiptData(
                transaction,
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand transactionAddUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                addUserReceiptData
        );

        TransactionUserReceiptAddedEvent event = transactionUserReceiptAddedEvent(
                TransactionUserReceiptData.Outcome.OK
        );

        Flux<TransactionEvent<Object>> events = ((Flux) Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closureSentEvent
        ));

        /* preconditions */
        Mockito.when(userReceiptDataEventRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionId(TRANSACTION_ID)).thenReturn(events);
        Mockito.when(notificationsServiceClient.sendKoEmail(koTemplateMailCaptor.capture()))
                .thenReturn(Mono.just(new NotificationEmailResponseDto().outcome("OK")));
        Mockito.when(refundedRequestedEventStoreRepository.save(any())).thenAnswer(a -> Mono.just(a.getArgument(0)));
        Mockito.when(transactionRefundQueueClient.sendMessage(any(BinaryData.class)))
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESULT);
        Mockito.when(confidentialMailUtils.toEmail(EMAIL)).thenReturn(Mono.just(new Email(EMAIL_STRING)));

        /* test */
        StepVerifier.create(updateStatusHandler.handle(transactionAddUserReceiptCommand))
                .expectNext(event)
                .verifyComplete();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_USER_RECEIPT_ADDED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
        NotificationsServiceClient.KoTemplateRequest koTemplateRequest = koTemplateMailCaptor.getAllValues().get(0);
        assertEquals(koTemplateRequest.to(), EMAIL_STRING);
    }

    @Test
    void shouldRejectTransactionInInvalidState() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                AuthorizationResultDto.OK
        );

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        TransactionActivated transaction = transactionActivated(ZonedDateTime.now().toString());

        AddUserReceiptData addUserReceiptData = new AddUserReceiptData(
                transaction,
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand requestStatusCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                addUserReceiptData
        );

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        /* preconditions */
        Mockito.when(eventStoreRepository.findByTransactionId(TRANSACTION_ID)).thenReturn(events);

        /* test */
        StepVerifier.create(updateStatusHandler.handle(requestStatusCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldRejectTransactionWithClosureOutcomeKO() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                AuthorizationResultDto.OK
        );

        TransactionClosedEvent closureSentEvent = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.KO);

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        TransactionActivated transaction = transactionActivated(ZonedDateTime.now().toString());

        AddUserReceiptData addUserReceiptData = new AddUserReceiptData(
                transaction,
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand requestStatusCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                addUserReceiptData
        );

        Flux<TransactionEvent<Object>> events = ((Flux) Flux
                .just(
                        transactionActivatedEvent,
                        authorizationRequestedEvent,
                        authorizationCompletedEvent,
                        closureSentEvent
                ));

        /* preconditions */
        Mockito.when(eventStoreRepository.findByTransactionId(TRANSACTION_ID)).thenReturn(events);

        /* test */
        StepVerifier.create(updateStatusHandler.handle(requestStatusCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(0)).save(any());
    }
}
