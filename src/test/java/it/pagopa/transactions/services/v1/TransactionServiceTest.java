package it.pagopa.transactions.services.v1;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.PaymentTransferInformation;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestedEvent;
import it.pagopa.ecommerce.commons.documents.v2.activation.EmptyTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.RedirectTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.domain.v2.*;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.v2.ReactivePaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManager;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManagerTest;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.client.WalletClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.configurations.AzureStorageConfig;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.AutoConfigureDataRedis;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Stream;

import static it.pagopa.ecommerce.commons.v2.TransactionTestUtils.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@AutoConfigureDataRedis
class TransactionServiceTest {

    private final TransactionsViewRepository transactionsViewRepository = Mockito
            .mock(TransactionsViewRepository.class);
    @Autowired
    private UUIDUtils uuidUtils;
    private final EcommercePaymentMethodsClient ecommercePaymentMethodsClient = Mockito
            .mock(EcommercePaymentMethodsClient.class);

    private final WalletClient walletClient = Mockito
            .mock(WalletClient.class);

    private final PaymentGatewayClient paymentGatewayClient = Mockito.mock(PaymentGatewayClient.class);

    private final NodeForPspClient nodeForPspClient = Mockito.mock(NodeForPspClient.class);

    private final AzureStorageConfig azureStorageConfig = new AzureStorageConfig();

    private final QueueAsyncClient queueAsyncClientClosureRetryV1 = Mockito.mock(QueueAsyncClient.class);

    private final QueueAsyncClient queueAsyncClientRefundV1 = Mockito.mock(QueueAsyncClient.class);

    private final QueueAsyncClient queueAsyncClientClosureRetryV2 = Mockito.mock(QueueAsyncClient.class);

    private final QueueAsyncClient queueAsyncClientRefundV2 = Mockito.mock(QueueAsyncClient.class);

