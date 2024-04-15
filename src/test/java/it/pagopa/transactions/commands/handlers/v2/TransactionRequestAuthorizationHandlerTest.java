package it.pagopa.transactions.commands.handlers.v2;

import com.azure.cosmos.implementation.BadRequestException;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionEvent;
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.RedirectTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.domain.v2.TransactionActivated;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldsDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.StateResponseDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.WorkflowStateDto;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.TracingInfo;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.queues.TracingUtilsTests;
import it.pagopa.ecommerce.commons.utils.JwtTokenUtils;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.generated.ecommerce.gateway.v1.dto.VposAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.VposAuthResponseDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.XPayAuthResponseEntityDto;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlResponseDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.client.WalletAsyncQueueClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.repositories.TransactionTemplateWrapper;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.OpenTelemetryUtils;
import it.pagopa.transactions.utils.PaymentSessionData;
import it.pagopa.transactions.utils.Queues;
import it.pagopa.transactions.utils.TransactionsUtils;
import org.apache.commons.codec.binary.Base64;
import org.junit.jupiter.api.AfterAll;
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
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static it.pagopa.transactions.commands.handlers.TransactionAuthorizationHandlerCommon.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

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

    private final UUID transactionIdUUID = UUID.randomUUID();

    TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);

    @Mock
    private EcommercePaymentMethodsClient paymentMethodsClient;

    @Mock
    private TransactionTemplateWrapper transactionTemplateWrapper;

    @Captor
    private ArgumentCaptor<TransactionEvent<TransactionAuthorizationRequestData>> eventStoreCaptor;
    @Captor
    private ArgumentCaptor<Duration> durationArgumentCaptor;

    private static final Set<CardAuthRequestDetailsDto.BrandEnum> testedCardBrands = new HashSet<>();

    private static boolean cardsTested = false;

    private final TracingUtils tracingUtils = TracingUtilsTests.getMock();

    private final int transientQueueEventsTtlSeconds = 30;
    private final int npgAuthRequestTimeout = 150;

    private final OpenTelemetryUtils openTelemetryUtils = Mockito.mock(OpenTelemetryUtils.class);

    private final QueueAsyncClient transactionAuthorizationRequestedQueueAsyncClient = Mockito
            .mock(QueueAsyncClient.class);

    private final WalletAsyncQueueClient walletAsyncQueueClient = Mockito.mock(WalletAsyncQueueClient.class);

    private static final JwtTokenUtils jwtTokenUtils = Mockito.mock(JwtTokenUtils.class);

    @AfterAll
    public static void afterAll() {
        if (cardsTested) {
            Set<CardAuthRequestDetailsDto.BrandEnum> untestedBrands = Arrays
                    .stream(CardAuthRequestDetailsDto.BrandEnum.values())
                    .filter(Predicate.not(testedCardBrands::contains)).collect(Collectors.toSet());
            assertTrue(
                    untestedBrands.isEmpty(),
                    "There are untested brand to logo cases: %s".formatted(untestedBrands)
            );
            Set<String> vposCardCircuit = Arrays.stream(VposAuthRequestDto.CircuitEnum.values())
                    .map(VposAuthRequestDto.CircuitEnum::toString).collect(Collectors.toSet());
            Set<String> uncoveredEcommerceBrands = Arrays.stream(CardAuthRequestDetailsDto.BrandEnum.values())
                    .map(CardAuthRequestDetailsDto.BrandEnum::toString).collect(Collectors.toSet());
            uncoveredEcommerceBrands.removeAll(vposCardCircuit);
            assertTrue(
                    uncoveredEcommerceBrands.isEmpty(),
                    "There are ecommerce card brands not mapped into PGS VPOS circuit!%nUnmapped brands: %s"
                            .formatted(uncoveredEcommerceBrands)
            );
        }
    }

    @BeforeEach
    private void init() {
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
                Optional.of(walletAsyncQueueClient),
                transientQueueEventsTtlSeconds,
                npgAuthRequestTimeout,
                tracingUtils,
                openTelemetryUtils,
                jwtTokenUtils,
                ECOMMERCE_JWT_SIGNING_KEY,
                TOKEN_VALIDITY_TIME_SECONDS
        );
        Mockito.reset(walletAsyncQueueClient);
    }

    @Test
    void shouldSaveAuthorizationEventXPAY() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.PgsCardSessionData(
                "VISA",
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                "XPAY",
                paymentSessionData,
                new CardAuthRequestDetailsDto().brand(CardAuthRequestDetailsDto.BrandEnum.VISA),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        XPayAuthResponseEntityDto xPayAuthResponseEntityDto = new XPayAuthResponseEntityDto()
                .requestId("requestId")
                .status("status")
                .urlRedirect("http://example.com");

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.just(xPayAuthResponseEntityDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(args -> Mono.just(args.getArguments()[0]));

        /* test */
        requestAuthorizationHandler.handle(requestAuthorizationCommand).block();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        Mockito.verify(transactionAuthorizationRequestedQueueAsyncClient, Mockito.times(0)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                any()
        );
    }

    @Test
    void shouldSaveAuthorizationEventVPOS() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.PgsCardSessionData(
                "VISA",
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                "VPOS",
                paymentSessionData,
                new CardAuthRequestDetailsDto().brand(CardAuthRequestDetailsDto.BrandEnum.VISA),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );
        VposAuthResponseDto vposAuthResponseDto = new VposAuthResponseDto()
                .requestId("requestId")
                .status("status")
                .urlRedirect("http://example.com");

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.just(vposAuthResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(args -> Mono.just(args.getArguments()[0]));

        /* test */
        requestAuthorizationHandler.handle(requestAuthorizationCommand).block();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        Mockito.verify(transactionAuthorizationRequestedQueueAsyncClient, Mockito.times(0)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                any()
        );
    }

    @Test
    void shouldSaveAuthorizationEventNpgCardsRedirectToExternalDomain() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData.CardSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN).url(NPG_URL_IFRAME);

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        Mockito.when(
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
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();
        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        Mockito.verify(transactionAuthorizationRequestedQueueAsyncClient, Mockito.times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                paymentSessionData.sessionId(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
    }

    @Test
    void shouldSaveAuthorizationEventNpgCardsPaymentCompleteCheckoutClient() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData.CardSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.PAYMENT_COMPLETE)
                .fieldSet(
                        new FieldsDto()
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        Mockito.when(
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
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        Mockito.verify(transactionAuthorizationRequestedQueueAsyncClient, Mockito.times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                paymentSessionData.sessionId(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
    }

    @Test
    void shouldSaveAuthorizationEventNpgCardsPaymentCompleteIOClient() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData.CardSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.PAYMENT_COMPLETE)
                .fieldSet(
                        new FieldsDto()
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent(
                new NpgTransactionGatewayActivationData(orderId, correlationId)
        );
        transactionActivatedEvent.getData().setClientId(Transaction.ClientId.IO);
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                transactionActivatedEvent
                        )
                );

        Mockito.when(jwtTokenUtils.generateToken(any(), anyInt(), any())).thenReturn(Either.right(MOCK_JWT));

        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        Mockito.when(
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
                        new StringBuilder(NPG_WALLET_ESITO_PATH).append("clientId=").append(Transaction.ClientId.IO)
                                .append("&transactionId=").append(transactionId.value()).toString()
                                .concat("&sessionToken=").concat(MOCK_JWT)
                );
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        Mockito.verify(transactionAuthorizationRequestedQueueAsyncClient, Mockito.times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                paymentSessionData.sessionId(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
    }

    @Test
    void shouldSaveAuthorizationEventGdiVerification() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData.CardSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.GDI_VERIFICATION)
                .fieldSet(
                        new FieldsDto()
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        Mockito.when(
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
        Hooks.onOperatorDebug();
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        Mockito.verify(transactionAuthorizationRequestedQueueAsyncClient, Mockito.times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                paymentSessionData.sessionId(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
    }

    @Test
    void shouldSaveAuthorizationEventBadGatewayException() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.CARD_DATA_COLLECTION);

        /* preconditions */
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof BadGatewayException)
                .verify();

        Mockito.verify(paymentMethodsClient, Mockito.times(1)).updateSession(any(), any(), any());
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                null,
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value().toString()))
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

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                "GPAY",
                paymentSessionData,
                new CardAuthRequestDetailsDto(),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData)).thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData)).thenReturn(Mono.empty());

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof BadRequestException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                    "VISA",
                    "MASTERCARD",
                    "UNKNOWN",
                    "DINERS",
                    "MAESTRO",
                    "AMEX"
            }
    )
    void shouldMapLogoSuccessfully(String brand) {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.PgsCardSessionData(
                "VISA",
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                "VPOS",
                paymentSessionData,
                new CardAuthRequestDetailsDto()
                        .cvv("000")
                        .pan("123")
                        .threeDsData("threeDsData")
                        .expiryDate("209912")
                        .brand(CardAuthRequestDetailsDto.BrandEnum.fromValue(brand))
                        .holderName("holder name")
                        .detailType("CARD"),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        XPayAuthResponseEntityDto xPayAuthResponseEntityDto = new XPayAuthResponseEntityDto()
                .requestId("requestId")
                .status("status")
                .urlRedirect("http://example.com");

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.just(xPayAuthResponseEntityDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));

        /* test */
        requestAuthorizationHandler.handle(requestAuthorizationCommand).block();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        TransactionEvent<TransactionAuthorizationRequestData> capturedEvent = eventStoreCaptor.getValue();

        cardsTested = true;
        testedCardBrands.add(CardAuthRequestDetailsDto.BrandEnum.fromValue(brand));
    }

    @Test
    void shouldReturnErrorForMissingStateInNPGConfirmPaymentResponse() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto();

        /* preconditions */
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(
                        exception -> exception instanceof BadGatewayException bge &&
                                bge.getHttpStatus() == HttpStatus.BAD_GATEWAY &&
                                bge.getDetail().equals("Invalid NPG confirm payment, state response null!")
                )
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldReturnErrorForNPGGdiVerificationResponseStateWithNoFieldSetReceived() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.GDI_VERIFICATION);

        /* preconditions */
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );

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

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldReturnErrorForNPGGdiVerificationResponseStateWithNoFieldsReceivedIntoFieldSet() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                new CardsAuthRequestDetailsDto().orderId("orderId"),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.GDI_VERIFICATION)
                .fieldSet(new FieldsDto());

        /* preconditions */
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );

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

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldReturnErrorForNPGGdiVerificationResponseStateWithNullSrcReceivedInField0() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.GDI_VERIFICATION)
                .fieldSet(new FieldsDto().addFieldsItem(new FieldDto()));

        /* preconditions */
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );

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

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldReturnErrorForNPGRedirectToExternalDomainStateResponseWithoutPopulatedUrl() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN);

        /* preconditions */
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );

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

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldSaveAuthorizationEventNpgCardsRedirectToExternalDomainSavingReceivedSessionIdInEvent() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData.CardSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                new CardsAuthRequestDetailsDto().orderId("orderId"),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN).url(NPG_URL_IFRAME)
                .fieldSet(new FieldsDto().sessionId(TransactionTestUtils.NPG_CONFIRM_PAYMENT_SESSION_ID));

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        Mockito.when(
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
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                paymentSessionData.sessionId(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertEquals(
                TransactionTestUtils.NPG_CONFIRM_PAYMENT_SESSION_ID,
                npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId()
        );
    }

    @Test
    void shouldSaveAuthorizationEventNpgCardsPaymentCompleteSavingReceivedSessionIdInEvent() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData.CardSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.PAYMENT_COMPLETE)
                .fieldSet(
                        new FieldsDto()
                                .sessionId(TransactionTestUtils.NPG_CONFIRM_PAYMENT_SESSION_ID)
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        Mockito.when(
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

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                paymentSessionData.sessionId(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertEquals(
                TransactionTestUtils.NPG_CONFIRM_PAYMENT_SESSION_ID,
                npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId()
        );
    }

    @Test
    void shouldSaveAuthorizationEventGdiVerificationSavingReceivedSessionIdInEvent() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData.CardSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.GDI_VERIFICATION)
                .fieldSet(
                        new FieldsDto()
                                .sessionId(TransactionTestUtils.NPG_CONFIRM_PAYMENT_SESSION_ID)
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        Mockito.when(
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
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                paymentSessionData.sessionId(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertEquals(
                TransactionTestUtils.NPG_CONFIRM_PAYMENT_SESSION_ID,
                npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId()
        );
    }

    @Test
    void shouldSaveAuthorizationEventGdiVerificationForWallet() {
        String walletId = UUID.randomUUID().toString();
        String contractId = "contractId";
        String sessionId = "sessionId";
        String orderId = "orderId";
        TransactionId transactionId = new TransactionId(transactionIdUUID);
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        Transaction.ClientId clientId = Transaction.ClientId.IO;
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.WalletCardSessionData(
                "VISA",
                Optional.empty(),
                new BIN("0000"),
                new CardLastFourDigits("1234"),
                contractId
        );

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
                paymentSessionData,
                new WalletAuthRequestDetailsDto().detailType("wallet").walletId(walletId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        PaymentSessionData paymentSessionDataAfterBuildSession = new PaymentSessionData.WalletCardSessionData(
                "VISA",
                Optional.of(sessionId),
                new BIN("0000"),
                new CardLastFourDigits("1234"),
                contractId
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
                paymentSessionDataAfterBuildSession,
                new WalletAuthRequestDetailsDto().detailType("wallet").walletId(walletId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
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
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(
                paymentGatewayClient
                        .requestNpgBuildSession(
                                authorizationData,
                                correlationId,
                                true,
                                clientId.name(),
                                UUID.fromString(TransactionTestUtils.USER_ID)
                        )
        )
                .thenReturn(Mono.just(responseRequestNpgBuildSession));
        Mockito.when(
                paymentGatewayClient.requestNpgCardsAuthorization(any(), any())
        )
                .thenReturn(Mono.just(stateResponseDto));
        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent(
                new NpgTransactionGatewayActivationData(orderId, correlationId)
        );
        transactionActivatedEvent.getData().setClientId(clientId);
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                transactionActivatedEvent
                        )
                );

        Mockito.when(jwtTokenUtils.generateToken(any(), anyInt(), any())).thenReturn(Either.right(MOCK_JWT));

        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));

        Mockito.when(
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
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                "sessionId",
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        Mockito.verify(transactionTemplateWrapper, Mockito.times(1)).save(any());
        Mockito.verify(paymentGatewayClient, Mockito.times(1))
                .requestNpgCardsAuthorization(authorizationDataAfterBuildSession, correlationId);
    }

    @Test
    void shouldThrowExceptionNoGatewayMatchesDuringAuthorizationForWallet() {
        String contractId = "contractId";
        String sessionId = "sessionId";
        String orderId = "oderId";
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.WalletCardSessionData(
                "VISA",
                Optional.empty(),
                new BIN("0000"),
                new CardLastFourDigits("1234"),
                contractId
        );

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
                paymentSessionData,
                new CardAuthRequestDetailsDto(),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        FieldsDto npgBuildSessionResponse = new FieldsDto().sessionId(sessionId)
                .state(WorkflowStateDto.READY_FOR_PAYMENT);

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof BadRequestException)
                .verify();

        Mockito.verify(paymentGatewayClient, Mockito.times(0))
                .requestNpgBuildSession(
                        any(),
                        any(),
                        eq(true),
                        eq(Transaction.ClientId.IO.name()),
                        eq(UUID.fromString(TransactionTestUtils.USER_ID))
                );
        Mockito.verify(paymentGatewayClient, Mockito.times(0)).requestNpgCardsAuthorization(any(), any());
        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
        Mockito.verify(transactionTemplateWrapper, Mockito.times(0)).save(any());

    }

    @Test
    void shouldPerformTransactionAuthorizationForWalletPayPalPaymentMethodUsingOrderBuildNpgCall() {
        String walletId = UUID.randomUUID().toString();
        String contractId = "contractId";
        String sessionId = "sessionId";
        String orderId = "oderId";
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.WalletPayPalSessionData(
                contractId,
                "maskedEmail"
        );

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
                paymentSessionData,
                new WalletAuthRequestDetailsDto().detailType("wallet").walletId(walletId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        FieldsDto npgBuildSessionResponse = new FieldsDto().sessionId(sessionId)
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN)
                .securityToken("securityToken")
                .sessionId("sessionId")
                .url("http://localhost/redirectionUrl");

        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);
        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(
                paymentGatewayClient
                        .requestNpgBuildApmPayment(
                                authorizationData,
                                correlationId,
                                true,
                                clientId.name(),
                                UUID.fromString(TransactionTestUtils.USER_ID)
                        )
        )
                .thenReturn(Mono.just(responseRequestNpgBuildSession));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));

        Mockito.when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(orderId)
                .authorizationUrl(npgBuildSessionResponse.getUrl());
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                "sessionId",
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        Mockito.verify(transactionTemplateWrapper, Mockito.times(1)).save(any());

    }

    @Test
    void shouldPerformTransactionAuthorizationForApmPaymentMethodUsingOrderBuildNpgCall() {
        String sessionId = "sessionId";
        String orderId = "oderId";
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.ApmSessionData(
                "BANCOMATPAY"
        );

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
                paymentSessionData,
                new ApmAuthRequestDetailsDto().detailType("apm"),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        FieldsDto npgBuildSessionResponse = new FieldsDto().sessionId(sessionId)
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN)
                .securityToken("securityToken")
                .sessionId("sessionId")
                .url("http://localhost/redirectionUrl");

        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);
        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(
                paymentGatewayClient.requestNpgBuildApmPayment(
                        authorizationData,
                        correlationId,
                        false,
                        Transaction.ClientId.CHECKOUT.name(),
                        UUID.fromString(TransactionTestUtils.USER_ID)
                )
        )
                .thenReturn(Mono.just(responseRequestNpgBuildSession));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));

        Mockito.when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(orderId)
                .authorizationUrl(npgBuildSessionResponse.getUrl());
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                "sessionId",
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        Mockito.verify(transactionTemplateWrapper, Mockito.times(1)).save(any());

    }

    @ParameterizedTest
    @ValueSource(strings = "")
    @NullSource
    void shouldReturnErrorPerformingTransactionAuthorizationForWalletPayPalForInvalidNpgReturnUrl(
                                                                                                  String wrongReturnUrl
    ) {
        String walletId = UUID.randomUUID().toString();
        String contractId = "contractId";
        String sessionId = "sessionId";
        String orderId = "oderId";
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.WalletPayPalSessionData(
                contractId,
                "maskedEmail"
        );

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
                paymentSessionData,
                new WalletAuthRequestDetailsDto().detailType("wallet").walletId(walletId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        FieldsDto npgBuildSessionResponse = new FieldsDto().sessionId(sessionId)
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN)
                .securityToken("securityToken")
                .sessionId("sessionId")
                .url(wrongReturnUrl);

        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);
        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(
                paymentGatewayClient
                        .requestNpgBuildApmPayment(
                                authorizationData,
                                correlationId,
                                true,
                                clientId.name(),
                                UUID.fromString(TransactionTestUtils.USER_ID)
                        )
        )
                .thenReturn(Mono.just(responseRequestNpgBuildSession));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );

        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectError(BadGatewayException.class)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
        Mockito.verify(transactionTemplateWrapper, Mockito.times(0)).save(any());

    }

    @Test
    void shouldHandleAuthorizationRequestForRedirectionPaymentGateway() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.RedirectSessionData();

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
                paymentSessionData,
                new RedirectionAuthRequestDetailsDto(),
                expectedLogo,
                Optional.empty()
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        RedirectUrlResponseDto redirectUrlResponseDto = new RedirectUrlResponseDto()
                .url("http://redirectUrl")
                .idTransaction(transactionId.value())
                .idPSPTransaction(TransactionTestUtils.AUTHORIZATION_REQUEST_ID)
                .amount(authorizationRequest.getAmount() + authorizationRequest.getFee())
                .timeout(TransactionTestUtils.REDIRECT_AUTHORIZATION_TIMEOUT);

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestRedirectUrlAuthorization(eq(authorizationData), any()))
                .thenReturn(Mono.just(redirectUrlResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));

        /* test */
        requestAuthorizationHandler.handle(requestAuthorizationCommand).block();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        Mockito.verify(transactionAuthorizationRequestedQueueAsyncClient, Mockito.times(0)).sendMessageWithResponse(
                any(),
                any(),
                any()
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
    }

    @Test
    void shouldUseDefaultAssetLogoWithNoMappingConfigurationMatchFoundInBrandLogoMap() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData.CardSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "UNMATCHED",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                new CardsAuthRequestDetailsDto().orderId(orderId),
                expectedLogo,
                Optional.of(Map.of("VISA", "http://visa"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN).url(NPG_URL_IFRAME);

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        Mockito.when(
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
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();
        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        Mockito.verify(transactionAuthorizationRequestedQueueAsyncClient, Mockito.times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                paymentSessionData.sessionId(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        assertEquals(expectedLogo, npgTransactionGatewayAuthorizationRequestedData.getLogo().toString());
    }

    @Test
    void shouldUseDefaultAssetLogoWithNoBrandLogoConfiguration() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData.CardSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                new CardsAuthRequestDetailsDto().orderId(orderId),
                expectedLogo,
                Optional.empty()
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN).url(NPG_URL_IFRAME);

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        Mockito.when(
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
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();
        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        Mockito.verify(transactionAuthorizationRequestedQueueAsyncClient, Mockito.times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                paymentSessionData.sessionId(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        assertEquals(expectedLogo, npgTransactionGatewayAuthorizationRequestedData.getLogo().toString());
    }

    @Test
    void shouldUseBrandAssetLogoForCardPaymentWithConfiguredBrand() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData.CardSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                new CardsAuthRequestDetailsDto().orderId(orderId),
                "http//defaultLogo",
                Optional.of(Map.of("VISA", expectedLogo))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN).url(NPG_URL_IFRAME);

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        Mockito.when(
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
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();
        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        Mockito.verify(transactionAuthorizationRequestedQueueAsyncClient, Mockito.times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                paymentSessionData.sessionId(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        assertEquals(expectedLogo, npgTransactionGatewayAuthorizationRequestedData.getLogo().toString());
    }

    @Test
    void shouldUseDefaultLogoForNoBrandInformation() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData.CardSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                paymentSessionData,
                new CardsAuthRequestDetailsDto().orderId(orderId),
                expectedLogo,
                Optional.empty()
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN).url(NPG_URL_IFRAME);

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                TransactionTestUtils.transactionActivateEvent(
                                        new NpgTransactionGatewayActivationData(orderId, correlationId)
                                )
                        )
                );
        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        Mockito.when(
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
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();
        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        Mockito.verify(transactionAuthorizationRequestedQueueAsyncClient, Mockito.times(1)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                durationArgumentCaptor.capture()
        );
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                paymentSessionData.sessionId(),
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        assertEquals(expectedLogo, npgTransactionGatewayAuthorizationRequestedData.getLogo().toString());
    }

    @Test
    void shouldFireWalletUsedEventWhenRequestAuthorizationThroughWallet() {
        String walletId = UUID.randomUUID().toString();
        String contractId = "contractId";
        String sessionId = "sessionId";
        String orderId = "oderId";
        TransactionId transactionId = new TransactionId(transactionIdUUID);
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String correlationId = UUID.randomUUID().toString();
        Transaction.ClientId clientId = it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.IO;
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.WalletCardSessionData(
                "VISA",
                Optional.empty(),
                new BIN("0000"),
                new CardLastFourDigits("1234"),
                contractId
        );

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
                paymentSessionData,
                new WalletAuthRequestDetailsDto().detailType("wallet").walletId(walletId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        PaymentSessionData paymentSessionDataAfterBuildSession = new PaymentSessionData.WalletCardSessionData(
                "VISA",
                Optional.of(sessionId),
                new BIN("0000"),
                new CardLastFourDigits("1234"),
                contractId
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
                paymentSessionDataAfterBuildSession,
                new WalletAuthRequestDetailsDto().detailType("wallet").walletId(walletId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
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
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(
                paymentGatewayClient.requestNpgBuildSession(
                        authorizationData,
                        correlationId,
                        true,
                        clientId.name(),
                        UUID.fromString(TransactionTestUtils.USER_ID)
                )
        )
                .thenReturn(Mono.just(responseRequestNpgBuildSession));
        Mockito.when(
                paymentGatewayClient.requestNpgCardsAuthorization(authorizationDataAfterBuildSession, correlationId)
        )
                .thenReturn(Mono.just(stateResponseDto));

        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent(
                new NpgTransactionGatewayActivationData(orderId, correlationId)
        );
        transactionActivatedEvent.getData().setClientId(clientId);

        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                transactionActivatedEvent
                        )
                );

        Mockito.when(jwtTokenUtils.generateToken(any(), anyInt(), any())).thenReturn(Either.right(MOCK_JWT));

        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));

        Mockito.when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);

        Mockito.when(walletAsyncQueueClient.fireWalletLastUsageEvent(any(), any(), any()))
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(orderId)
                .authorizationUrl(
                        NPG_WALLET_GDI_CHECK_PATH + Base64.encodeBase64URLSafeString(
                                NPG_URL_IFRAME
                                        .getBytes(StandardCharsets.UTF_8)
                        )
                                .concat("&clientId=IO&transactionId=")
                                .concat(authorizationData.transactionId().value())
                                .concat("&sessionToken=").concat(MOCK_JWT)
                );
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                "sessionId",
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        Mockito.verify(transactionTemplateWrapper, Mockito.times(1)).save(any());

        final var tracingArgument = ArgumentCaptor.forClass(TracingInfo.class);
        Mockito.verify(walletAsyncQueueClient, Mockito.times(1)).fireWalletLastUsageEvent(
                eq(walletId),
                eq(Transaction.ClientId.IO),
                tracingArgument.capture()
        );
        assertNotNull(tracingArgument.getValue());
    }

    @Test
    void shouldNotFireWalletUsedEventWhenRequestAuthorizationWithoutWallet() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.CardSessionData(
                "VISA",
                UUID.randomUUID().toString(),
                new BIN("0000"),
                new CardLastFourDigits("1234")
        );

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
                "VPOS",
                paymentSessionData,
                new CardAuthRequestDetailsDto().brand(CardAuthRequestDetailsDto.BrandEnum.VISA),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
        );
        VposAuthResponseDto vposAuthResponseDto = new VposAuthResponseDto()
                .requestId("requestId")
                .status("status")
                .urlRedirect("http://example.com");

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.just(vposAuthResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(args -> Mono.just(args.getArguments()[0]));

        /* test */
        requestAuthorizationHandler.handle(requestAuthorizationCommand).block();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        Mockito.verify(transactionAuthorizationRequestedQueueAsyncClient, Mockito.times(0)).sendMessageWithResponse(
                any(QueueEvent.class),
                any(),
                any()
        );
        Mockito.verify(walletAsyncQueueClient, Mockito.times(0)).fireWalletLastUsageEvent(any(), any(), any());
    }

    @Test
    void shouldNotAffectRequestAuthorizationWhenFiringWalletUsedFails() {
        String walletId = UUID.randomUUID().toString();
        String contractId = "contractId";
        String sessionId = "sessionId";
        String orderId = "oderId";
        TransactionId transactionId = new TransactionId(transactionIdUUID);
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";
        String correlationId = UUID.randomUUID().toString();
        Transaction.ClientId clientId = Transaction.ClientId.IO;
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
                                new CompanyName(null)
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
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

        PaymentSessionData paymentSessionData = new PaymentSessionData.WalletCardSessionData(
                "VISA",
                Optional.empty(),
                new BIN("0000"),
                new CardLastFourDigits("1234"),
                contractId
        );

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
                paymentSessionData,
                new WalletAuthRequestDetailsDto().detailType("wallet").walletId(walletId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        PaymentSessionData paymentSessionDataAfterBuildSession = new PaymentSessionData.WalletCardSessionData(
                "VISA",
                Optional.of(sessionId),
                new BIN("0000"),
                new CardLastFourDigits("1234"),
                contractId
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
                paymentSessionDataAfterBuildSession,
                new WalletAuthRequestDetailsDto().detailType("wallet").walletId(walletId),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().stream().map(PaymentNotice::rptId).toList(),
                authorizationData
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
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(
                paymentGatewayClient.requestNpgBuildSession(
                        authorizationData,
                        correlationId,
                        true,
                        clientId.name(),
                        UUID.fromString(TransactionTestUtils.USER_ID)
                )
        )
                .thenReturn(Mono.just(responseRequestNpgBuildSession));
        Mockito.when(
                paymentGatewayClient.requestNpgCardsAuthorization(authorizationDataAfterBuildSession, correlationId)
        )
                .thenReturn(Mono.just(stateResponseDto));
        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent(
                new NpgTransactionGatewayActivationData(orderId, correlationId)
        );
        transactionActivatedEvent.getData().setClientId(clientId);
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(
                        (Flux) Flux.just(
                                transactionActivatedEvent
                        )
                );

        Mockito.when(jwtTokenUtils.generateToken(any(), anyInt(), any())).thenReturn(Either.right(MOCK_JWT));

        Mockito.when(transactionEventStoreRepository.save(eventStoreCaptor.capture()))
                .thenAnswer(args -> Mono.just(args.getArguments()[0]));

        Mockito.when(
                transactionAuthorizationRequestedQueueAsyncClient.sendMessageWithResponse(
                        any(QueueEvent.class),
                        any(),
                        durationArgumentCaptor.capture()
                )
        )
                .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);

        Mockito.when(walletAsyncQueueClient.fireWalletLastUsageEvent(any(), any(), any()))
                .thenReturn(Mono.error(new Exception("Something went wrong")));

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(orderId)
                .authorizationUrl(
                        NPG_WALLET_GDI_CHECK_PATH + Base64.encodeBase64URLSafeString(
                                NPG_URL_IFRAME
                                        .getBytes(StandardCharsets.UTF_8)
                        ).concat("&clientId=IO&transactionId=").concat(authorizationData.transactionId().value())
                                .concat("&sessionToken=").concat(MOCK_JWT)
                );
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        TransactionEvent<TransactionAuthorizationRequestData> savedEvent = eventStoreCaptor.getValue();
        NpgTransactionGatewayAuthorizationRequestedData npgTransactionGatewayAuthorizationRequestedData = (NpgTransactionGatewayAuthorizationRequestedData) savedEvent
                .getData().getTransactionGatewayAuthorizationRequestedData();
        assertEquals(
                "sessionId",
                npgTransactionGatewayAuthorizationRequestedData.getSessionId()
        );
        assertNull(npgTransactionGatewayAuthorizationRequestedData.getConfirmPaymentSessionId());
        Mockito.verify(transactionTemplateWrapper, Mockito.times(1)).save(any());

        final var tracingArgument = ArgumentCaptor.forClass(TracingInfo.class);
        Mockito.verify(walletAsyncQueueClient, Mockito.times(1)).fireWalletLastUsageEvent(
                eq(walletId),
                eq(Transaction.ClientId.IO),
                tracingArgument.capture()
        );
        assertNotNull(tracingArgument.getValue());
    }
}
