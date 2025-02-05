package it.pagopa.transactions.commands.handlers.v2;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.v2.*;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.WalletInfo;
import it.pagopa.ecommerce.commons.domain.PaymentNotice;
import it.pagopa.ecommerce.commons.domain.v2.TransactionActivated;
import it.pagopa.ecommerce.commons.domain.v2.TransactionEventCode;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.OperationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.queues.TracingUtilsTests;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestDto;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestPaymentsInnerDto;
import it.pagopa.transactions.commands.TransactionAddUserReceiptCommand;
import it.pagopa.transactions.commands.data.AddUserReceiptData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.InvalidStatusException;
import it.pagopa.transactions.exceptions.ProcessingErrorException;
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

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static it.pagopa.ecommerce.commons.v2.TransactionTestUtils.*;
import static it.pagopa.generated.transactions.server.model.AddUserReceiptRequestDto.OutcomeEnum.OK;
import static it.pagopa.transactions.utils.Queues.QUEUE_SUCCESSFUL_RESPONSE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TransactionRequestUserReceiptHandlerTest {

    private TransactionRequestUserReceiptHandler updateStatusHandler;

    private TransactionsEventStoreRepository<TransactionUserReceiptData> userReceiptDataEventRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private QueueAsyncClient queueAsyncClient = Mockito.mock(QueueAsyncClient.class);

    @Captor
    private ArgumentCaptor<QueueEvent<?>> queueArgumentCaptor;

    private TransactionsEventStoreRepository<Object> eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils = Mockito
            .mock(UpdateTransactionStatusTracerUtils.class);

    private final TransactionsUtils transactionsUtils = new TransactionsUtils(eventStoreRepository, "3020");

    private final int transientQueueEventsTtlSeconds = 30;

    private final TracingUtils tracingUtils = TracingUtilsTests.getMock();

    @Captor
    private ArgumentCaptor<Duration> durationArgumentCaptor;

    private final boolean sendPaymentResultForTxExpiredEnabled = true;

    @BeforeEach
    public void initTest() {
        updateStatusHandler = new TransactionRequestUserReceiptHandler(
                userReceiptDataEventRepository,
                transactionsUtils,
                queueAsyncClient,
                transientQueueEventsTtlSeconds,
                tracingUtils,
                sendPaymentResultForTxExpiredEnabled,
                updateTransactionStatusTracerUtils
        );
    }

    @Test
    void shouldSendEventForSendPaymentOutcomeOk() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.EXECUTED,
                        "operationId",
                        "paymentEnd2EndId",
                        null,
                        null
                )
        );

        TransactionClosureRequestedEvent closureRequestedEvent = TransactionTestUtils
                .transactionClosureRequestedEvent();

        TransactionClosedEvent closureSentEvent = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(OK)
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
                transaction.getTransactionId(),
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand addUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                addUserReceiptData
        );

        TransactionUserReceiptRequestedEvent event = transactionUserReceiptRequestedEvent(
                TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
        );

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closureRequestedEvent,
                closureSentEvent
        ));

        /* preconditions */
        Mockito.when(userReceiptDataEventRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID)).thenReturn(events);
        Mockito.when(
                queueAsyncClient
                        .sendMessageWithResponse(queueArgumentCaptor.capture(), any(), durationArgumentCaptor.capture())
        )
                .thenReturn(QUEUE_SUCCESSFUL_RESPONSE);

        UpdateTransactionStatusTracerUtils.StatusUpdateInfo expectedTransactionUpdateStatus = new UpdateTransactionStatusTracerUtils.SendPaymentResultNodoStatusUpdate(
                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.OK,
                authorizationRequestedEvent.getData().getPspId(),
                authorizationRequestedEvent.getData().getPaymentTypeCode(),
                transactionActivatedEvent.getData().getClientId(),

                transactionAuthorizationRequestedEvent().getData()
                        .getTransactionGatewayAuthorizationRequestedData() instanceof NpgTransactionGatewayAuthorizationRequestedData
                        &&
                        ((NpgTransactionGatewayAuthorizationRequestedData) (transactionAuthorizationRequestedEvent()
                                .getData().getTransactionGatewayAuthorizationRequestedData()))
                                        .getWalletInfo() != null,
                new UpdateTransactionStatusTracerUtils.GatewayOutcomeResult(
                        OK.getValue(),
                        Optional.empty()
                )
        );
        /* test */
        StepVerifier.create(updateStatusHandler.handle(addUserReceiptCommand))
                .expectNext(event)
                .verifyComplete();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT.toString()
                                .equals(eventArg.getEventCode())
                )
        );
        Mockito.verify(queueAsyncClient, Mockito.times(1)).sendMessageWithResponse(any(), any(), any());

        verify(updateTransactionStatusTracerUtils, times(1))
                .traceStatusUpdateOperation(expectedTransactionUpdateStatus);

        TransactionUserReceiptRequestedEvent queueEvent = ((TransactionUserReceiptRequestedEvent) queueArgumentCaptor
                .getValue().event());
        assertEquals(
                TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT.toString(),
                queueEvent.getEventCode()
        );
        assertEquals(event.getData(), queueEvent.getData());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
    }

    @Test
    void shouldSendEventForSendPaymentOutcomeOkWithPaymentGatewayNPGNoWallet() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();
        TransactionAuthorizationRequestData data = authorizationRequestedEvent.getData();
        data.setPaymentGateway(TransactionAuthorizationRequestData.PaymentGateway.NPG);
        data.setTransactionGatewayAuthorizationRequestedData(
                new NpgTransactionGatewayAuthorizationRequestedData(
                        LOGO_URI,
                        "brand",
                        "sessionID",
                        "confirmPaymentSessionId",
                        null
                )
        );
        authorizationRequestedEvent.setData(data);

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.EXECUTED,
                        "operationId",
                        "paymentEnd2EndId",
                        null,
                        null
                )
        );

        TransactionClosureRequestedEvent closureRequestedEvent = TransactionTestUtils
                .transactionClosureRequestedEvent();

        TransactionClosedEvent closureSentEvent = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(OK)
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
                transaction.getTransactionId(),
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand addUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                addUserReceiptData
        );

        TransactionUserReceiptRequestedEvent event = transactionUserReceiptRequestedEvent(
                TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
        );

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closureRequestedEvent,
                closureSentEvent
        ));

        /* preconditions */
        Mockito.when(userReceiptDataEventRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID)).thenReturn(events);
        Mockito.when(
                queueAsyncClient
                        .sendMessageWithResponse(queueArgumentCaptor.capture(), any(), durationArgumentCaptor.capture())
        )
                .thenReturn(QUEUE_SUCCESSFUL_RESPONSE);

        UpdateTransactionStatusTracerUtils.StatusUpdateInfo expectedTransactionUpdateStatus = new UpdateTransactionStatusTracerUtils.SendPaymentResultNodoStatusUpdate(
                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.OK,
                authorizationRequestedEvent.getData().getPspId(),
                authorizationRequestedEvent.getData().getPaymentTypeCode(),
                transactionActivatedEvent.getData().getClientId(),
                false,
                new UpdateTransactionStatusTracerUtils.GatewayOutcomeResult(
                        OK.getValue(),
                        Optional.empty()
                )
        );
        /* test */
        StepVerifier.create(updateStatusHandler.handle(addUserReceiptCommand))
                .expectNext(event)
                .verifyComplete();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT.toString()
                                .equals(eventArg.getEventCode())
                )
        );
        Mockito.verify(queueAsyncClient, Mockito.times(1)).sendMessageWithResponse(any(), any(), any());

        verify(updateTransactionStatusTracerUtils, times(1))
                .traceStatusUpdateOperation(expectedTransactionUpdateStatus);

        TransactionUserReceiptRequestedEvent queueEvent = ((TransactionUserReceiptRequestedEvent) queueArgumentCaptor
                .getValue().event());
        assertEquals(
                TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT.toString(),
                queueEvent.getEventCode()
        );
        assertEquals(event.getData(), queueEvent.getData());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
    }

    @Test
    void shouldSendEventForSendPaymentOutcomeOkWithPaymentGatewayNPGWithWalletCard() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();
        TransactionAuthorizationRequestData data = authorizationRequestedEvent.getData();
        data.setPaymentGateway(TransactionAuthorizationRequestData.PaymentGateway.NPG);

        WalletInfo walletInfo = new WalletInfo("walletId", new WalletInfo.CardWalletDetails("1234", "5678"));

        data.setTransactionGatewayAuthorizationRequestedData(
                new NpgTransactionGatewayAuthorizationRequestedData(
                        LOGO_URI,
                        "brand",
                        "sessionID",
                        "confirmPaymentSessionId",
                        walletInfo
                )
        );
        authorizationRequestedEvent.setData(data);

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.EXECUTED,
                        "operationId",
                        "paymentEnd2EndId",
                        null,
                        null
                )
        );

        TransactionClosureRequestedEvent closureRequestedEvent = TransactionTestUtils
                .transactionClosureRequestedEvent();

        TransactionClosedEvent closureSentEvent = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(OK)
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
                transaction.getTransactionId(),
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand addUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                addUserReceiptData
        );

        TransactionUserReceiptRequestedEvent event = transactionUserReceiptRequestedEvent(
                TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
        );

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closureRequestedEvent,
                closureSentEvent
        ));

        /* preconditions */
        Mockito.when(userReceiptDataEventRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID)).thenReturn(events);
        Mockito.when(
                queueAsyncClient
                        .sendMessageWithResponse(queueArgumentCaptor.capture(), any(), durationArgumentCaptor.capture())
        )
                .thenReturn(QUEUE_SUCCESSFUL_RESPONSE);

        UpdateTransactionStatusTracerUtils.StatusUpdateInfo expectedTransactionUpdateStatus = new UpdateTransactionStatusTracerUtils.SendPaymentResultNodoStatusUpdate(
                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.OK,
                authorizationRequestedEvent.getData().getPspId(),
                authorizationRequestedEvent.getData().getPaymentTypeCode(),
                transactionActivatedEvent.getData().getClientId(),

                true,
                new UpdateTransactionStatusTracerUtils.GatewayOutcomeResult(
                        OK.getValue(),
                        Optional.empty()
                )
        );
        /* test */
        StepVerifier.create(updateStatusHandler.handle(addUserReceiptCommand))
                .expectNext(event)
                .verifyComplete();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT.toString()
                                .equals(eventArg.getEventCode())
                )
        );
        Mockito.verify(queueAsyncClient, Mockito.times(1)).sendMessageWithResponse(any(), any(), any());

        verify(updateTransactionStatusTracerUtils, times(1))
                .traceStatusUpdateOperation(expectedTransactionUpdateStatus);

        TransactionUserReceiptRequestedEvent queueEvent = ((TransactionUserReceiptRequestedEvent) queueArgumentCaptor
                .getValue().event());
        assertEquals(
                TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT.toString(),
                queueEvent.getEventCode()
        );
        assertEquals(event.getData(), queueEvent.getData());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
    }

    @Test
    void shouldSendEventForSendPaymentOutcomeOkWithPaymentGatewayNPGWithWalletPaypal() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();
        TransactionAuthorizationRequestData data = authorizationRequestedEvent.getData();
        data.setPaymentGateway(TransactionAuthorizationRequestData.PaymentGateway.NPG);

        WalletInfo walletInfo = new WalletInfo("walletId", new WalletInfo.PaypalWalletDetails("gia****om"));

        data.setTransactionGatewayAuthorizationRequestedData(
                new NpgTransactionGatewayAuthorizationRequestedData(
                        LOGO_URI,
                        "brand",
                        "sessionID",
                        "confirmPaymentSessionId",
                        walletInfo
                )
        );
        authorizationRequestedEvent.setData(data);

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.EXECUTED,
                        "operationId",
                        "paymentEnd2EndId",
                        null,
                        null
                )
        );

        TransactionClosureRequestedEvent closureRequestedEvent = TransactionTestUtils
                .transactionClosureRequestedEvent();

        TransactionClosedEvent closureSentEvent = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(OK)
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
                transaction.getTransactionId(),
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand addUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                addUserReceiptData
        );

        TransactionUserReceiptRequestedEvent event = transactionUserReceiptRequestedEvent(
                TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
        );

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closureRequestedEvent,
                closureSentEvent
        ));

        /* preconditions */
        Mockito.when(userReceiptDataEventRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID)).thenReturn(events);
        Mockito.when(
                queueAsyncClient
                        .sendMessageWithResponse(queueArgumentCaptor.capture(), any(), durationArgumentCaptor.capture())
        )
                .thenReturn(QUEUE_SUCCESSFUL_RESPONSE);

        UpdateTransactionStatusTracerUtils.StatusUpdateInfo expectedTransactionUpdateStatus = new UpdateTransactionStatusTracerUtils.SendPaymentResultNodoStatusUpdate(
                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.OK,
                authorizationRequestedEvent.getData().getPspId(),
                authorizationRequestedEvent.getData().getPaymentTypeCode(),
                transactionActivatedEvent.getData().getClientId(),

                true,
                new UpdateTransactionStatusTracerUtils.GatewayOutcomeResult(
                        OK.getValue(),
                        Optional.empty()
                )
        );
        /* test */
        StepVerifier.create(updateStatusHandler.handle(addUserReceiptCommand))
                .expectNext(event)
                .verifyComplete();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT.toString()
                                .equals(eventArg.getEventCode())
                )
        );
        Mockito.verify(queueAsyncClient, Mockito.times(1)).sendMessageWithResponse(any(), any(), any());

        verify(updateTransactionStatusTracerUtils, times(1))
                .traceStatusUpdateOperation(expectedTransactionUpdateStatus);

        TransactionUserReceiptRequestedEvent queueEvent = ((TransactionUserReceiptRequestedEvent) queueArgumentCaptor
                .getValue().event());
        assertEquals(
                TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT.toString(),
                queueEvent.getEventCode()
        );
        assertEquals(event.getData(), queueEvent.getData());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
    }

    @Test
    void shouldSendEventForSendPaymentOutcomeKo() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.EXECUTED,
                        "operationId",
                        "paymentEnd2EndId",
                        null,
                        null
                )
        );

        TransactionClosureRequestedEvent closureRequestedEvent = TransactionTestUtils
                .transactionClosureRequestedEvent();

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
                transaction.getTransactionId(),
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand transactionAddUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                addUserReceiptData
        );

        TransactionUserReceiptRequestedEvent event = transactionUserReceiptRequestedEvent(
                TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
        );

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closureRequestedEvent,
                closureSentEvent
        ));

        /* preconditions */
        Mockito.when(userReceiptDataEventRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID)).thenReturn(events);
        Mockito.when(
                queueAsyncClient
                        .sendMessageWithResponse(queueArgumentCaptor.capture(), any(), durationArgumentCaptor.capture())
        )
                .thenReturn(QUEUE_SUCCESSFUL_RESPONSE);
        /* test */
        StepVerifier.create(updateStatusHandler.handle(transactionAddUserReceiptCommand))
                .expectNext(event)
                .verifyComplete();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT.toString()
                                .equals(eventArg.getEventCode())
                )
        );
        Mockito.verify(queueAsyncClient, Mockito.times(1)).sendMessageWithResponse(any(), any(), any());
        TransactionUserReceiptRequestedEvent queueEvent = ((TransactionUserReceiptRequestedEvent) queueArgumentCaptor
                .getValue().event());
        assertEquals(
                TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT.toString(),
                queueEvent.getEventCode()
        );
        assertEquals(event.getData(), queueEvent.getData());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
    }

    @Test
    void shouldReturnMonoErrorForErrorSendingEventOnTheQueue() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.EXECUTED,
                        "operationId",
                        "paymentEnd2EndId",
                        null,
                        null
                )
        );

        TransactionClosureRequestedEvent closureRequestedEvent = TransactionTestUtils
                .transactionClosureRequestedEvent();

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
                transaction.getTransactionId(),
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand transactionAddUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                addUserReceiptData
        );

        TransactionUserReceiptRequestedEvent event = transactionUserReceiptRequestedEvent(
                TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
        );

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closureRequestedEvent,
                closureSentEvent
        ));

        /* preconditions */
        Mockito.when(userReceiptDataEventRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID)).thenReturn(events);
        Mockito.when(
                queueAsyncClient
                        .sendMessageWithResponse(queueArgumentCaptor.capture(), any(), durationArgumentCaptor.capture())
        )
                .thenReturn(Mono.error(new RuntimeException("Error writing message to queue")));
        /* test */
        StepVerifier.create(updateStatusHandler.handle(transactionAddUserReceiptCommand))
                .expectError(RuntimeException.class)
                .verify();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT.toString()
                                .equals(eventArg.getEventCode())
                )
        );
        Mockito.verify(queueAsyncClient, Mockito.times(1)).sendMessageWithResponse(any(), any(), any());
        TransactionUserReceiptRequestedEvent queueEvent = ((TransactionUserReceiptRequestedEvent) queueArgumentCaptor
                .getValue().event());
        assertEquals(
                TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT.toString(),
                queueEvent.getEventCode()
        );
        assertEquals(event.getData(), queueEvent.getData());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
    }

    @Test
    void shouldRejectTransactionInInvalidStateClosureRequested() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.EXECUTED,
                        "operationId",
                        "paymentEnd2EndId",
                        null,
                        null
                )
        );

        TransactionClosureRequestedEvent transactionClosureRequestedEvent = transactionClosureRequestedEvent();

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(OK)
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
                transaction.getTransactionId(),
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand requestStatusCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                addUserReceiptData
        );

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(
                        transactionActivatedEvent,
                        authorizationRequestedEvent,
                        authorizationCompletedEvent,
                        transactionClosureRequestedEvent
                ));

        /* preconditions */
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID)).thenReturn(events);

        /* test */
        StepVerifier.create(updateStatusHandler.handle(requestStatusCommand))
                .expectErrorMatches(error -> error instanceof InvalidStatusException)
                .verify();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldRejectTransactionInInvalidState() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.EXECUTED,
                        "operationId",
                        "paymentEnd2EndId",
                        null,
                        null
                )
        );

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(OK)
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
                transaction.getTransactionId(),
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand requestStatusCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                addUserReceiptData
        );

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, authorizationRequestedEvent, authorizationCompletedEvent));

        /* preconditions */
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID)).thenReturn(events);

        /* test */
        StepVerifier.create(updateStatusHandler.handle(requestStatusCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldRejectTransactionInInvalidStateNotRequestedAuth() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(OK)
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
                transaction.getTransactionId(),
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand requestStatusCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                addUserReceiptData
        );

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent));

        /* preconditions */
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID)).thenReturn(events);

        /* test */
        StepVerifier.create(updateStatusHandler.handle(requestStatusCommand))
                .expectErrorMatches(
                        error -> error instanceof ProcessingErrorException && error.getMessage().equals(
                                "Error processing sendPaymentResult for transaction "
                                        + transaction.getTransactionId().value() + " in status "
                                        + transaction.getStatus().getValue()
                        )
                )
                .verify();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldRejectTransactionWithClosureOutcomeKO() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.EXECUTED,
                        "operationId",
                        "paymentEnd2EndId",
                        null,
                        null
                )
        );

        TransactionClosedEvent closureSentEvent = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.KO);

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(OK)
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
                transaction.getTransactionId(),
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand requestStatusCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                addUserReceiptData
        );

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(
                        transactionActivatedEvent,
                        authorizationRequestedEvent,
                        authorizationCompletedEvent,
                        closureSentEvent
                ));

        /* preconditions */
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID)).thenReturn(events);

        /* test */
        StepVerifier.create(updateStatusHandler.handle(requestStatusCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        verify(updateTransactionStatusTracerUtils, times(0)).traceStatusUpdateOperation(
                any()
        );

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldSendEventForSendPaymentOutcomeOkForTxExpired() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.EXECUTED,
                        "operationId",
                        "paymentEnd2EndId",
                        null,
                        null
                )
        );

        TransactionClosureRequestedEvent closureRequestedEvent = TransactionTestUtils
                .transactionClosureRequestedEvent();

        TransactionClosedEvent closureSentEvent = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(OK)
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

        TransactionExpiredEvent transactionExpiredEvent = TransactionTestUtils.transactionExpiredEvent(transaction);

        AddUserReceiptData addUserReceiptData = new AddUserReceiptData(
                transaction.getTransactionId(),
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand addUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                addUserReceiptData
        );

        TransactionUserReceiptRequestedEvent event = transactionUserReceiptRequestedEvent(
                TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
        );

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closureRequestedEvent,
                closureSentEvent,
                transactionExpiredEvent
        ));

        /* preconditions */
        Mockito.when(userReceiptDataEventRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID)).thenReturn(events);
        Mockito.when(
                queueAsyncClient
                        .sendMessageWithResponse(queueArgumentCaptor.capture(), any(), durationArgumentCaptor.capture())
        )
                .thenReturn(QUEUE_SUCCESSFUL_RESPONSE);
        /* test */
        StepVerifier.create(updateStatusHandler.handle(addUserReceiptCommand))
                .expectNext(event)
                .verifyComplete();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT.toString()
                                .equals(eventArg.getEventCode())
                )
        );
        Mockito.verify(queueAsyncClient, Mockito.times(1)).sendMessageWithResponse(any(), any(), any());
        TransactionUserReceiptRequestedEvent queueEvent = ((TransactionUserReceiptRequestedEvent) queueArgumentCaptor
                .getValue().event());
        assertEquals(
                TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT.toString(),
                queueEvent.getEventCode()
        );
        assertEquals(event.getData(), queueEvent.getData());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
    }

    @Test
    void shouldRejectSendPaymentResultAfterExpirationForDisabledFeatureFlag() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.EXECUTED,
                        "operationId",
                        "paymentEnd2EndId",
                        null,
                        null
                )
        );

        TransactionClosureRequestedEvent closureRequestedEvent = TransactionTestUtils
                .transactionClosureRequestedEvent();

        TransactionClosedEvent closureSentEvent = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(OK)
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

        TransactionExpiredEvent transactionExpiredEvent = TransactionTestUtils.transactionExpiredEvent(transaction);

        AddUserReceiptData addUserReceiptData = new AddUserReceiptData(
                transaction.getTransactionId(),
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand addUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                addUserReceiptData
        );

        TransactionUserReceiptRequestedEvent event = transactionUserReceiptRequestedEvent(
                TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
        );

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closureRequestedEvent,
                closureSentEvent,
                transactionExpiredEvent
        ));

        /* preconditions */
        Mockito.when(userReceiptDataEventRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID)).thenReturn(events);
        Mockito.when(
                queueAsyncClient
                        .sendMessageWithResponse(queueArgumentCaptor.capture(), any(), durationArgumentCaptor.capture())
        )
                .thenReturn(QUEUE_SUCCESSFUL_RESPONSE);
        TransactionRequestUserReceiptHandler updateStatusHandler = new TransactionRequestUserReceiptHandler(
                userReceiptDataEventRepository,
                transactionsUtils,
                queueAsyncClient,
                transientQueueEventsTtlSeconds,
                tracingUtils,
                false,
                updateTransactionStatusTracerUtils
        );
        /* test */
        StepVerifier.create(updateStatusHandler.handle(addUserReceiptCommand))
                .expectError(AlreadyProcessedException.class)
                .verify();

        verify(updateTransactionStatusTracerUtils, times(0)).traceStatusUpdateOperation(
                any()
        );

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(0)).save(
                any()
        );
        Mockito.verify(queueAsyncClient, Mockito.times(0)).sendMessageWithResponse(any(), any(), any());
    }

    @Test
    void shouldReturnInvalidRequestExceptionForMismatchInPaymentTokens() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();
        List<it.pagopa.ecommerce.commons.documents.PaymentNotice> paymentNotices = new ArrayList<>();
        IntStream.range(0, 5).forEach(
                idx -> paymentNotices.add(
                        new it.pagopa.ecommerce.commons.documents.PaymentNotice(
                                "paymentToken_%s".formatted(idx),
                                RPT_ID,
                                DESCRIPTION,
                                AMOUNT,
                                PAYMENT_CONTEXT_CODE,
                                List.of(),
                                false,
                                COMPANY_NAME,
                                CREDITOR_REFERENCE_ID
                        )
                )
        );
        transactionActivatedEvent.getData().setPaymentNotices(paymentNotices);

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.EXECUTED,
                        "operationId",
                        "paymentEnd2EndId",
                        null,
                        null
                )
        );

        TransactionClosureRequestedEvent closureRequestedEvent = TransactionTestUtils
                .transactionClosureRequestedEvent();

        TransactionClosedEvent closureSentEvent = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);

        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(OK)
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
                transaction.getTransactionId(),
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand addUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                addUserReceiptData
        );

        TransactionUserReceiptRequestedEvent event = transactionUserReceiptRequestedEvent(
                TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
        );

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closureRequestedEvent,
                closureSentEvent
        ));

        /* preconditions */
        Mockito.when(userReceiptDataEventRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID)).thenReturn(events);
        Mockito.when(
                queueAsyncClient
                        .sendMessageWithResponse(queueArgumentCaptor.capture(), any(), durationArgumentCaptor.capture())
        )
                .thenReturn(QUEUE_SUCCESSFUL_RESPONSE);
        /* test */
        StepVerifier.create(updateStatusHandler.handle(addUserReceiptCommand))
                .expectError(InvalidRequestException.class)
                .verify();

        verify(updateTransactionStatusTracerUtils, times(0)).traceStatusUpdateOperation(
                any()
        );
        Mockito.verify(userReceiptDataEventRepository, Mockito.times(0)).save(any());
        Mockito.verify(queueAsyncClient, Mockito.times(0)).sendMessageWithResponse(any(), any(), any());
    }

    @Test
    void shouldSendEventForSendPaymentOutcomeOkWithMultiplePaymentNotices() {
        TransactionActivatedEvent transactionActivatedEvent = transactionActivateEvent();
        List<it.pagopa.ecommerce.commons.documents.PaymentNotice> paymentNotices = new ArrayList<>();
        IntStream.range(0, 5).forEach(
                idx -> paymentNotices.add(
                        new it.pagopa.ecommerce.commons.documents.PaymentNotice(
                                "paymentToken_%s".formatted(idx),
                                RPT_ID,
                                DESCRIPTION,
                                AMOUNT,
                                PAYMENT_CONTEXT_CODE,
                                List.of(),
                                false,
                                COMPANY_NAME,
                                CREDITOR_REFERENCE_ID
                        )
                )
        );
        transactionActivatedEvent.getData().setPaymentNotices(paymentNotices);

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = transactionAuthorizationCompletedEvent(
                new NpgTransactionGatewayAuthorizationData(
                        OperationResultDto.EXECUTED,
                        "operationId",
                        "paymentEnd2EndId",
                        null,
                        null
                )
        );

        TransactionClosureRequestedEvent closureRequestedEvent = TransactionTestUtils
                .transactionClosureRequestedEvent();

        TransactionClosedEvent closureSentEvent = TransactionTestUtils
                .transactionClosedEvent(TransactionClosureData.Outcome.OK);
        List<AddUserReceiptRequestPaymentsInnerDto> sendPaymentResultPaymentItems = new ArrayList<>();
        IntStream.range(0, 5).forEach(
                idx -> sendPaymentResultPaymentItems.add(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken_%s".formatted(idx))
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                )
        );
        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(OK)
                .paymentDate(OffsetDateTime.now());
        addUserReceiptRequest.setPayments(sendPaymentResultPaymentItems);

        TransactionActivated transaction = transactionActivated(ZonedDateTime.now().toString());

        AddUserReceiptData addUserReceiptData = new AddUserReceiptData(
                transaction.getTransactionId(),
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand addUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                addUserReceiptData
        );

        TransactionUserReceiptRequestedEvent event = transactionUserReceiptRequestedEvent(
                TransactionTestUtils.transactionUserReceiptData(TransactionUserReceiptData.Outcome.OK)
        );

        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closureRequestedEvent,
                closureSentEvent
        ));

        /* preconditions */
        Mockito.when(userReceiptDataEventRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(TRANSACTION_ID)).thenReturn(events);
        Mockito.when(
                queueAsyncClient
                        .sendMessageWithResponse(queueArgumentCaptor.capture(), any(), durationArgumentCaptor.capture())
        )
                .thenReturn(QUEUE_SUCCESSFUL_RESPONSE);
        /* test */
        StepVerifier.create(updateStatusHandler.handle(addUserReceiptCommand))
                .expectNext(event)
                .verifyComplete();

        Mockito.verify(userReceiptDataEventRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT.toString()
                                .equals(eventArg.getEventCode())
                )
        );
        Mockito.verify(queueAsyncClient, Mockito.times(1)).sendMessageWithResponse(any(), any(), any());

        UpdateTransactionStatusTracerUtils.StatusUpdateInfo expectedStatusUpdateInfo = new UpdateTransactionStatusTracerUtils.SendPaymentResultNodoStatusUpdate(
                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.OK,
                authorizationRequestedEvent.getData().getPspId(),
                authorizationRequestedEvent.getData().getPaymentTypeCode(),
                transactionActivatedEvent.getData().getClientId(),

                transactionAuthorizationRequestedEvent().getData()
                        .getTransactionGatewayAuthorizationRequestedData() instanceof NpgTransactionGatewayAuthorizationRequestedData
                        &&
                        ((NpgTransactionGatewayAuthorizationRequestedData) (transactionAuthorizationRequestedEvent()
                                .getData().getTransactionGatewayAuthorizationRequestedData()))
                                        .getWalletInfo() != null,
                new UpdateTransactionStatusTracerUtils.GatewayOutcomeResult(
                        OK.getValue(),
                        Optional.empty()
                )
        );
        verify(updateTransactionStatusTracerUtils, times(1)).traceStatusUpdateOperation(
                expectedStatusUpdateInfo
        );

        TransactionUserReceiptRequestedEvent queueEvent = ((TransactionUserReceiptRequestedEvent) queueArgumentCaptor
                .getValue().event());
        assertEquals(
                TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT.toString(),
                queueEvent.getEventCode()
        );
        assertEquals(event.getData(), queueEvent.getData());
        assertEquals(Duration.ofSeconds(transientQueueEventsTtlSeconds), durationArgumentCaptor.getValue());
    }

}
