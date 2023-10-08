package it.pagopa.transactions.commands.handlers.v2;

import com.azure.cosmos.implementation.BadRequestException;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionEvent;
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.domain.v2.TransactionActivated;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldsDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.StateDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.StateResponseDto;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthResponseEntityDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.VposAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.VposAuthResponseDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.XPayAuthResponseEntityDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
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
    private LogoMappingUtils logoMappingUtils;

    @Captor
    private ArgumentCaptor<TransactionEvent<TransactionAuthorizationRequestData>> eventStoreCaptor;

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
                logoMappingUtils
        );
    }

    @Test
    void shouldSaveAuthorizationEvent() {
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
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new NpgTransactionGatewayActivationData("orderId", null, null)

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
                "PPAY",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                null,
                Optional.empty(),
                "VISA",
                new PostePayAuthRequestDetailsDto()
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );

        PostePayAuthResponseEntityDto postePayAuthResponseEntityDto = new PostePayAuthResponseEntityDto()
                .channel("channel")
                .requestId("requestId")
                .urlRedirect("http://example.com");

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(authorizationData))
                .thenReturn(Mono.just(postePayAuthResponseEntityDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(args -> Mono.just(args.getArguments()[0]));

        /* test */
        requestAuthorizationHandler.handle(requestAuthorizationCommand).block();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
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
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new NpgTransactionGatewayActivationData("orderId", null, null)
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
        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
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
                                false
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
                new NpgTransactionGatewayActivationData("orderId", null, null)
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
                "VISA",
                new CardAuthRequestDetailsDto().brand(CardAuthRequestDetailsDto.BrandEnum.VISA)
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );
        VposAuthResponseDto vposAuthResponseDto = new VposAuthResponseDto()
                .requestId("requestId")
                .status("status")
                .urlRedirect("http://example.com");

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
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
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new NpgTransactionGatewayActivationData("orderId", null, null)
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
                "VISA",
                new CardsAuthRequestDetailsDto().orderId("orderId")
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(StateDto.REDIRECTED_TO_EXTERNAL_DOMAIN).url(NPG_URL_IFRAME);

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData))
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
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new NpgTransactionGatewayActivationData("orderId", null, null)
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
                "VISA",
                new CardsAuthRequestDetailsDto().orderId("orderId")
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(StateDto.PAYMENT_COMPLETE);

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData))
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
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new NpgTransactionGatewayActivationData("orderId", null, null)
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
                "VISA",
                new CardsAuthRequestDetailsDto().orderId("orderId")
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(StateDto.GDI_VERIFICATION)
                .fieldSet(new FieldsDto().addFieldsItem(new FieldDto().src(NPG_URL_IFRAME)));

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData))
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
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new NpgTransactionGatewayActivationData("orderId", null, null)
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
                "VISA",
                new CardsAuthRequestDetailsDto().orderId("orderId")
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );

        StateResponseDto stateResponseDto = new StateResponseDto()
                .state(StateDto.CARD_DATA_COLLECTION);

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData))
                .thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData))
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
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new NpgTransactionGatewayActivationData("orderId", null, null)
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
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new NpgTransactionGatewayActivationData("orderId", null, null)
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
                "VISA",
                new PostePayAuthRequestDetailsDto().detailType("GPAY").accountEmail("test@test.it")
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value().toString()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        Mockito.when(paymentGatewayClient.requestXPayAuthorization(authorizationData)).thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(authorizationData)).thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestCreditCardAuthorization(authorizationData)).thenReturn(Mono.empty());
        Mockito.when(paymentGatewayClient.requestNpgCardsAuthorization(authorizationData)).thenReturn(Mono.empty());

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
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new NpgTransactionGatewayActivationData("orderId", null, null)
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
        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(authorizationData))
                .thenReturn(Mono.empty());
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
    void shouldUpdateSessionsIfPayingWithNpg() {
        TransactionId transactionId = new TransactionId(transactionIdUUID);
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        String orderId = "orderId";
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
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new NpgTransactionGatewayActivationData(orderId, null, null)
        );

        String paymentInstrumentId = "paymentInstrumentId";
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(200)
                .paymentInstrumentId(paymentInstrumentId)
                .pspId("PSP_CODE")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT);

        String sessionId = "sessionId";
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                authorizationRequest.getFee(),
                authorizationRequest.getPaymentInstrumentId(),
                authorizationRequest.getPspId(),
                "NPG",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                null,
                Optional.of(sessionId),
                "VISA",
                new CardsAuthRequestDetailsDto()
                        .detailType("cards")
                        .orderId(orderId)
        );

        TransactionRequestAuthorizationCommand requestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                authorizationData
        );

        PostePayAuthResponseEntityDto postePayAuthResponseEntityDto = new PostePayAuthResponseEntityDto()
                .channel("channel")
                .requestId("requestId")
                .urlRedirect("http://example.com");

        /* preconditions */
        Mockito.when(paymentGatewayClient.requestPostepayAuthorization(authorizationData))
                .thenReturn(Mono.just(postePayAuthResponseEntityDto));
        Mockito.when(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn((Flux) Flux.just(TransactionTestUtils.transactionActivateEvent()));
        Mockito.when(transactionEventStoreRepository.save(any())).thenAnswer(args -> Mono.just(args.getArguments()[0]));
        Mockito.when(paymentMethodsClient.updateSession(paymentInstrumentId, orderId, transactionId.value()))
                .thenReturn(Mono.empty());

        /* test */
        requestAuthorizationHandler.handle(requestAuthorizationCommand).block();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(any());
        Mockito.verify(paymentMethodsClient, Mockito.times(1))
                .updateSession(paymentInstrumentId, orderId, transactionId.value());
    }
}
