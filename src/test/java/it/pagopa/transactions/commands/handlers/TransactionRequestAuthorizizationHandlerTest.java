package it.pagopa.transactions.commands.handlers;

import com.azure.cosmos.implementation.BadRequestException;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionEvent;
import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthResponseEntityDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.VposAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.XPayAuthResponseEntityDto;
import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.PostePayAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationRequestDto;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
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

import java.net.URI;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class TransactionRequestAuthorizizationHandlerTest {

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

    @Captor
    private ArgumentCaptor<TransactionEvent<TransactionAuthorizationRequestData>> eventStoreCaptor;

    private static final Map<CardAuthRequestDetailsDto.BrandEnum, URI> brandLogoMapping = Arrays.stream(
            CardAuthRequestDetailsDto.BrandEnum.values()
    )
            .collect(
                    Collectors.toUnmodifiableMap(
                            Function.identity(),
                            brand -> URI.create("http://%s.cdn.uri".formatted(brand))
                    )
            );

    private static final Set<CardAuthRequestDetailsDto.BrandEnum> testedCardBrands = new HashSet<>();

    @AfterAll
    public static void afterAll() {
        Set<CardAuthRequestDetailsDto.BrandEnum> untestedBrands = Arrays
                .stream(CardAuthRequestDetailsDto.BrandEnum.values())
                .filter(Predicate.not(testedCardBrands::contains)).collect(Collectors.toSet());
        assertTrue(untestedBrands.isEmpty(), "There are untested brand to logo cases: %s".formatted(untestedBrands));
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

    @BeforeEach
    private void init() {
        requestAuthorizationHandler = new TransactionRequestAuthorizationHandler(
                paymentGatewayClient,
                transactionEventStoreRepository,
                transactionsUtils,
                brandLogoMapping
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
                transaction,
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
                transaction,
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
                transaction,
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
                transaction,
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
                transaction,
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
        assertEquals(
                brandLogoMapping.get(CardAuthRequestDetailsDto.BrandEnum.fromValue(brand)),
                capturedEvent.getData().getLogo()
        );
        testedCardBrands.add(CardAuthRequestDetailsDto.BrandEnum.fromValue(brand));
    }

}