    private final it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler transactionActivateHandlerV2 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler.class);
    private final it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler transactionCancelHandlerV2 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler.class);
    private final it.pagopa.transactions.commands.handlers.v2.TransactionRequestAuthorizationHandler transactionRequestAuthorizationHandlerV2 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionRequestAuthorizationHandler.class);
    private final it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandlerV2 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler.class);
    private final it.pagopa.transactions.commands.handlers.v2.TransactionRequestUserReceiptHandler transactionUpdateStatusHandlerV2 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionRequestUserReceiptHandler.class);
    private final it.pagopa.transactions.commands.handlers.v2.TransactionSendClosureRequestHandler transactionSendClosureRequestHandler = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionSendClosureRequestHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.AuthorizationRequestProjectionHandler authorizationProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.AuthorizationRequestProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.TransactionUserReceiptProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.ClosureRequestedProjectionHandler closureRequestedProjectionHandler = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.ClosureRequestedProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.TransactionsActivationProjectionHandler transactionsActivationProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.TransactionsActivationProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.CancellationRequestProjectionHandler cancellationRequestProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.CancellationRequestProjectionHandler.class);
    private final TransactionsEventStoreRepository transactionsEventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    @Captor
    private ArgumentCaptor<TransactionRequestAuthorizationCommand> commandArgumentCaptor;

    private final TransactionsUtils transactionsUtils = Mockito.mock(TransactionsUtils.class);

    private final AuthRequestDataUtils authRequestDataUtils = Mockito.mock(AuthRequestDataUtils.class);

    private final TracingUtils tracingUtils = Mockito.mock(TracingUtils.class);

    private final ConfidentialDataManager confidentialDataManager = ConfidentialDataManagerTest.getMock();

    private final ConfidentialMailUtils confidentialMailUtils = new ConfidentialMailUtils(confidentialDataManager);

    private final ReactivePaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper = Mockito
            .mock(ReactivePaymentRequestInfoRedisTemplateWrapper.class);

    private final UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils = Mockito
            .mock(UpdateTransactionStatusTracerUtils.class);

    private final Map<String, String> npgAuthorizationErrorCodeMapping = new HashMap<>();

    private final Set<String> ecommerceFinalStates = Set.of(
            "NOTIFIED_OK",
            "NOTIFIED_KO",
            "NOTIFICATION_ERROR",
            "NOTIFICATION_REQUESTED",
            "EXPIRED",
            "REFUNDED",
            "CANCELED",
            "UNAUTHORIZED",
            "REFUND_ERROR",
            "REFUND_REQUESTED",
            "CANCELLATION_EXPIRED"
    );

    private final Set<String> ecommercePossibleFinalStates = Set
            .of("AUTHORIZATION_COMPLETED", "CLOSURE_REQUESTED", "CLOSURE_ERROR");

    private final TransactionsService transactionsServiceV1 = new TransactionsService(
            transactionActivateHandlerV2,
            transactionRequestAuthorizationHandlerV2,
            transactionUpdateAuthorizationHandlerV2,
            transactionSendClosureRequestHandler,
            transactionUpdateStatusHandlerV2,
            transactionCancelHandlerV2,
            authorizationProjectionHandlerV2,
            authorizationUpdateProjectionHandlerV2,
            closureRequestedProjectionHandler,
            cancellationRequestProjectionHandlerV2,
            transactionUserReceiptProjectionHandlerV2,
            transactionsActivationProjectionHandlerV2,
            transactionsViewRepository,
            ecommercePaymentMethodsClient,
            walletClient,
            uuidUtils,
            transactionsUtils,
            transactionsEventStoreRepository,
            10,
            paymentRequestInfoRedisTemplateWrapper,
            confidentialMailUtils,
            updateTransactionStatusTracerUtils,
            npgAuthorizationErrorCodeMapping,
            ecommerceFinalStates,
            ecommercePossibleFinalStates
    );

    private final TransactionsService transactionsServiceV2 = new TransactionsService(
            transactionActivateHandlerV2,
            transactionRequestAuthorizationHandlerV2,
            transactionUpdateAuthorizationHandlerV2,
            transactionSendClosureRequestHandler,
            transactionUpdateStatusHandlerV2,
            transactionCancelHandlerV2,
            authorizationProjectionHandlerV2,
            authorizationUpdateProjectionHandlerV2,
            closureRequestedProjectionHandler,
            cancellationRequestProjectionHandlerV2,
            transactionUserReceiptProjectionHandlerV2,
            transactionsActivationProjectionHandlerV2,
            transactionsViewRepository,
            ecommercePaymentMethodsClient,
            walletClient,
            uuidUtils,
            transactionsUtils,
            transactionsEventStoreRepository,
            10,
            paymentRequestInfoRedisTemplateWrapper,
            confidentialMailUtils,
            updateTransactionStatusTracerUtils,
            npgAuthorizationErrorCodeMapping,
            ecommerceFinalStates,
            ecommercePossibleFinalStates
    );

    @Test
    void shouldHandleNewTransactionTransactionActivatedV2Event() {
        ClientIdDto clientIdDto = ClientIdDto.CHECKOUT;
        UUID TEST_SESSION_TOKEN = UUID.randomUUID();
        UUID TEST_CPP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(EMAIL_STRING)
                .addPaymentNoticesItem(new PaymentNoticeInfoDto().rptId(TransactionTestUtils.RPT_ID).amount(100));

        it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData transactionActivatedData = new it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData();
        transactionActivatedData.setEmail(TransactionTestUtils.EMAIL);
        transactionActivatedData
                .setPaymentNotices(
                        List.of(
                                new PaymentNotice(
                                        TransactionTestUtils.PAYMENT_TOKEN,
                                        null,
                                        "dest",
                                        0,
                                        TEST_CPP.toString(),
                                        List.of(new PaymentTransferInformation("77777777777", false, 0, null)),
                                        false,
                                        null,
                                        null
                                )
                        )
                );

        it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent transactionActivatedEvent = new it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent(
                new TransactionId(TRANSACTION_ID).value(),
                transactionActivatedData
        );

        Tuple2<Mono<BaseTransactionEvent<?>>, String> response = Tuples
                .of(
                        Mono.just(transactionActivatedEvent),
                        TEST_SESSION_TOKEN.toString()
                );

        it.pagopa.ecommerce.commons.domain.v2.TransactionActivated transactionActivated = new it.pagopa.ecommerce.commons.domain.v2.TransactionActivated(
                new TransactionId(TRANSACTION_ID),
                Arrays.asList(
                        new it.pagopa.ecommerce.commons.domain.v2.PaymentNotice(
                                new PaymentToken(TransactionTestUtils.PAYMENT_TOKEN),
                                new RptId(TransactionTestUtils.RPT_ID),
                                new TransactionAmount(0),
                                new TransactionDescription("desc"),
                                new PaymentContextCode(TEST_CPP.toString()),
                                List.of(new PaymentTransferInfo("77777777777", false, 100, null)),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                TransactionTestUtils.EMAIL,
                "faultCode",
                "faultCodeString",
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new EmptyTransactionGatewayActivationData(),
                null
        );

        /*
         * Preconditions
         */
        Mockito.when(transactionActivateHandlerV2.handle(any()))
                .thenReturn(Mono.just(response));
        Mockito.when(transactionsActivationProjectionHandlerV2.handle(transactionActivatedEvent))
                .thenReturn(Mono.just(transactionActivated));
        Mockito.when(transactionsUtils.convertEnumerationV1(any()))
                .thenCallRealMethod();
        StepVerifier
                .create(
                        transactionsServiceV2.newTransaction(
                                transactionRequestDto,
                                clientIdDto,
                                new TransactionId(transactionActivatedEvent.getTransactionId())
                        )
                )
                .expectNextMatches(
                        res -> res.getPayments().get(0).getRptId()
                                .equals(transactionRequestDto.getPaymentNotices().get(0).getRptId())
                                && res.getIdCart().equals("idCart")
                                && res.getStatus().equals(TransactionStatusDto.ACTIVATED)
                                && res.getClientId()
                                        .equals(NewTransactionResponseDto.ClientIdEnum.valueOf(clientIdDto.getValue()))
                                && !res.getTransactionId().isEmpty()
                                && !res.getAuthToken().isEmpty()
                )
                .verifyComplete();

    }

    private static Stream<Arguments> koAuthRequestPatchMethodSource() {
        return Stream.of(
                Arguments.of(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.WRONG_TRANSACTION_STATUS,
                        new AlreadyProcessedException(new TransactionId(TransactionTestUtils.TRANSACTION_ID))
                ),
                Arguments.of(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.TRANSACTION_NOT_FOUND,
                        new TransactionNotFoundException(TransactionTestUtils.PAYMENT_TOKEN)
                ),
                Arguments.of(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.PROCESSING_ERROR,
                        new RuntimeException("Error processing request")
                )
        );
    }

    private static Stream<Arguments> authRequestMethodSource() {
        return Stream.of(
                Arguments.of(
                        it.pagopa.ecommerce.commons.v2.TransactionTestUtils.transactionAuthorizationRequestedEvent(
                                TransactionAuthorizationRequestData.PaymentGateway.NPG,
                                new NpgTransactionGatewayAuthorizationRequestedData()
                        ),
                        new UpdateAuthorizationRequestDto()
                                .outcomeGateway(
                                        new OutcomeNpgGatewayDto()
                                                .authorizationCode("authorizationCode")
                                                .operationResult(OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED)
                                ).timestampOperation(OffsetDateTime.now()),
                        UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.NPG,
                        "EXECUTED"
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.v2.TransactionTestUtils.transactionAuthorizationRequestedEvent(
                                TransactionAuthorizationRequestData.PaymentGateway.REDIRECT,
                                new RedirectTransactionGatewayAuthorizationRequestedData(
                                        LOGO_URI,
                                        REDIRECT_AUTHORIZATION_TIMEOUT
                                )
                        ),
                        new UpdateAuthorizationRequestDto()
                                .outcomeGateway(
                                        new OutcomeRedirectGatewayDto()
                                                .authorizationCode("authorizationCode")
                                                .outcome(AuthorizationOutcomeDto.OK)
                                                .pspId(it.pagopa.ecommerce.commons.v2.TransactionTestUtils.PSP_ID)
                                                .pspTransactionId(
                                                        it.pagopa.ecommerce.commons.v2.TransactionTestUtils.AUTHORIZATION_REQUEST_ID
                                                )
                                ).timestampOperation(OffsetDateTime.now()),
                        UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.REDIRECT,
                        "OK"
                )
        );
    }

    @ParameterizedTest
    @MethodSource("authRequestMethodSource")
    void shouldTraceTransactionUpdateStatusOK(
                                              TransactionAuthorizationRequestedEvent transactionAuthorizationRequestedEvent,
                                              UpdateAuthorizationRequestDto updateAuthorizationRequest,
                                              UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger trigger,
                                              String expectedOutcome
    ) {
        Hooks.onOperatorDebug();
        TransactionId transactionId = new TransactionId(UUID.randomUUID());
        String expectedPaymentMethodTypeCode = transactionAuthorizationRequestedEvent.getData().getPaymentTypeCode();
        String expectedPspId = transactionAuthorizationRequestedEvent.getData().getPspId();

        it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent transactionActivatedEvent = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivateEvent();

        it.pagopa.ecommerce.commons.domain.v2.TransactionActivated transactionActivated = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivated(transactionActivatedEvent.getCreationDate());
        it.pagopa.ecommerce.commons.domain.v2.TransactionWithRequestedAuthorization transactionWithRequestedAuthorization = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionWithRequestedAuthorization(transactionAuthorizationRequestedEvent, transactionActivated);
        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, transactionAuthorizationRequestedEvent));

        it.pagopa.ecommerce.commons.documents.v2.Transaction closureRequestedTransaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        ZonedDateTime.now()
                );

        TransactionInfoDto expected = new TransactionInfoDto()
                .payments(
                        closureRequestedTransaction.getPaymentNotices().stream().map(
                                paymentNotice -> new PaymentInfoDto()
                                        .amount(paymentNotice.getAmount())
                                        .reason(paymentNotice.getDescription())
                                        .paymentToken(paymentNotice.getPaymentToken())
                                        .rptId(paymentNotice.getRptId())
                        )
                                .toList()
                )
                .transactionId(closureRequestedTransaction.getTransactionId())
                .status(TransactionStatusDto.CLOSURE_REQUESTED);

        /* preconditions */
        Mockito.when(transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(events);
        Mockito.when(
                transactionsUtils.reduceEvents(
                        any(),
                        eq(new it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction()),
                        any(),
                        any()
                )
        ).thenReturn(Mono.just(new it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction()));
        Mockito.when(
                transactionsUtils.reduceEvents(
                        any(),
                        eq(new it.pagopa.ecommerce.commons.domain.v2.EmptyTransaction()),
                        any(),
                        any()
                )
        ).thenReturn(Mono.just(transactionWithRequestedAuthorization));

        Mockito.when(transactionsUtils.convertEnumerationV1(any())).thenCallRealMethod();
        Mockito.when(transactionsUtils.getPspId(any(BaseTransaction.class))).thenCallRealMethod();
        Mockito.when(transactionsUtils.getPaymentMethodTypeCode(any(BaseTransaction.class))).thenCallRealMethod();

        Mockito.when(transactionsEventStoreRepository.findByTransactionIdAndEventCode(any(), any()))
                .thenReturn(Mono.empty());

        Mockito.when(transactionUpdateAuthorizationHandlerV2.handle(any())).thenReturn(
                Mono.just(transactionAuthorizationCompletedEvent(new NpgTransactionGatewayAuthorizationData()))
        );

        Mockito.when(authorizationUpdateProjectionHandlerV2.handle(any())).thenReturn(Mono.just(transactionActivated));

        Mockito.when(transactionSendClosureRequestHandler.handle(any()))
                .thenReturn(Mono.just(transactionClosureRequestedEvent()));
        Mockito.when(closureRequestedProjectionHandler.handle(any()))
                .thenReturn(Mono.just(closureRequestedTransaction));

        Mockito.when(transactionsUtils.getPspId(any(BaseTransaction.class))).thenCallRealMethod();
        Mockito.when(transactionsUtils.getPaymentMethodTypeCode(any(BaseTransaction.class))).thenCallRealMethod();
        Mockito.when(transactionsUtils.isWalletPayment(any(BaseTransaction.class))).thenCallRealMethod();

        /* test */
        StepVerifier.create(
                transactionsServiceV1
                        .updateTransactionAuthorization(
                                transactionId.uuid(),
                                updateAuthorizationRequest
                        )
        )
                .assertNext(actual -> assertEquals(expected, actual))
                .verifyComplete();

        UpdateTransactionStatusTracerUtils.StatusUpdateInfo expectedStatusUpdateInfo = new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdate(
                trigger,
                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.OK,
                new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdateContext(
                        expectedPspId,
                        new UpdateTransactionStatusTracerUtils.GatewayOutcomeResult(
                                expectedOutcome,
                                Optional.empty()
                        ),
                        expectedPaymentMethodTypeCode,
                        transactionActivated.getClientId(),
                        false
                )
        );
        verify(updateTransactionStatusTracerUtils, times(1)).traceStatusUpdateOperation(
                expectedStatusUpdateInfo
        );
    }
}
