package it.pagopa.transactions.commands.handlers.v1;

import com.azure.cosmos.implementation.BadRequestException;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionEvent;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldsDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.StateResponseDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.WorkflowStateDto;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.ecommerce.gateway.v1.dto.VposAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.XPayAuthResponseEntityDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.repositories.TransactionTemplateWrapper;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.LogoMappingUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import org.apache.commons.codec.binary.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class TransactionRequestAuthorizationHandlerTest {

    private static final String NPG_CHECKOUT_ESITO_PATH = "/esito";
    private static final String NPG_URL_IFRAME = "http://iframe";
    private static final String NPG_GDI_CHECK_PATH = "/gdi-check#gdiIframeUrl=";
    private static final String NPG_WALLET_GDI_CHECK_PATH = "/ecommerce-fe/gdi-check#gdiIframeUrl=";
    private TransactionRequestAuthorizationHandler requestAuthorizationHandler;

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

    @Mock
    private LogoMappingUtils logoMappingUtils;

    private static final String CHECKOUT_BASE_PATH = "checkoutUri";
    private static final Set<CardAuthRequestDetailsDto.BrandEnum> testedCardBrands = new HashSet<>();

    private static boolean cardsTested = false;

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
                paymentMethodsClient,
                logoMappingUtils,
                transactionTemplateWrapper
        );
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
                                false
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                "paymentMethodName",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "XPAY",
                Optional.empty(),
                Optional.empty(),
                "VISA",
                new CardAuthRequestDetailsDto().brand(CardAuthRequestDetailsDto.BrandEnum.VISA)
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
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
                                false
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                new CardsAuthRequestDetailsDto().orderId("orderId")
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN).url(NPG_URL_IFRAME)
                .fieldSet(
                        new FieldsDto().sessionId("authorizationSessionId")
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        /* preconditions */

        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, null))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId())
                .authorizationUrl(NPG_URL_IFRAME);
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
    }

    @Test
    void shouldSaveAuthorizationEventNpgCardsPaymentComplete() {
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
                                false
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                new CardsAuthRequestDetailsDto().orderId("orderId")
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.PAYMENT_COMPLETE)
                .fieldSet(
                        new FieldsDto().sessionId("authorizationSessionId")
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        /* preconditions */

        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, null))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId())
                .authorizationUrl(NPG_CHECKOUT_ESITO_PATH);
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
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
                                false
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                new CardsAuthRequestDetailsDto().orderId("orderId")
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.GDI_VERIFICATION)
                .fieldSet(
                        new FieldsDto().sessionId("authorizationSessionId")
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, null))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.when(
                paymentMethodsClient.updateSession(
                        authorizationData.paymentInstrumentId(),
                        ((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId(),
                        transactionId.value()
                )
        ).thenReturn(Mono.empty());

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(((CardsAuthRequestDetailsDto) authorizationData.authDetails()).getOrderId())
                .authorizationUrl(
                        NPG_GDI_CHECK_PATH + Base64.encodeBase64URLSafeString(
                                NPG_URL_IFRAME
                                        .getBytes(StandardCharsets.UTF_8)
                        )
                );
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
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
                                false
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                new CardsAuthRequestDetailsDto().orderId("orderId")
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.CARD_DATA_COLLECTION);

        /* preconditions */

        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, null))
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof BadGatewayException)
                .verify();

        Mockito.verify(paymentMethodsClient, Mockito.times(0)).updateSession(any(), any(), any());
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
                                false
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
                email,
                faultCode,
                faultCodeString,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                null
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
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
                                false
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
                email,
                faultCode,
                faultCodeString,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                "GPAY",
                Optional.empty(),
                Optional.empty(),
                "VISA",
                new RedirectionAuthRequestDetailsDto()
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
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
                                false
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                "paymentMethodName",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "VPOS",
                Optional.empty(),
                Optional.empty(),
                "VISA",
                new CardAuthRequestDetailsDto()
                        .cvv("000")
                        .pan("123")
                        .threeDsData("threeDsData")
                        .expiryDate("209912")
                        .brand(CardAuthRequestDetailsDto.BrandEnum.fromValue(brand))
                        .holderName("holder name")
                        .detailType("CARD")
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
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
    void shouldSaveAuthorizationEventGdiVerificationForWallet() {
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
                                false
                        )
                ), // TODO
                   // TRANSFER
                   // LIST
                email,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                new WalletAuthRequestDetailsDto().detailType("wallet").walletId(walletId)
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
                new WalletAuthRequestDetailsDto().detailType("wallet").walletId(walletId)
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );
        FieldsDto npgBuildSessionResponse = new FieldsDto().sessionId(sessionId)
                .state(WorkflowStateDto.READY_FOR_PAYMENT).securityToken("securityToken");

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(WorkflowStateDto.GDI_VERIFICATION)
                .fieldSet(
                        new FieldsDto().sessionId(sessionId)
                                .addFieldsItem(new FieldDto().src(NPG_URL_IFRAME))
                );

        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);
        /* preconditions */
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgBuildSession(authorizationData, null, true))
                .thenReturn(Mono.just(responseRequestNpgBuildSession));
        Mockito.when(
                paymentGatewayClient.requestNpgCardsAuthorization(authorizationDataAfterBuildSession, null)
        )
                .thenReturn(Mono.just(stateResponseDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(args -> Mono.just(args.getArguments()[0]));

        RequestAuthorizationResponseDto responseDto = new RequestAuthorizationResponseDto()
                .authorizationRequestId(orderId)
                .authorizationUrl(
                        NPG_WALLET_GDI_CHECK_PATH + Base64.encodeBase64URLSafeString(
                                NPG_URL_IFRAME
                                        .getBytes(StandardCharsets.UTF_8)
                        ).concat("&clientId=IO&transactionId=").concat(authorizationData.transactionId().value())
                );
        /* test */
        StepVerifier.create(requestAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNext(responseDto)
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        Mockito.verify(transactionTemplateWrapper, Mockito.times(1)).save(any());
    }
}
