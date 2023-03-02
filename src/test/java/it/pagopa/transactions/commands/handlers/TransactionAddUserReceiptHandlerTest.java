package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v1.PaymentNotice;
import it.pagopa.ecommerce.commons.domain.v1.*;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class TransactionAddUserReceiptHandlerTest {

    private TransactionAddUserReceiptHandler updateStatusHandler;

    @Mock
    private TransactionsEventStoreRepository<Void> transactionEventStoreRepository;

    @Mock
    private TransactionsEventStoreRepository<Object> eventStoreRepository;

    @Mock
    NotificationsServiceClient notificationsServiceClient;

    @Captor
    ArgumentCaptor<NotificationsServiceClient.SuccessTemplateRequest> successTemplateMailCaptor;

    @Captor
    ArgumentCaptor<NotificationsServiceClient.KoTemplateRequest> koTemplateMailCaptor;

    private final TransactionId transactionId = new TransactionId(UUID.randomUUID());

    private final ConfidentialMailUtils confidentialMailUtils = new ConfidentialMailUtils(
            TransactionTestUtils.confidentialDataManager
    );

    @BeforeEach
    private void initTest() {
        updateStatusHandler = new TransactionAddUserReceiptHandler(
                eventStoreRepository,
                transactionEventStoreRepository,
                notificationsServiceClient,
                confidentialMailUtils
        );
    }

    @Test
    void shouldSaveSuccessfulUpdateWithStatusClosed() throws Exception {
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
                Transaction.ClientId.CHECKOUT
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email,
                        List.of(
                                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                                        paymentToken.value(),
                                        rptId.value(),
                                        description.value(),
                                        amount.value(),
                                        nullPaymentContextCode.value()
                                )
                        ),
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.OK
                )
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

        AddUserReceiptData addUserReceiptData = new AddUserReceiptData(
                transaction,
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand addUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                addUserReceiptData
        );

        TransactionUserReceiptAddedEvent event = new TransactionUserReceiptAddedEvent(
                transactionId.toString()
        );

        Flux<TransactionEvent<Object>> events = ((Flux) Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closureSentEvent
        ));

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(notificationsServiceClient.sendSuccessEmail(successTemplateMailCaptor.capture()))
                .thenReturn(Mono.just(new NotificationEmailResponseDto().outcome("OK")));

        /* test */
        StepVerifier.create(updateStatusHandler.handle(addUserReceiptCommand))
                .expectNext(event)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_USER_RECEIPT_ADDED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
        NotificationsServiceClient.SuccessTemplateRequest successTemplateRequest = successTemplateMailCaptor
                .getAllValues().get(0);
        String decryptedMail = TransactionTestUtils.confidentialDataManager.decrypt(email);
        assertEquals(successTemplateRequest.to(), decryptedMail);
        assertEquals(successTemplateRequest.templateParameters().getUser().getEmail(), decryptedMail);
    }

    @Test
    void shouldSaveSuccessfulUpdateWithStatusClosureFailed() throws Exception {
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
                Transaction.ClientId.CHECKOUT
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email,
                        List.of(
                                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                                        paymentToken.value(),
                                        rptId.value(),
                                        description.value(),
                                        amount.value(),
                                        nullPaymentContextCode.value()
                                )
                        ),
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.OK
                )
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

        AddUserReceiptData addUserReceiptData = new AddUserReceiptData(
                transaction,
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand addUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                addUserReceiptData
        );

        TransactionUserReceiptAddedEvent event = new TransactionUserReceiptAddedEvent(
                transactionId.toString()
        );

        Flux<TransactionEvent<Object>> events = ((Flux) Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closureSentEvent
        ));

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(notificationsServiceClient.sendSuccessEmail(successTemplateMailCaptor.capture()))
                .thenReturn(Mono.just(new NotificationEmailResponseDto().outcome("OK")));

        /* test */
        StepVerifier.create(updateStatusHandler.handle(addUserReceiptCommand))
                .expectNext(event)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_USER_RECEIPT_ADDED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
        NotificationsServiceClient.SuccessTemplateRequest successTemplateRequest = successTemplateMailCaptor
                .getAllValues().get(0);

        String decryptedMail = TransactionTestUtils.confidentialDataManager.decrypt(email);
        assertEquals(successTemplateRequest.to(), decryptedMail);
        assertEquals(successTemplateRequest.templateParameters().getUser().getEmail(), decryptedMail);
    }

    @Test
    void shouldSaveKoUpdate() throws Exception {
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
                Transaction.ClientId.CHECKOUT
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email,
                        List.of(
                                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                                        paymentToken.value(),
                                        rptId.value(),
                                        description.value(),
                                        amount.value(),
                                        nullPaymentContextCode.value()
                                )
                        ),
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.OK
                )
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

        AddUserReceiptData addUserReceiptData = new AddUserReceiptData(
                transaction,
                addUserReceiptRequest
        );

        TransactionAddUserReceiptCommand transactionAddUserReceiptCommand = new TransactionAddUserReceiptCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                addUserReceiptData
        );

        TransactionUserReceiptAddedEvent event = new TransactionUserReceiptAddedEvent(
                transactionId.toString()
        );

        Flux<TransactionEvent<Object>> events = ((Flux) Flux.just(
                transactionActivatedEvent,
                authorizationRequestedEvent,
                authorizationCompletedEvent,
                closureSentEvent
        ));

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);
        Mockito.when(notificationsServiceClient.sendKoEmail(koTemplateMailCaptor.capture()))
                .thenReturn(Mono.just(new NotificationEmailResponseDto().outcome("OK")));

        /* test */
        StepVerifier.create(updateStatusHandler.handle(transactionAddUserReceiptCommand))
                .expectNext(event)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_USER_RECEIPT_ADDED_EVENT
                                .equals(eventArg.getEventCode())
                )
        );
        NotificationsServiceClient.KoTemplateRequest koTemplateRequest = koTemplateMailCaptor.getAllValues().get(0);
        assertEquals(koTemplateRequest.to(), TransactionTestUtils.confidentialDataManager.decrypt(email));
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
                Transaction.ClientId.CHECKOUT
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email,
                        List.of(
                                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                                        paymentToken.value(),
                                        rptId.value(),
                                        description.value(),
                                        amount.value(),
                                        nullPaymentContextCode.value()
                                )
                        ),
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.OK
                )
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
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);

        /* test */
        StepVerifier.create(updateStatusHandler.handle(requestStatusCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldRejectTransactionWithClosureOutcomeKO() {
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
                Transaction.ClientId.CHECKOUT
        );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                transactionId.value().toString(),
                new TransactionActivatedData(
                        email,
                        List.of(
                                new it.pagopa.ecommerce.commons.documents.v1.PaymentNotice(
                                        paymentToken.value(),
                                        rptId.value(),
                                        description.value(),
                                        amount.value(),
                                        nullPaymentContextCode.value()
                                )
                        ),
                        faultCode,
                        faultCodeString,
                        Transaction.ClientId.CHECKOUT
                )
        );

        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = new TransactionAuthorizationRequestedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationRequestData(
                        amount.value(),
                        10,
                        "paymentInstrumentId",
                        "pspId",
                        "paymentTypeCode",
                        "brokerName",
                        "pspChannelCode",
                        "paymentMethodName",
                        "pspBusinessName",
                        "authorizationRequestId"
                )
        );

        TransactionAuthorizationCompletedEvent authorizationCompletedEvent = new TransactionAuthorizationCompletedEvent(
                transactionId.value().toString(),
                new TransactionAuthorizationCompletedData(
                        "authorizationCode",
                        AuthorizationResultDto.OK
                )
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
        Mockito.when(eventStoreRepository.findByTransactionId(transactionId.value().toString())).thenReturn(events);

        /* test */
        StepVerifier.create(updateStatusHandler.handle(requestStatusCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }
}
