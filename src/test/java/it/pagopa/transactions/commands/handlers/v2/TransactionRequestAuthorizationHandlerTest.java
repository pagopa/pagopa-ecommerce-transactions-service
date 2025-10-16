package it.pagopa.transactions.commands.handlers.v2;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionEvent;
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.RedirectTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v2.*;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenRequestDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldsDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.StateResponseDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.WorkflowStateDto;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.queues.TracingUtilsTests;
import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.ReactiveExclusiveLockDocumentWrapper;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlResponseDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.JwtTokenIssuerClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.LockNotAcquiredException;
import it.pagopa.transactions.repositories.TransactionTemplateWrapper;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.PaymentSessionData;
import it.pagopa.transactions.utils.Queues;
import it.pagopa.transactions.utils.TransactionsUtils;
import org.apache.commons.codec.binary.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

import static it.pagopa.transactions.commands.handlers.TransactionAuthorizationHandlerCommon.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionRequestAuthorizationHandlerTest {

    private static final String CHECKOUT_BASE_PATH = "checkoutUri";
    private static final String CHECKOUT_NPG_GDI_PATH = "http://checkout.pagopa.it/gdi-check";
    private static final String CHECKOUT_OUTCOME_PATH = "http://checkout.pagopa.it/esito";
    private static final String NPG_URL_IFRAME = "http://iframe";
    private static final String NPG_GDI_FRAGMENT = "#gdiIframeUrl=";
    private static final String NPG_WALLET_GDI_CHECK_PATH = "/ecommerce-fe/gdi-check#gdiIframeUrl=";
    private static final String NPG_WALLET_ESITO_PATH = "/ecommerce-fe/esito#";
    private it.pagopa.transactions.commands.handlers.v2.TransactionRequestAuthorizationHandler requestAuthorizationHandler;

    @Mock
    private PaymentGatewayClient paymentGatewayClient;

    @Mock
    private TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository;

    private TransactionsEventStoreRepository<Object> eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final TransactionsUtils transactionsUtils = new TransactionsUtils(eventStoreRepository, "3020");

    TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);

    @Mock
    private EcommercePaymentMethodsClient paymentMethodsClient;

    @Mock
    private TransactionTemplateWrapper transactionTemplateWrapper;

    @Captor
    private ArgumentCaptor<TransactionEvent<TransactionAuthorizationRequestData>> eventStoreCaptor;
    @Captor
    private ArgumentCaptor<Duration> durationArgumentCaptor;

    private final TracingUtils tracingUtils = TracingUtilsTests.getMock();

    private final int transientQueueEventsTtlSeconds = 30;
    private final int authRequestEventVisibilityTimeoutSeconds = 0;

    private PaymentSessionData.ContextualOnboardDetails contextualOnboardDetailsFor(TransactionId transactionId) {
        return new PaymentSessionData.ContextualOnboardDetails(
                transactionId.value(),
                100L,
                "orderId"
        );
    }

    private final OpenTelemetryUtils openTelemetryUtils = Mockito.mock(OpenTelemetryUtils.class);

    private final QueueAsyncClient transactionAuthorizationRequestedQueueAsyncClient = Mockito
            .mock(QueueAsyncClient.class);

    private static final JwtTokenIssuerClient jwtTokenIssuerClient = Mockito.mock(JwtTokenIssuerClient.class);

    private final UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils = Mockito
            .mock(UpdateTransactionStatusTracerUtils.class);

    private final ReactiveExclusiveLockDocumentWrapper exclusiveLockDocumentWrapper = Mockito
            .mock(ReactiveExclusiveLockDocumentWrapper.class);

    @BeforeEach
    public void init() {
        requestAuthorizationHandler = new TransactionRequestAuthorizationHandler(
                paymentGatewayClient,
                transactionEventStoreRepository,
                transactionsUtils,
                CHECKOUT_BASE_PATH,
                CHECKOUT_NPG_GDI_PATH,
                CHECKOUT_OUTCOME_PATH,
                paymentMethodsClient,
                transactionTemplateWrapper,
                transactionAuthorizationRequestedQueueAsyncClient,
                transientQueueEventsTtlSeconds,
                authRequestEventVisibilityTimeoutSeconds,
                tracingUtils,
                openTelemetryUtils,
                jwtTokenIssuerClient,
                TOKEN_VALIDITY_TIME_SECONDS,
                updateTransactionStatusTracerUtils,
                exclusiveLockDocumentWrapper
        );
    }

    @Test
    void shouldSaveAuthorizationEventNpgCardsRedirectToExternalDomain() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.empty()
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN).url(NPG_URL_IFRAME);

        /* preconditions */
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId())
                .authorizationUrl(NPG_URL_IFRAME);
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();
        verify(transactionEventStoreRepository, times(1)).save(any());
        verify(transactionAuthorizationRequestedQueueAsyncClient, times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                authorizationData.sessionId().get(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldSaveAuthorizationEventNpgCardsPaymentCompleteCheckoutClient() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.PAYMENT_COMPLETE)
                .fieldSet(
                        new FieldsDto()
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        /* preconditions */
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId())
                .authorizationUrl(CHECKOUT_OUTCOME_PATH);
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(value -> requestAuthResponseDtoComparator(value, responseDto))
                .verifyComplete();

        verify(transactionEventStoreRepository, times(1)).save(any());
        verify(transactionAuthorizationRequestedQueueAsyncClient, times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                authorizationData.sessionId().get(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldSaveAuthorizationEventNpgCardsContextualPaymentCompleteIOClient() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String walletId = "walletId";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.IO,
                null,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                new WalletAuthRequestDetailsDto().walletId(walletId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );
        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.PAYMENT_COMPLETE)
                .fieldSet(
                        new FieldsDto()
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        FieldsDto npgBuildSessionResponse = new FieldsDto().sessionId("sessionId")
                .state(WorkflowStateDto.READY_FOR_PAYMENT).securityToken("securityToken");
        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);

        /* preconditions */
        when(paymentGatewayClient.requestNpgBuildSession(any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(Mono.just(responseRequestNpgBuildSession));
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent(
                new NpgTransactionGatewayActivationData(orderId, correlationId)
        );
        transactionActivatedEvent.getData().setClientId(Transaction.ClientId.IO);
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                transactionActivatedEvent
                        )
                );
        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        transactionActivatedEvent
                )
        );
        when(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .thenReturn(Mono.just(createTokenResponseDto));

        when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));

        when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId("orderId")
                .authorizationUrl(
                        new StringBuilder(NPG_WALLET_ESITO_PATH).append("clientId=").append(Transaction.ClientId.IO)
                                .append("&transactionId=").append(transactionId.value()).toString()
                                .concat("&sessionToken=").concat(MOCK_JWT)
                );
        Hooks.onOperatorDebug();
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(value -> requestAuthResponseDtoComparator(value, responseDto))
                .verifyComplete();

        verify(transactionEventStoreRepository, times(1)).save(any());
        verify(paymentGatewayClient, times(1)).requestNpgBuildSession(any(), any(), anyBoolean(), any(), any(), any());
        verify(transactionAuthorizationRequestedQueueAsyncClient, times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertTrue(savedEvent.getData().getIsContextualOnboard());
        assertEquals(
                authorizationData.sessionId().get(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldSaveAuthorizationEventNpgCardsPaymentCompleteIOClient() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.IO,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.PAYMENT_COMPLETE)
                .fieldSet(
                        new FieldsDto()
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        /* preconditions */
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent(
                new NpgTransactionGatewayActivationData(orderId, correlationId)
        );
        transactionActivatedEvent.getData().setClientId(Transaction.ClientId.IO);
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                transactionActivatedEvent
                        )
                );
        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        transactionActivatedEvent
                )
        );
        when(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .thenReturn(Mono.just(createTokenResponseDto));

        when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId())
                .authorizationUrl(
                        new StringBuilder(NPG_WALLET_ESITO_PATH).append("clientId=").append(Transaction.ClientId.IO)
                                .append("&transactionId=").append(transactionId.value()).toString()
                                .concat("&sessionToken=").concat(MOCK_JWT)
                );
        Hooks.onOperatorDebug();
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(value -> requestAuthResponseDtoComparator(value, responseDto))
                .verifyComplete();

        verify(transactionEventStoreRepository, times(1)).save(any());
        verify(transactionAuthorizationRequestedQueueAsyncClient, times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertTrue(savedEvent.getData().getIsContextualOnboard());
        assertEquals(
                authorizationData.sessionId().get(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldSaveAuthorizationEventGdiVerification() {

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.GDI_VERIFICATION)
                .fieldSet(
                        new FieldsDto()
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        /* preconditions */
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId())
                .authorizationUrl(
                        CHECKOUT_NPG_GDI_PATH + NPG_GDI_FRAGMENT + Base64.encodeBase64URLSafeString(
                                NPG_URL_IFRAME
                                        .getBytes(StandardCharsets.UTF_8)
                        )
                );
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        Hooks.onOperatorDebug();
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(value -> requestAuthResponseDtoComparator(value, responseDto))
                .verifyComplete();

        verify(transactionEventStoreRepository, times(1)).save(any());
        verify(transactionAuthorizationRequestedQueueAsyncClient, times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertTrue(savedEvent.getData().getIsContextualOnboard());
        assertEquals(
                authorizationData.sessionId().get(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldSaveAuthorizationEventBadGatewayException() {

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.CARD_DATA_COLLECTION);

        /* preconditions */
        when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof BadGatewayException)
                .verify();

        verify(paymentMethodsClient, times(1)).updateSession(any(), any(), any());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldRejectAlreadyProcessedTransaction() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                faultCode,
                faultCodeString,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "paymentTypeCode",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                null,
                Optional.empty(),
                Optional.empty(),
                "VISA",
                null,
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(),
                        TransactionTestUtils.transactionAuthorizationRequestedEvent()
                )
        );
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value().toString()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(),
                                TransactionTestUtils.transactionAuthorizationRequestedEvent()
                        )
                );

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        verify(transactionEventStoreRepository, times(0)).save(any());
    }

    @Test
    void shouldRejectBadGateway() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                faultCode,
                faultCodeString,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("VPOS")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.empty(),
                Optional.empty(),
                "VISA",
                new CardsAuthRequestDetailsDto(),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(TransactionTestUtils.transactionActivateEvent())
        );

        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        // TODO Check this null value in this mocks
        when(paymentMethodsClient.updateSession("paymentInstrumentId", null, transactionId.value()))
                .thenReturn(Mono.empty());
        when(paymentGatewayClient.requestNpgCardsAuthorization(eq(authorizationData), eq(null)))
                .thenReturn(Mono.empty());
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof InvalidRequestException)
                .verify();

        verify(transactionEventStoreRepository, times(0)).save(any());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldReturnErrorForMissingStateInNPGConfirmPaymentResponse() {

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        StateResponseDto stateResponseDto = new StateResponseDto();

        /* preconditions */
        when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(
                        exception -> exception instanceof BadGatewayException bge &&
                                bge.getHttpStatus() == HttpStatus.BAD_GATEWAY &&
                                bge.getDetail().equals("Invalid NPG confirm payment, state response null!")
                )
                .verify();

        verify(transactionEventStoreRepository, times(0)).save(any());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldReturnErrorForNPGGdiVerificationResponseStateWithNoFieldSetReceived() {

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.GDI_VERIFICATION);

        /* preconditions */
        when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(
                        exception -> exception instanceof BadGatewayException bge &&
                                bge.getHttpStatus() == HttpStatus.BAD_GATEWAY &&
                                bge.getDetail().equals(
                                        "Invalid NPG response for state GDI_VERIFICATION, no fieldSet.field received, expected 1"
                                )
                )
                .verify();

        verify(transactionEventStoreRepository, times(0)).save(any());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldReturnErrorForNPGGdiVerificationResponseStateWithNoFieldsReceivedIntoFieldSet() {

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                new CardsAuthRequestDetailsDto().orderId("orderId"),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.GDI_VERIFICATION)
                .fieldSet(new FieldsDto());

        /* preconditions */
        when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(
                        exception -> exception instanceof BadGatewayException bge &&
                                bge.getHttpStatus() == HttpStatus.BAD_GATEWAY &&
                                bge.getDetail().equals(
                                        "Invalid NPG response for state GDI_VERIFICATION, no fieldSet.field received, expected 1"
                                )
                )
                .verify();

        verify(transactionEventStoreRepository, times(0)).save(any());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldReturnErrorForNPGGdiVerificationResponseStateWithNullSrcReceivedInField0() {

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.GDI_VERIFICATION)
                .fieldSet(new FieldsDto().addFieldsItem(new FieldDto()));

        /* preconditions */
        when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(
                        exception -> exception instanceof BadGatewayException bge &&
                                bge.getHttpStatus() == HttpStatus.BAD_GATEWAY &&
                                bge.getDetail().equals(
                                        "Invalid NPG response for state GDI_VERIFICATION, fieldSet.field[0].src is null"
                                )
                )
                .verify();

        verify(transactionEventStoreRepository, times(0)).save(any());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldReturnErrorForNPGRedirectToExternalDomainStateResponseWithoutPopulatedUrl() {

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN);

        /* preconditions */
        when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(
                        exception -> exception instanceof BadGatewayException bge &&
                                bge.getHttpStatus() == HttpStatus.BAD_GATEWAY &&
                                bge.getDetail().equals(
                                        "Invalid NPG response for state REDIRECTED_TO_EXTERNAL_DOMAIN, response.url is null"
                                )
                )
                .verify();

        verify(transactionEventStoreRepository, times(0)).save(any());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldSaveAuthorizationEventNpgCardsRedirectToExternalDomainSavingReceivedSessionIdInEvent() {

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                new CardsAuthRequestDetailsDto().orderId("orderId"),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN).url(NPG_URL_IFRAME)
                .fieldSet(new FieldsDto().sessionId(TransactionTestUtils.NPG_CONFIRM_PAYMENT_SESSION_ID));

        /* preconditions */
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId())
                .authorizationUrl(NPG_URL_IFRAME);
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        verify(transactionEventStoreRepository, times(1)).save(any());
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertTrue(savedEvent.getData().getIsContextualOnboard());
        assertEquals(
                authorizationData.sessionId().get(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertEquals(
                TransactionTestUtils.NPG_CONFIRM_PAYMENT_SESSION_ID,
                npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId()
        );
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldSaveAuthorizationEventNpgCardsPaymentCompleteSavingReceivedSessionIdInEvent() {

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.PAYMENT_COMPLETE)
                .fieldSet(
                        new FieldsDto()
                                .sessionId(TransactionTestUtils.NPG_CONFIRM_PAYMENT_SESSION_ID)
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        /* preconditions */
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId())
                .authorizationUrl(CHECKOUT_OUTCOME_PATH);
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(
                        value -> value.getAuthorizationRequestId().equals(responseDto.getAuthorizationRequestId())
                )
                .verifyComplete();

        verify(transactionEventStoreRepository, times(1)).save(any());
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertTrue(savedEvent.getData().getIsContextualOnboard());
        assertEquals(
                authorizationData.sessionId().get(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertEquals(
                TransactionTestUtils.NPG_CONFIRM_PAYMENT_SESSION_ID,
                npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId()
        );
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldSaveAuthorizationEventGdiVerificationSavingReceivedSessionIdInEvent() {

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.GDI_VERIFICATION)
                .fieldSet(
                        new FieldsDto()
                                .sessionId(TransactionTestUtils.NPG_CONFIRM_PAYMENT_SESSION_ID)
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        /* preconditions */
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId())
                .authorizationUrl(
                        CHECKOUT_NPG_GDI_PATH + NPG_GDI_FRAGMENT + Base64.encodeBase64URLSafeString(
                                NPG_URL_IFRAME
                                        .getBytes(StandardCharsets.UTF_8)
                        )
                );
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(value -> requestAuthResponseDtoComparator(value, responseDto))
                .verifyComplete();

        verify(transactionEventStoreRepository, times(1)).save(any());
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertTrue(savedEvent.getData().getIsContextualOnboard());
        assertEquals(
                authorizationData.sessionId().get(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertEquals(
                TransactionTestUtils.NPG_CONFIRM_PAYMENT_SESSION_ID,
                npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId()
        );
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldSaveAuthorizationEventGdiVerificationForWallet() {
        String walletId = UUID.randomUUID().toString();
        String contractId = "contractId";
        String sessionId = "sessionId";
        String orderId = "oderId";

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        Transaction.ClientId clientId = Transaction.ClientId.IO;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String correlationId = UUID.randomUUID().toString();
        String idBundle = UUID.randomUUID().toString();

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                clientId,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.empty(),
                Optional.of(contractId),
                "VISA",
                new WalletAuthRequestDetailsDto().detailType("wallet").walletId(walletId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                idBundle,
                Optional.empty()
        );

        AuthorizationRequestData authorizationDataAfterBuildSession = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(sessionId),
                Optional.of(contractId),
                "VISA",
                new WalletAuthRequestDetailsDto().detailType("wallet").walletId(walletId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                idBundle,
                Optional.empty()
        );
        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent(
                new NpgTransactionGatewayActivationData(orderId, correlationId)
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(transactionActivatedEvent)
        );

        FieldsDto npgBuildSessionResponse = new FieldsDto().sessionId(sessionId)
                .state(WorkflowStateDto.READY_FOR_PAYMENT).securityToken("securityToken");

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.GDI_VERIFICATION)
                .fieldSet(
                        new FieldsDto()
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);
        /* preconditions */
        when(
                paymentGatewayClient
                        .requestNpgBuildSession(
                                authorizationData,
                                correlationId,
                                true,
                                clientId.name(),
                                null,
                                UUID.fromString(TransactionTestUtils.USER_ID)
                        )
        )
                .thenReturn(Mono.just(responseRequestNpgBuildSession));
        when(
                paymentGatewayClient.requestNpgCardsAuthorization(authorizationDataAfterBuildSession, correlationId)
        )
                .thenReturn(Mono.just(stateResponseDto));

        transactionActivatedEvent.getData().setClientId(clientId);
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                transactionActivatedEvent
                        )
                );

        when(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .thenReturn(Mono.just(createTokenResponseDto));

        when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));

        when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(orderId)
                .authorizationUrl(
                        NPG_WALLET_GDI_CHECK_PATH + Base64.encodeBase64URLSafeString(
                                NPG_URL_IFRAME
                                        .getBytes(StandardCharsets.UTF_8)
                        ).concat("&clientId=IO&transactionId=").concat(authorizationData.transactionId().value())
                                .concat("&sessionToken=").concat(MOCK_JWT)
                );
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));
        when(transactionTemplateWrapper.save(any())).thenReturn(Mono.empty());

        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(
                        value -> value.getAuthorizationRequestId().equals(responseDto.getAuthorizationRequestId())
                )
                .verifyComplete();

        verify(transactionEventStoreRepository, times(1)).save(any());
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                "sessionId",
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        assertEquals(walletId, npgTransactionGatewayAuthorizationRequestedData.getWalletInfo().getWalletId());
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getWalletInfo().getWalletDetails());
        verify(transactionTemplateWrapper, times(1)).save(any());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldThrowExceptionNoGatewayMatchesDuringAuthorizationForWallet() {
        String contractId = "contractId";
        String sessionId = "sessionId";
        String orderId = "oderId";

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.empty(),
                Optional.of(contractId),
                "VISA",
                Mockito.mock(RequestAuthorizationRequestDetailsDto.class),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(TransactionTestUtils.transactionActivateEvent())
        );
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));
        /* preconditions */
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof InvalidRequestException)
                .verify();

        verify(paymentGatewayClient, times(0))
                .requestNpgBuildSession(
                        any(),
                        any(),
                        eq(true),
                        eq(Transaction.ClientId.IO.name()),
                        any(),
                        eq(UUID.fromString(TransactionTestUtils.USER_ID))
                );
        verify(paymentGatewayClient, times(0)).requestNpgCardsAuthorization(any(), any());
        verify(transactionEventStoreRepository, times(0)).save(any());
        verify(transactionTemplateWrapper, times(0)).save(any());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldPerformTransactionAuthorizationForWalletApmPaymentMethodUsingOrderBuildNpgCall() {
        String walletId = UUID.randomUUID().toString();
        String contractId = "contractId";
        String sessionId = "sessionId";
        String orderId = "oderId";

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String correlationId = UUID.randomUUID().toString();
        Transaction.ClientId clientId = it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT;
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                clientId,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new NpgTransactionGatewayActivationData("orderId", correlationId),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "PPAL",
                "brokerName",
                "pspChannelCode",
                "PAYPAL",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.empty(),
                Optional.of(contractId),
                "VISA",
                new WalletAuthRequestDetailsDto().detailType("wallet").walletId(walletId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );
        String securityToken = "securityToken";
        FieldsDto npgBuildSessionResponse = new FieldsDto().sessionId(sessionId)
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN)
                .securityToken(securityToken)
                .sessionId(sessionId)
                .url("http://localhost/redirectionUrl");

        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);
        /* preconditions */
        when(
                paymentGatewayClient
                        .requestNpgBuildApmPayment(
                                authorizationData,
                                correlationId,
                                true,
                                clientId.name(),
                                null,
                                UUID.fromString(TransactionTestUtils.USER_ID)
                        )
        )
                .thenReturn(Mono.just(responseRequestNpgBuildSession));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));

        when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));
        when(transactionTemplateWrapper.save(any())).thenReturn(Mono.just(true));
        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(orderId)
                .authorizationUrl(npgBuildSessionResponse.getUrl());
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        verify(transactionEventStoreRepository, times(1)).save(any());
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(Boolean.TRUE, savedEvent.getData().getIsContextualOnboard());
        assertEquals(
                "sessionId",
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        assertEquals(walletId, npgTransactionGatewayAuthorizationRequestedData.getWalletInfo().getWalletId());
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getWalletInfo().getWalletDetails());
        verify(transactionTemplateWrapper, times(1)).save(any());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
        verify(transactionTemplateWrapper, times(1)).save(
                argThat(transactionCacheInfo -> {
                    assertEquals(
                            TransactionTestUtils.TRANSACTION_ID,
                            transactionCacheInfo.transactionId().value()
                    );
                    assertNotNull(transactionCacheInfo.walletPaymentInfo());
                    assertEquals(orderId, transactionCacheInfo.walletPaymentInfo().orderId());
                    assertEquals(securityToken, transactionCacheInfo.walletPaymentInfo().securityToken());
                    assertEquals(sessionId, transactionCacheInfo.walletPaymentInfo().sessionId());
                    return true;
                })
        );
    }

    @Test
    void shouldPerformTransactionAuthorizationForApmPaymentMethodUsingOrderBuildNpgCall() {
        String sessionId = "sessionId";
        String orderId = "oderId";

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "BPAY",
                "brokerName",
                "pspChannelCode",
                "PAYPAL",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.empty(),
                Optional.empty(),
                "BANCOMATPAY",
                new ApmAuthRequestDetailsDto().detailType("apm"),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );
        String securityToken = "securityToken";
        FieldsDto npgBuildSessionResponse = new FieldsDto().sessionId(sessionId)
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN)
                .securityToken(securityToken)
                .sessionId(sessionId)
                .url("http://localhost/redirectionUrl");

        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);
        /* preconditions */
        when(
                paymentGatewayClient.requestNpgBuildApmPayment(
                        authorizationData,
                        correlationId,
                        false,
                        Transaction.ClientId.CHECKOUT.name(),
                        null,
                        UUID.fromString(TransactionTestUtils.USER_ID)
                )
        )
                .thenReturn(Mono.just(responseRequestNpgBuildSession));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(

                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));

        when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));
        when(transactionTemplateWrapper.save(any())).thenReturn(Mono.just(true));

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(orderId)
                .authorizationUrl(npgBuildSessionResponse.getUrl());
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        verify(transactionEventStoreRepository, times(1)).save(any());
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(Boolean.TRUE, savedEvent.getData().getIsContextualOnboard());
        assertEquals(
                "sessionId",
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        verify(transactionTemplateWrapper, times(1)).save(any());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
        verify(transactionTemplateWrapper, times(1)).save(
                argThat(transactionCacheInfo -> {
                    assertEquals(
                            TransactionTestUtils.TRANSACTION_ID,
                            transactionCacheInfo.transactionId().value()
                    );
                    assertNotNull(transactionCacheInfo.walletPaymentInfo());
                    assertEquals(orderId, transactionCacheInfo.walletPaymentInfo().orderId());
                    assertEquals(securityToken, transactionCacheInfo.walletPaymentInfo().securityToken());
                    assertEquals(sessionId, transactionCacheInfo.walletPaymentInfo().sessionId());
                    return true;
                })
        );
    }

    @ParameterizedTest
    @ValueSource(strings = "")
    @NullSource
    void shouldReturnErrorPerformingTransactionAuthorizationForWalletApmForInvalidNpgReturnUrl(String wrongReturnUrl) {
        String walletId = UUID.randomUUID().toString();
        String contractId = "contractId";
        String sessionId = "sessionId";
        String orderId = "orderId";

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String correlationId = UUID.randomUUID().toString();
        Transaction.ClientId clientId = Transaction.ClientId.CHECKOUT;
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                clientId,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "PPAL",
                "brokerName",
                "pspChannelCode",
                "PAYPAL",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.empty(),
                Optional.of(contractId),
                "VISA",
                new WalletAuthRequestDetailsDto().detailType("wallet").walletId(walletId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        FieldsDto npgBuildSessionResponse = new FieldsDto().sessionId(sessionId)
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN)
                .securityToken("securityToken")
                .sessionId("sessionId")
                .url(wrongReturnUrl);

        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);
        /* preconditions */
        when(
                paymentGatewayClient
                        .requestNpgBuildApmPayment(
                                authorizationData,
                                correlationId,
                                true,
                                clientId.name(),
                                null,
                                UUID.fromString(TransactionTestUtils.USER_ID)
                        )
        )
                .thenReturn(Mono.just(responseRequestNpgBuildSession));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(

                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectError(BadGatewayException.class)
                .verify();

        verify(transactionEventStoreRepository, times(0)).save(any());
        verify(transactionTemplateWrapper, times(0)).save(any());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldHandleAuthorizationRequestForRedirectionPaymentGateway() {

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT)
                .details(new RedirectionAuthRequestDetailsDto());
        String expectedLogo = "http://localhost/logo";
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "RPIC",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "REDIRECT",
                Optional.empty(),
                Optional.empty(),
                null,
                new RedirectionAuthRequestDetailsDto(),
                expectedLogo,
                Optional.empty(),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(TransactionTestUtils.transactionActivateEvent())
        );

        RedirectUrlResponseDto redirectUrlResponseDto = new RedirectUrlResponseDto()
                .url("http://redirectUrl")
                .idTransaction(transactionId.value())
                .idPSPTransaction(TransactionTestUtils.AUTHORIZATION_REQUEST_ID)
                .amount(authorizationRequest.getAmount() + authorizationRequest.getFee())
                .timeout(TransactionTestUtils.REDIRECT_AUTHORIZATION_TIMEOUT);

        /* preconditions */
        when(paymentGatewayClient.requestRedirectUrlAuthorization(eq(authorizationData), any(), any()))
                .thenReturn(Mono.just(redirectUrlResponseDto));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));
        when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);

        /* test */
        requestAuthorizationHandler.handle(requestAuthorizationCommand).block();

        verify(transactionEventStoreRepository, times(1)).save(any());
        verify(transactionAuthorizationRequestedQueueAsyncClient, times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> authorizationRequestedEvent = eventStoreCaptor.getValue();
        TransactionAuthorizationRequestData authRequestedData = authorizationRequestedEvent.getData();
        assertTrue(
                authRequestedData
                        .getTransactionGatewayAuthorizationRequestedData() instanceof RedirectTransactionGatewayAuthorizationRequestedData
        );
        RedirectTransactionGatewayAuthorizationRequestedData redirectionAuthRequestedData = (RedirectTransactionGatewayAuthorizationRequestedData) authRequestedData
                .getTransactionGatewayAuthorizationRequestedData();
        assertEquals(expectedLogo, redirectionAuthRequestedData.getLogo().toString());
        assertEquals(
                redirectUrlResponseDto.getTimeout(),
                redirectionAuthRequestedData.getTransactionOutcomeTimeoutMillis()
        );
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldUseDefaultAssetLogoWithNoMappingConfigurationMatchFoundInBrandLogoMap() {

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);
        String expectedLogo = "http://expectedLogo";
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "UNMATCHED",
                new CardsAuthRequestDetailsDto().orderId(orderId),
                expectedLogo,
                Optional.of(Map.of("VISA", "http://visa")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN).url(NPG_URL_IFRAME);
        /* preconditions */
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(

                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId())
                .authorizationUrl(NPG_URL_IFRAME);
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();
        verify(transactionEventStoreRepository, times(1)).save(any());
        verify(transactionAuthorizationRequestedQueueAsyncClient, times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(Boolean.TRUE, savedEvent.getData().getIsContextualOnboard());
        assertEquals(
                authorizationData.sessionId().get(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        assertEquals(expectedLogo, npgTransactionGatewayAuthorizationRequestedData.getLogo().toString());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldUseDefaultAssetLogoWithNoBrandLogoConfiguration() {

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);
        String expectedLogo = "http://expectedLogo";
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                new CardsAuthRequestDetailsDto().orderId(orderId),
                expectedLogo,
                Optional.empty(),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN).url(NPG_URL_IFRAME);
        /* preconditions */
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(

                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId())
                .authorizationUrl(NPG_URL_IFRAME);
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();
        verify(transactionEventStoreRepository, times(1)).save(any());
        verify(transactionAuthorizationRequestedQueueAsyncClient, times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(Boolean.TRUE, savedEvent.getData().getIsContextualOnboard());
        assertEquals(
                authorizationData.sessionId().get(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        assertEquals(expectedLogo, npgTransactionGatewayAuthorizationRequestedData.getLogo().toString());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldUseBrandAssetLogoForCardPaymentWithConfiguredBrand() {

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);
        String expectedLogo = "http://visa";
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http//defaultLogo",
                Optional.of(Map.of("VISA", expectedLogo)),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN).url(NPG_URL_IFRAME);

        /* preconditions */
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId())
                .authorizationUrl(NPG_URL_IFRAME);
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();
        verify(transactionEventStoreRepository, times(1)).save(any());
        verify(transactionAuthorizationRequestedQueueAsyncClient, times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(Boolean.TRUE, savedEvent.getData().getIsContextualOnboard());
        assertEquals(
                authorizationData.sessionId().get(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        assertEquals(expectedLogo, npgTransactionGatewayAuthorizationRequestedData.getLogo().toString());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldUseDefaultLogoForNoBrandInformation() {

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);
        String expectedLogo = "http://defaultLogo";
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                null,
                new CardsAuthRequestDetailsDto().orderId(orderId),
                expectedLogo,
                Optional.of(Map.of("VISA", "http://visaLogo")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN).url(NPG_URL_IFRAME);

        /* preconditions */
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId())
                .authorizationUrl(NPG_URL_IFRAME);
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();
        verify(transactionEventStoreRepository, times(1)).save(any());
        verify(transactionAuthorizationRequestedQueueAsyncClient, times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(Boolean.TRUE, savedEvent.getData().getIsContextualOnboard());
        assertEquals(
                authorizationData.sessionId().get(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        assertEquals(expectedLogo, npgTransactionGatewayAuthorizationRequestedData.getLogo().toString());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }

    @Test
    void shouldNotPerformGatewayCallForErrorAcquiringLock() {
        String orderId = "oderId";

        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "BPAY",
                "brokerName",
                "pspChannelCode",
                "PAYPAL",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.empty(),
                Optional.empty(),
                "BANCOMATPAY",
                new ApmAuthRequestDetailsDto().detailType("apm"),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        TransactionTestUtils.transactionActivateEvent(
                                new NpgTransactionGatewayActivationData(orderId, correlationId)
                        )
                )
        );

        /* preconditions */

        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(

                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );

        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(false));

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectError(LockNotAcquiredException.class)
                .verify();

        verify(transactionEventStoreRepository, times(0)).save(any());
        verify(transactionTemplateWrapper, times(0)).save(any());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
        verifyNoInteractions(paymentGatewayClient);
    }

    @Test
    void shouldSetContextualOnboardFalseWhenDetailsTransactionIdDiffers() {
        TransactionId mismatchedTransactionId = new TransactionId("ac09a20709434eacac1d539e97595e41");
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String walletId = "walletId";
        String orderId = "orderId";
        String correlationId = UUID.randomUUID().toString();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                new ArrayList<>(),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.IO,
                null,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                TransactionTestUtils.npgTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId("paymentInstrumentId")
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                mismatchedTransactionId,
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "CP",
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                new WalletAuthRequestDetailsDto().walletId(walletId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetailsFor(transaction.getTransactionId()))
        );
        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.PAYMENT_COMPLETE)
                .fieldSet(
                        new FieldsDto()
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        FieldsDto npgBuildSessionResponse = new FieldsDto().sessionId("sessionId")
                .state(WorkflowStateDto.READY_FOR_PAYMENT).securityToken("securityToken");
        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);

        // preconditions
        when(paymentGatewayClient.requestNpgBuildSession(any(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(Mono.just(responseRequestNpgBuildSession));
        when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent(
                new NpgTransactionGatewayActivationData(orderId, correlationId)
        );
        transactionActivatedEvent.getData().setClientId(Transaction.ClientId.IO);
        when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                transactionActivatedEvent
                        )
                );
        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                null,
                authorizationData,
                List.of(
                        transactionActivatedEvent
                )
        );
        when(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .thenReturn(Mono.just(createTokenResponseDto));

        when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));

        when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);
        when(exclusiveLockDocumentWrapper.saveIfAbsent(any(), any())).thenReturn(Mono.just(true));

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId("orderId")
                .authorizationUrl(
                        new StringBuilder(NPG_WALLET_ESITO_PATH).append("clientId=").append(Transaction.ClientId.IO)
                                .append("&transactionId=").append(transactionId.value()).toString()
                                .concat("&sessionToken=").concat(MOCK_JWT)
                );
        Hooks.onOperatorDebug();
        // test
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(value -> requestAuthResponseDtoComparator(value, responseDto))
                .verifyComplete();

        verify(transactionEventStoreRepository, times(1)).save(any());
        verify(paymentGatewayClient, times(1)).requestNpgBuildSession(any(), any(), anyBoolean(), any(), any(), any());
        verify(transactionAuthorizationRequestedQueueAsyncClient, times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(Boolean.FALSE, savedEvent.getData().getIsContextualOnboard());
        assertEquals(
                authorizationData.sessionId().get(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        verify(exclusiveLockDocumentWrapper, times(1)).saveIfAbsent(
                argThat(lockDocument -> {
                    assertEquals(
                            "POST-auth-request-%s".formatted(TransactionTestUtils.TRANSACTION_ID),
                            lockDocument.id()
                    );
                    assertEquals("transactions-service", lockDocument.holderName());
                    return true;
                }),
                eq(Duration.ofSeconds(TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC))
        );
    }
}
