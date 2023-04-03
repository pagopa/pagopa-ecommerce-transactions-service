package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.domain.v1.TransactionEventCode;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestDto;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestPaymentsInnerDto;
import it.pagopa.transactions.commands.TransactionAddUserReceiptCommand;
import it.pagopa.transactions.commands.data.AddUserReceiptData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static it.pagopa.ecommerce.commons.v1.TransactionTestUtils.*;
import static it.pagopa.transactions.utils.Queues.QUEUE_SUCCESSFUL_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class TransactionRequestUserReceiptHandlerTest {

    private TransactionRequestUserReceiptHandler updateStatusHandler;

    private TransactionsEventStoreRepository<TransactionUserReceiptData> userReceiptDataEventRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private QueueAsyncClient queueAsyncClient = Mockito.mock(QueueAsyncClient.class);

    @Captor
    private ArgumentCaptor<BinaryData> queueArgumentCaptor;

    private TransactionsEventStoreRepository<Object> eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final TransactionsUtils transactionsUtils = new TransactionsUtils(eventStoreRepository);

    @BeforeEach
    public void initTest() {
        updateStatusHandler = new TransactionRequestUserReceiptHandler(
                userReceiptDataEventRepository,
                transactionsUtils,
                queueAsyncClient
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

        TransactionUserReceiptRequestedEvent event = transactionUserReceiptRequestedEvent(
                TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
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
        Mockito.when(queueAsyncClient.sendMessageWithResponse(queueArgumentCaptor.capture(), any(), any()))
                .thenReturn(QUEUE_SUCCESSFUL_RESPONSE);
        /* test */
        StepVerifier.create(updateStatusHandler.handle(addUserReceiptCommand))
                .expectNext(event)
                .verifyComplete();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
        Mockito.verify(queueAsyncClient, Mockito.times(1)).sendMessageWithResponse(any(BinaryData.class), any(), any());
        TransactionUserReceiptRequestedEvent queueEvent = queueArgumentCaptor.getValue()
                .toObject(TransactionUserReceiptRequestedEvent.class);
        assertEquals(TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT, queueEvent.getEventCode());
        assertEquals(event.getData().getResponseOutcome(), queueEvent.getData().getResponseOutcome());
        assertEquals(event.getData().getLanguage(), queueEvent.getData().getLanguage());
        assertEquals(event.getData().getPaymentMethodLogoUri(), queueEvent.getData().getPaymentMethodLogoUri());
        assertEquals(event.getData().getReceivingOfficeName(), queueEvent.getData().getReceivingOfficeName());
        assertEquals(event.getData().getPaymentDescription(), queueEvent.getData().getPaymentDescription());
        // made payment date comparison comparing dates at same zone id
        assertEquals(
                event.getData().getPaymentDate().atZoneSameInstant(ZoneOffset.UTC),
                queueEvent.getData().getPaymentDate().atZoneSameInstant(ZoneOffset.UTC)
        );
    }

    @Test
    void shouldSaveSuccessfulUpdateWithStatusClosureFailed() {
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

        TransactionUserReceiptRequestedEvent event = TransactionTestUtils
                .transactionUserReceiptRequestedEvent(
                        TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
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
        Mockito.when(queueAsyncClient.sendMessageWithResponse(queueArgumentCaptor.capture(), any(), any()))
                .thenReturn(QUEUE_SUCCESSFUL_RESPONSE);
        /* test */
        StepVerifier.create(updateStatusHandler.handle(addUserReceiptCommand))
                .expectNext(event)
                .verifyComplete();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
        Mockito.verify(queueAsyncClient, Mockito.times(1)).sendMessageWithResponse(any(BinaryData.class), any(), any());
        TransactionUserReceiptRequestedEvent queueEvent = queueArgumentCaptor.getValue()
                .toObject(TransactionUserReceiptRequestedEvent.class);
        assertEquals(TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT, queueEvent.getEventCode());
        assertEquals(event.getData().getResponseOutcome(), queueEvent.getData().getResponseOutcome());
        assertEquals(event.getData().getLanguage(), queueEvent.getData().getLanguage());
        assertEquals(event.getData().getPaymentMethodLogoUri(), queueEvent.getData().getPaymentMethodLogoUri());
        assertEquals(event.getData().getReceivingOfficeName(), queueEvent.getData().getReceivingOfficeName());
        assertEquals(event.getData().getPaymentDescription(), queueEvent.getData().getPaymentDescription());
        // made payment date comparison comparing dates at same zone id
        assertEquals(
                event.getData().getPaymentDate().atZoneSameInstant(ZoneOffset.UTC),
                queueEvent.getData().getPaymentDate().atZoneSameInstant(ZoneOffset.UTC)
        );

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

        TransactionUserReceiptRequestedEvent event = transactionUserReceiptRequestedEvent(
                TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
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
        Mockito.when(queueAsyncClient.sendMessageWithResponse(queueArgumentCaptor.capture(), any(), any()))
                .thenReturn(QUEUE_SUCCESSFUL_RESPONSE);
        /* test */
        StepVerifier.create(updateStatusHandler.handle(transactionAddUserReceiptCommand))
                .expectNext(event)
                .verifyComplete();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
        Mockito.verify(queueAsyncClient, Mockito.times(1)).sendMessageWithResponse(any(BinaryData.class), any(), any());
        TransactionUserReceiptRequestedEvent queueEvent = queueArgumentCaptor.getValue()
                .toObject(TransactionUserReceiptRequestedEvent.class);
        assertEquals(TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT, queueEvent.getEventCode());
        assertEquals(event.getData().getResponseOutcome(), queueEvent.getData().getResponseOutcome());
        assertEquals(event.getData().getLanguage(), queueEvent.getData().getLanguage());
        assertEquals(event.getData().getPaymentMethodLogoUri(), queueEvent.getData().getPaymentMethodLogoUri());
        assertEquals(event.getData().getReceivingOfficeName(), queueEvent.getData().getReceivingOfficeName());
        assertEquals(event.getData().getPaymentDescription(), queueEvent.getData().getPaymentDescription());
        // made payment date comparison comparing dates at same zone id
        assertEquals(
                event.getData().getPaymentDate().atZoneSameInstant(ZoneOffset.UTC),
                queueEvent.getData().getPaymentDate().atZoneSameInstant(ZoneOffset.UTC)
        );
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