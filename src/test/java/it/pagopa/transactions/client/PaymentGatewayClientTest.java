package it.pagopa.transactions.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.client.NodeForwarderClient;
import it.pagopa.ecommerce.commons.client.NpgClient;
import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.activation.EmptyTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.domain.v2.*;
import it.pagopa.ecommerce.commons.exceptions.NodeForwarderClientException;
import it.pagopa.ecommerce.commons.exceptions.NpgApiKeyMissingPspRequestedException;
import it.pagopa.ecommerce.commons.exceptions.NpgResponseException;
import it.pagopa.ecommerce.commons.exceptions.RedirectConfigurationException;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenRequestDto;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenResponseDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldsDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.StateResponseDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.WorkflowStateDto;
import it.pagopa.ecommerce.commons.utils.NpgApiKeyConfiguration;
import it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig;
import it.pagopa.ecommerce.commons.utils.ReactiveUniqueIdUtils;
import it.pagopa.ecommerce.commons.utils.RedirectKeysConfiguration;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlRequestDto;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlResponseDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.configurations.NpgSessionUrlConfig;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.NpgNotRetryableErrorException;
import it.pagopa.transactions.utils.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static it.pagopa.ecommerce.commons.v2.TransactionTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
class PaymentGatewayClientTest {

    public static final String MOCK_JWT = "eyJhbGciOiJIUzI1NiJ9.eyJJc3N1ZXIiOiJJc3N1ZXIiLCJwYXltZW50TWV0aG9kSWQiOiJwYXltZW50SW5zdHJ1bWVudElkIiwiZXhwIjo0OTA0MjgzMDU0LCJpYXQiOjE3NDg2MDk0NTQsInRyYW5zYWN0aW9uSWQiOiI4OWU5NWRhYmRiYjM0MTQzOTJlNmUwNmY2NDgzMmViYSJ9.BT_DJ5PD3P_2-T9EEV24mTVX4RQLobuHPxaE9X7trwY";
    public static final String MOCK_JWT_WITH_ORDERID = "eyJhbGciOiJIUzI1NiJ9.eyJJc3N1ZXIiOiJJc3N1ZXIiLCJvcmRlcklkIjoib3JkZXJJZEdlbmVyYXRlZCIsInBheW1lbnRNZXRob2RJZCI6InBheW1lbnRJbnN0cnVtZW50SWQiLCJleHAiOjQ5MDQyODMwNTQsImlhdCI6MTc0ODYwOTQ1NCwidHJhbnNhY3Rpb25JZCI6Ijg5ZTk1ZGFiZGJiMzQxNDM5MmU2ZTA2ZjY0ODMyZWJhIn0.gwP3cZ7w2lrgOv8FACpGclDljWp-PomIc0DAfa3wjeg";

    private PaymentGatewayClient client;

    @Mock
    UUIDUtils mockUuidUtils;

    @Mock
    ConfidentialMailUtils confidentialMailUtils;

    @Mock
    ReactiveUniqueIdUtils uniqueIdUtils;

    private final String npgDefaultApiKey = UUID.randomUUID().toString();
    private final PaymentSessionData.ContextualOnboardDetails contextualOnboardDetails = new PaymentSessionData.ContextualOnboardDetails(
            UUID.randomUUID().toString(),
            100L
    );

    private final NpgSessionUrlConfig sessionUrlConfig = new NpgSessionUrlConfig(
            "http://localhost:1234",
            "/ecommerce-fe/esito#clientId={clientId}&transactionId={transactionId}&sessionToken={sessionToken}",
            "https://localhost/ecommerce/{orderId}/outcomes?sessionToken={sessionToken}"
    );

    NpgPspApiKeysConfig npgPspApiKeysConfig = NpgPspApiKeysConfig.parseApiKeyConfiguration(
            """
                    {
                        "pspId1": "pspKey1"
                    }
                    """,
            Set.of("pspId1"),
            NpgClient.PaymentMethod.CARDS,
            new ObjectMapper()
    ).get();

    @Mock
    NpgClient npgClient;

    @Mock
    JwtTokenIssuerClient jwtTokenIssuerClient;

    private final TransactionId transactionId = new TransactionId(UUID.randomUUID());

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    private static final int TOKEN_VALIDITY_TIME_SECONDS = 900;

    private final NodeForwarderClient<RedirectUrlRequestDto, RedirectUrlResponseDto> nodeForwarderClient = Mockito
            .mock(NodeForwarderClient.class);

    private static final Set<String> redirectPaymentTypeCodes = Set
            .of("RBPR", "RBPB", "RBPP", "RPIC", "RBPS", "RICO", "KLRN");

    private static final Map<String, String> redirectPaymentTypeCodeDescription = redirectPaymentTypeCodes.stream()
            .collect(
                    Collectors.toMap(Function.identity(), "Redirect payment type code description %s"::formatted)
            );

    private final Set<String> pspTypeCodesPspIdSet = redirectPaymentTypeCodes.stream()
            .map("pspId-%s"::formatted).collect(Collectors.toSet());

    private final Map<String, String> redirectBeApiCallUriMap = redirectPaymentTypeCodes.stream()
            .collect(
                    Collectors.toMap("pspId-%s"::formatted, "http://redirect/%s"::formatted)
            );

    private final RedirectKeysConfiguration configurationKeysConfig = new RedirectKeysConfiguration(
            redirectBeApiCallUriMap,
            pspTypeCodesPspIdSet
    );

    private final Set<String> npgAuthorizationRetryExcludedErrorCodes = Set.of("GW0035", "GW0004");

    private final NpgApiKeyConfiguration npgApiKeyHandler = Mockito.mock(NpgApiKeyConfiguration.class);

    @BeforeEach
    public void init() {
        client = new PaymentGatewayClient(
                objectMapper,
                mockUuidUtils,
                confidentialMailUtils,
                npgClient,
                sessionUrlConfig,
                uniqueIdUtils,
                TOKEN_VALIDITY_TIME_SECONDS,
                TOKEN_VALIDITY_TIME_SECONDS,
                nodeForwarderClient,
                configurationKeysConfig,
                npgApiKeyHandler,
                npgAuthorizationRetryExcludedErrorCodes,
                redirectPaymentTypeCodeDescription,
                jwtTokenIssuerClient
        );

        Hooks.onOperatorDebug();
    }

    @Test
    void shouldNotCallAuthorizationGatewayWithInvalidDetailTypeGatewayIdTuple() {
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null),
                                List.of(new PaymentTransferInfo("77777777777", false, 100, null)),
                                false,
                                new CompanyName("companyName"),
                                null
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                null,
                null
        );

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId",
                "XX",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "GID",
                Optional.empty(),
                Optional.empty(),
                "VISA",
                Mockito.mock(RequestAuthorizationRequestDetailsDto.class),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetails)
        );

        /* test */

        StepVerifier.create(client.requestNpgCardsAuthorization(authorizationData, UUID.randomUUID().toString()))
                .expectNextCount(0)
                .verifyError(InvalidRequestException.class);

        verifyNoInteractions(npgClient);
    }

    @Test
    void shouldReturnAuthorizationResponseForCardsWithNpg() {
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null),
                                List.of(new PaymentTransferInfo("77777777777", false, 100, null)),
                                false,
                                new CompanyName("companyName"),
                                null
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                null,
                null
        );
        CardsAuthRequestDetailsDto cardDetails = new CardsAuthRequestDetailsDto()
                .orderId(UUID.randomUUID().toString());
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId1",
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
                cardDetails,
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetails)
        );
        StateResponseDto ngpStateResponse = new StateResponseDto().url("https://example.com");
        /* preconditions */
        Mockito.when(npgClient.confirmPayment(any(), any(), any(), any())).thenReturn(Mono.just(ngpStateResponse));

        Mockito.when(npgApiKeyHandler.getApiKeyForPaymentMethod(any(), any())).thenReturn(Either.right("pspKey1"));
        /* test */
        StepVerifier.create(client.requestNpgCardsAuthorization(authorizationData, UUID.randomUUID().toString()))
                .expectNext(ngpStateResponse)
                .verifyComplete();
        String expectedApiKey = npgPspApiKeysConfig.get(authorizationData.pspId()).get();
        String expectedSessionId = authorizationData.sessionId().get();
        BigDecimal expectedGranTotalAmount = BigDecimal.valueOf(
                transaction
                        .getPaymentNotices()
                        .stream()
                        .mapToInt(paymentNotice -> paymentNotice.transactionAmount().value())
                        .sum() + authorizationData.fee()
        );
        verify(npgClient, times(1))
                .confirmPayment(any(), eq(expectedSessionId), eq(expectedGranTotalAmount), eq(expectedApiKey));
        verify(npgApiKeyHandler, times(1)).getApiKeyForPaymentMethod(NpgClient.PaymentMethod.CARDS, "pspId1");
    }

    @ParameterizedTest
    @ValueSource(
            ints = {
                    400,
                    401,
                    404
            }
    )
    void shouldThrowNpgNotRetryableErrorExceptionOn4xxForCardsWithNpg(Integer npgHttpErrorCode) {
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null),
                                List.of(new PaymentTransferInfo("77777777777", false, 100, null)),
                                false,
                                new CompanyName("companyName"),
                                null
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                null,
                null
        );
        CardsAuthRequestDetailsDto cardDetails = new CardsAuthRequestDetailsDto()
                .orderId(UUID.randomUUID().toString());
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId1",
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
                cardDetails,
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetails)
        );

        /* preconditions */
        HttpStatus npgErrorStatus = HttpStatus.valueOf(npgHttpErrorCode);
        Mockito.when(npgClient.confirmPayment(any(), any(), any(), any()))
                .thenReturn(
                        Mono.error(
                                new NpgResponseException(
                                        "NPG error",
                                        List.of(),
                                        Optional.of(npgErrorStatus),
                                        new WebClientResponseException(
                                                "api error",
                                                npgErrorStatus.value(),
                                                npgErrorStatus.getReasonPhrase(),
                                                null,
                                                null,
                                                null
                                        )
                                )
                        )
                );

        Mockito.when(npgApiKeyHandler.getApiKeyForPaymentMethod(any(), any())).thenReturn(Either.right("pspKey1"));
        /* test */
        String correlationId = UUID.randomUUID().toString();
        StepVerifier.create(client.requestNpgCardsAuthorization(authorizationData, correlationId))
                .expectErrorMatches(
                        error -> {
                            assertTrue(error instanceof NpgNotRetryableErrorException);
                            assertEquals(
                                    "Npg 4xx error for transactionId: [" + transaction.getTransactionId().value()
                                            + "], correlationId: [" + correlationId + "], HTTP status code: ["
                                            + npgHttpErrorCode + "]",
                                    error.getMessage()
                            );
                            return true;
                        }
                )
                .verify();
        verify(npgApiKeyHandler, times(1)).getApiKeyForPaymentMethod(NpgClient.PaymentMethod.CARDS, "pspId1");
    }

    @Test
    void shouldThrowGatewayTimeoutExceptionForCardsWithNpg() {
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null),
                                List.of(new PaymentTransferInfo("77777777777", false, 100, null)),
                                false,
                                new CompanyName("companyName"),
                                null
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                null,
                null
        );
        CardsAuthRequestDetailsDto cardDetails = new CardsAuthRequestDetailsDto()
                .orderId(UUID.randomUUID().toString());
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId1",
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
                cardDetails,
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.empty()
        );

        /* preconditions */
        Mockito.when(npgClient.confirmPayment(any(), any(), any(), any()))
                .thenReturn(
                        Mono.error(
                                new NpgResponseException(
                                        "NPG error",
                                        List.of(),
                                        Optional.of(HttpStatus.GATEWAY_TIMEOUT),
                                        new WebClientResponseException(
                                                "api error",
                                                HttpStatus.GATEWAY_TIMEOUT.value(),
                                                "INTERNAL_SERVER_ERROR",
                                                null,
                                                null,
                                                null
                                        )
                                )
                        )
                );

        Mockito.when(npgApiKeyHandler.getApiKeyForPaymentMethod(any(), any())).thenReturn(Either.right("pspKey1"));
        /* test */
        StepVerifier.create(client.requestNpgCardsAuthorization(authorizationData, UUID.randomUUID().toString()))
                .expectErrorMatches(
                        error -> error instanceof BadGatewayException
                )
                .verify();
        verify(npgApiKeyHandler, times(1)).getApiKeyForPaymentMethod(NpgClient.PaymentMethod.CARDS, "pspId1");
    }

    @Test
    void shouldThrowInternalServerErrorExceptionForCardsWithNpg() {
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null),
                                List.of(new PaymentTransferInfo("77777777777", false, 100, null)),
                                false,
                                new CompanyName("companyName"),
                                null
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                null,
                null
        );
        CardsAuthRequestDetailsDto cardDetails = new CardsAuthRequestDetailsDto()
                .orderId(UUID.randomUUID().toString());
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId1",
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
                cardDetails,
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.empty()
        );

        /* preconditions */
        Mockito.when(npgClient.confirmPayment(any(), any(), any(), any()))
                .thenReturn(
                        Mono.error(
                                new NpgResponseException(
                                        "NPG error",
                                        List.of(),
                                        Optional.of(HttpStatus.INTERNAL_SERVER_ERROR),
                                        new WebClientResponseException(
                                                "api error",
                                                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                                "INTERNAL SERVER ERROR",
                                                null,
                                                null,
                                                null
                                        )
                                )
                        )
                );

        Mockito.when(npgApiKeyHandler.getApiKeyForPaymentMethod(any(), any())).thenReturn(Either.right("pspKey1"));
        /* test */
        StepVerifier.create(client.requestNpgCardsAuthorization(authorizationData, UUID.randomUUID().toString()))
                .expectErrorMatches(
                        error -> error instanceof BadGatewayException
                )
                .verify();
        verify(npgApiKeyHandler, times(1)).getApiKeyForPaymentMethod(NpgClient.PaymentMethod.CARDS, "pspId1");
    }

    @Test
    void shouldReturnBuildSessionResponseForWalletWithNpgWithCards() {
        String walletId = UUID.randomUUID().toString();
        String orderId = "orderIdGenerated";
        String sessionId = "sessionId";
        String contractId = "contractId";
        String correlationId = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();
        TransactionId mockedTransactionId = new TransactionId("89e95dabdbb3414392e6e06f64832eba");
        TransactionActivated transaction = new TransactionActivated(
                mockedTransactionId,
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null),
                                List.of(new PaymentTransferInfo("77777777777", false, 100, null)),
                                false,
                                new CompanyName("companyName"),
                                null
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                null,
                null
        );
        WalletAuthRequestDetailsDto walletDetails = new WalletAuthRequestDetailsDto()
                .walletId(walletId);
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId1",
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
                walletDetails,
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.empty()
        );
        Mockito.when(uniqueIdUtils.generateUniqueId()).thenReturn(Mono.just(orderId));
        FieldsDto npgBuildSessionResponse = new FieldsDto().sessionId(sessionId)
                .state(WorkflowStateDto.READY_FOR_PAYMENT).securityToken("securityToken");
        /* preconditions */
        Mockito.when(
                npgClient.buildForm(
                        eq(UUID.fromString(correlationId)),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(orderId),
                        eq(null),
                        eq(NpgClient.PaymentMethod.CARDS),
                        eq(npgDefaultApiKey),
                        eq(contractId),
                        any()
                )
        ).thenReturn(Mono.just(npgBuildSessionResponse));

        Mockito.when(npgApiKeyHandler.getDefaultApiKey()).thenReturn(npgDefaultApiKey);
        Mockito.when(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .thenReturn(Mono.just(new CreateTokenResponseDto().token(MOCK_JWT_WITH_ORDERID)));

        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);
        /* test */
        StepVerifier
                .create(
                        client.requestNpgBuildSession(
                                authorizationData,
                                correlationId,
                                true,
                                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.IO.name(),
                                null,
                                userId
                        )
                )
                .expectNext(responseRequestNpgBuildSession)
                .verifyComplete();

        String npgNotificationUrl = UriComponentsBuilder
                .fromUriString(sessionUrlConfig.notificationUrl())
                .build(
                        Map.of(
                                "orderId",
                                orderId,
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();

        String npgOutcomeUrl = UriComponentsBuilder
                .fromUriString(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
                .queryParam("t", Instant.now().toEpochMilli())
                .build(
                        Map.of(
                                "clientId",
                                Transaction.ClientId.IO.name(),
                                "transactionId",
                                mockedTransactionId.value(),
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();

        verify(npgClient, times(1))
                .buildForm(
                        any(),
                        eq(URI.create(sessionUrlConfig.basePath())),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        npgOutcomeUrl,
                                        mockedTransactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )
                        ),
                        argThat(
                                new NpgNotificationUrlMatcher(
                                        npgNotificationUrl,
                                        mockedTransactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )
                        ),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        npgOutcomeUrl,
                                        mockedTransactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )

                        ),
                        eq(orderId),
                        eq(null),
                        any(),
                        any(),
                        eq(contractId),
                        any()
                );
        verify(npgClient, times(0))
                .buildFormForPayment(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()
                );
        verify(npgApiKeyHandler, times(1)).getDefaultApiKey();
    }

    @ParameterizedTest
    @ValueSource(
            ints = {
                    400,
                    401,
                    404
            }
    )
    void shouldThrowNpgNotRetryableErrorExceptionOn4xxForWalletWithNpg(Integer npgHttpErrorCode) {
        String walletId = UUID.randomUUID().toString();
        String orderId = "orderIdGenerated";
        String contractId = "contractId";
        String correlationId = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null),
                                List.of(new PaymentTransferInfo("77777777777", false, 100, null)),
                                false,
                                new CompanyName("companyName"),
                                null
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                null,
                null
        );
        WalletAuthRequestDetailsDto walletDetails = new WalletAuthRequestDetailsDto()
                .walletId(walletId);
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId1",
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
                walletDetails,
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetails)
        );
        Mockito.when(uniqueIdUtils.generateUniqueId()).thenReturn(Mono.just(orderId));
        Mockito.when(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .thenReturn(Mono.just(new CreateTokenResponseDto().token(MOCK_JWT)));

        /* preconditions */
        HttpStatus npgErrorStatus = HttpStatus.valueOf(npgHttpErrorCode);
        Mockito.when(
                npgClient.buildForm(
                        eq(UUID.fromString(correlationId)),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(orderId),
                        eq(null),
                        any(),
                        any(),
                        eq(contractId),
                        any()
                )
        )
                .thenReturn(
                        Mono.error(
                                new NpgResponseException(
                                        "NPG error",
                                        List.of(),
                                        Optional.of(npgErrorStatus),
                                        new WebClientResponseException(
                                                "api error",
                                                npgErrorStatus.value(),
                                                npgErrorStatus.getReasonPhrase(),
                                                null,
                                                null,
                                                null
                                        )
                                )
                        )
                );
        /* test */

        StepVerifier
                .create(
                        client.requestNpgBuildSession(
                                authorizationData,
                                correlationId,
                                true,
                                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.IO.name(),
                                null,
                                userId
                        )
                )
                .expectErrorMatches(
                        error -> {
                            assertTrue(error instanceof NpgNotRetryableErrorException);
                            assertEquals(
                                    "Npg 4xx error for transactionId: [" + transaction.getTransactionId().value()
                                            + "], correlationId: [" + correlationId + "], HTTP status code: ["
                                            + npgHttpErrorCode + "]",
                                    error.getMessage()
                            );
                            return true;
                        }
                )
                .verify();
    }

    @Test
    void shouldThrowGatewayTimeoutExceptionForWalletWithNpg() {
        String walletId = UUID.randomUUID().toString();
        String orderId = "orderIdGenerated";
        String contractId = "contractId";
        String correlationId = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null),
                                List.of(new PaymentTransferInfo("77777777777", false, 100, null)),
                                false,
                                new CompanyName("companyName"),
                                null
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                null,
                null
        );
        WalletAuthRequestDetailsDto walletDetails = new WalletAuthRequestDetailsDto()
                .walletId(walletId);
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId1",
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
                walletDetails,
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetails)
        );
        Mockito.when(uniqueIdUtils.generateUniqueId()).thenReturn(Mono.just(orderId));
        Mockito.when(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .thenReturn(Mono.just(new CreateTokenResponseDto().token(MOCK_JWT)));

        /* preconditions */
        Mockito.when(
                npgClient.buildForm(
                        eq(UUID.fromString(correlationId)),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(orderId),
                        eq(null),
                        any(),
                        any(),
                        eq(contractId),
                        any()
                )
        )
                .thenReturn(
                        Mono.error(
                                new NpgResponseException(
                                        "NPG error",
                                        List.of(),
                                        Optional.of(HttpStatus.GATEWAY_TIMEOUT),
                                        new WebClientResponseException(
                                                "api error",
                                                HttpStatus.GATEWAY_TIMEOUT.value(),
                                                "INTERNAL_SERVER_ERROR",
                                                null,
                                                null,
                                                null
                                        )
                                )
                        )
                );
        /* test */
        StepVerifier
                .create(
                        client.requestNpgBuildSession(
                                authorizationData,
                                correlationId,
                                true,
                                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.IO.name(),
                                null,
                                userId
                        )
                )
                .expectErrorMatches(
                        error -> error instanceof BadGatewayException
                )
                .verify();
    }

    @Test
    void shouldThrowInternalServerErrorExceptionForWalletWithNpg() {
        String walletId = UUID.randomUUID().toString();
        String orderId = "orderIdGenerated";
        String contractId = "contractId";
        String correlationId = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null),
                                List.of(new PaymentTransferInfo("77777777777", false, 100, null)),
                                false,
                                new CompanyName("companyName"),
                                null
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                null,
                null
        );
        WalletAuthRequestDetailsDto walletDetails = new WalletAuthRequestDetailsDto()
                .walletId(walletId);
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId1",
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
                walletDetails,
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetails)
        );
        Mockito.when(uniqueIdUtils.generateUniqueId()).thenReturn(Mono.just(orderId));
        Mockito.when(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .thenReturn(Mono.just(new CreateTokenResponseDto().token(MOCK_JWT)));

        /* preconditions */
        Mockito.when(
                npgClient.buildForm(
                        eq(UUID.fromString(correlationId)),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(orderId),
                        eq(null),
                        any(),
                        any(),
                        eq(contractId),
                        any()
                )
        )
                .thenReturn(
                        Mono.error(
                                new NpgResponseException(
                                        "NPG error",
                                        List.of(),
                                        Optional.of(HttpStatus.INTERNAL_SERVER_ERROR),
                                        new WebClientResponseException(
                                                "api error",
                                                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                                "INTERNAL SERVER ERROR",
                                                null,
                                                null,
                                                null
                                        )
                                )
                        )
                );
        /* test */
        StepVerifier
                .create(
                        client.requestNpgBuildSession(
                                authorizationData,
                                correlationId,
                                true,
                                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.IO.name(),
                                null,
                                userId
                        )
                )
                .expectErrorMatches(
                        error -> error instanceof BadGatewayException
                )
                .verify();
    }

    @ParameterizedTest
    @MethodSource("buildSessionInvalidBodyResponse")
    void shouldReturnBadGatewayExceptionFromBuildSessionForWalletWithNpg(FieldsDto npgBuildSessionResponse) {
        String walletId = UUID.randomUUID().toString();
        String orderId = "orderIdGenerated";
        String contractId = "contractId";
        String correlationId = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();
        TransactionId mockedTransactionId = new TransactionId("89e95dabdbb3414392e6e06f64832eba");
        TransactionActivated transaction = new TransactionActivated(
                mockedTransactionId,
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null),
                                List.of(new PaymentTransferInfo("77777777777", false, 100, null)),
                                false,
                                new CompanyName("companyName"),
                                null
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                null,
                null
        );
        WalletAuthRequestDetailsDto walletDetails = new WalletAuthRequestDetailsDto()
                .walletId(walletId);
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId1",
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
                walletDetails,
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetails)
        );
        Mockito.when(uniqueIdUtils.generateUniqueId()).thenReturn(Mono.just(orderId));
        Mockito.when(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .thenReturn(Mono.just(new CreateTokenResponseDto().token(MOCK_JWT_WITH_ORDERID)));

        /* preconditions */
        Mockito.when(
                npgClient.buildForm(
                        eq(UUID.fromString(correlationId)),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(orderId),
                        eq(null),
                        eq(NpgClient.PaymentMethod.CARDS),
                        eq(npgDefaultApiKey),
                        eq(contractId),
                        any()
                )
        ).thenReturn(Mono.just(npgBuildSessionResponse));

        Mockito.when(npgApiKeyHandler.getDefaultApiKey()).thenReturn(npgDefaultApiKey);

        StepVerifier
                .create(
                        client.requestNpgBuildSession(
                                authorizationData,
                                correlationId,
                                true,
                                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.IO.name(),
                                null,
                                userId
                        )
                )
                .expectErrorMatches(error -> error instanceof BadGatewayException)
                .verify();

        String npgOutcomeUrl = UriComponentsBuilder
                .fromUriString(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
                .queryParam("t", Instant.now().toEpochMilli())
                .build(
                        Map.of(
                                "clientId",
                                Transaction.ClientId.IO.name(),
                                "transactionId",
                                mockedTransactionId.value(),
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();

        String npgNotificationUrl = UriComponentsBuilder
                .fromUriString(sessionUrlConfig.notificationUrl())
                .build(
                        Map.of(
                                "orderId",
                                orderId,
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();

        verify(npgClient, times(1))
                .buildForm(
                        any(),
                        eq(URI.create(sessionUrlConfig.basePath())),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        npgOutcomeUrl,
                                        mockedTransactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )

                        ),
                        argThat(
                                new NpgNotificationUrlMatcher(
                                        npgNotificationUrl,
                                        mockedTransactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )
                        ),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        npgOutcomeUrl,
                                        mockedTransactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )

                        ),
                        eq(orderId),
                        eq(null),
                        any(),
                        any(),
                        eq(contractId),
                        any()
                );
        verify(npgClient, times(0))
                .buildFormForPayment(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()
                );
        verify(npgApiKeyHandler, times(1)).getDefaultApiKey();
    }

    private static Stream<Arguments> buildSessionInvalidBodyResponse() {
        return Stream.of(
                // npg operation result - expected outcome mappings
                Arguments.arguments(
                        new FieldsDto().sessionId("sessionId")
                                .state(WorkflowStateDto.READY_FOR_PAYMENT)
                ),
                Arguments.arguments(
                        new FieldsDto().securityToken("securityToken")
                                .state(WorkflowStateDto.READY_FOR_PAYMENT),
                        Arguments.arguments(
                                new FieldsDto().sessionId("sessionId")
                                        .securityToken("securityToken")
                        )
                ),
                Arguments.arguments(
                        new FieldsDto().sessionId("sessionId")
                                .securityToken("securityToken").state(WorkflowStateDto.CARD_DATA_COLLECTION)
                )
        );
    }

    @Test
    void shouldReturnBuildSessionResponseForWalletWithNpgForWalletApmMethod() {
        String walletId = UUID.randomUUID().toString();
        String orderId = "orderIdGenerated";
        String sessionId = "sessionId";
        String contractId = "contractId";
        String correlationId = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();
        TransactionId mockedTransactionId = new TransactionId("89e95dabdbb3414392e6e06f64832eba");
        TransactionActivated transaction = new TransactionActivated(
                mockedTransactionId,
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null),
                                List.of(new PaymentTransferInfo("77777777777", false, 100, null)),
                                false,
                                new CompanyName("companyName"),
                                null
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                null,
                null
        );
        WalletAuthRequestDetailsDto walletDetails = new WalletAuthRequestDetailsDto()
                .walletId(walletId);
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId1",
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
                walletDetails,
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetails)
        );
        int totalAmount = authorizationData.paymentNotices().stream().map(notice -> notice.transactionAmount())
                .mapToInt(TransactionAmount::value).sum() + authorizationData.fee();
        Mockito.when(uniqueIdUtils.generateUniqueId()).thenReturn(Mono.just(orderId));
        FieldsDto npgBuildSessionResponse = new FieldsDto().sessionId(sessionId)
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN)
                .securityToken("securityToken")
                .sessionId("sessionId")
                .url("http://localhost/redirectionUrl");
        /* preconditions */
        Mockito.when(
                npgClient.buildFormForPayment(
                        eq(UUID.fromString(correlationId)),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(orderId),
                        eq(null),
                        eq(NpgClient.PaymentMethod.PAYPAL),
                        any(),
                        eq(contractId),
                        eq(totalAmount),
                        any()
                )
        ).thenReturn(Mono.just(npgBuildSessionResponse));

        Mockito.when(npgApiKeyHandler.getApiKeyForPaymentMethod(any(), any())).thenReturn(Either.right("pspKey1"));
        Mockito.when(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .thenReturn(Mono.just(new CreateTokenResponseDto().token(MOCK_JWT_WITH_ORDERID)));

        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);
        /* test */
        StepVerifier
                .create(
                        client.requestNpgBuildApmPayment(
                                authorizationData,
                                correlationId,
                                true,
                                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.IO.name(),
                                null,
                                userId
                        )
                )
                .expectNext(responseRequestNpgBuildSession)
                .verifyComplete();

        String npgOutcomeUrl = UriComponentsBuilder
                .fromUriString(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
                .queryParam("t", Instant.now().toEpochMilli())
                .build(
                        Map.of(
                                "clientId",
                                Transaction.ClientId.IO.name(),
                                "transactionId",
                                mockedTransactionId.value(),
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();

        String npgNotificationUrl = UriComponentsBuilder
                .fromUriString(sessionUrlConfig.notificationUrl())
                .build(
                        Map.of(
                                "orderId",
                                orderId,
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();

        verify(npgClient, times(1))
                .buildFormForPayment(
                        any(),
                        eq(URI.create(sessionUrlConfig.basePath())),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        npgOutcomeUrl,
                                        mockedTransactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )

                        ),
                        argThat(
                                new NpgNotificationUrlMatcher(
                                        npgNotificationUrl,
                                        mockedTransactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )
                        ),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        npgOutcomeUrl,
                                        mockedTransactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )

                        ),
                        eq(orderId),
                        eq(null),
                        any(),
                        eq("pspKey1"),
                        eq(contractId),
                        eq(totalAmount),
                        any()
                );
        verify(npgClient, times(0))
                .buildForm(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(npgApiKeyHandler, times(1)).getApiKeyForPaymentMethod(NpgClient.PaymentMethod.PAYPAL, "pspId1");
    }

    @Test
    void shouldThrowErrorForWalletWithNpgForGenericApmMethodAndMissingKey() {
        String walletId = UUID.randomUUID().toString();
        String orderId = "orderIdGenerated";
        String sessionId = "sessionId";
        String contractId = "contractId";
        String correlationId = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null),
                                List.of(new PaymentTransferInfo("77777777777", false, 100, null)),
                                false,
                                new CompanyName("companyName"),
                                null
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                null,
                null
        );
        WalletAuthRequestDetailsDto walletDetails = new WalletAuthRequestDetailsDto()
                .walletId(walletId);
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId2",
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
                walletDetails,
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetails)
        );
        int totalAmount = authorizationData.paymentNotices().stream().map(notice -> notice.transactionAmount())
                .mapToInt(TransactionAmount::value).sum() + authorizationData.fee();
        Mockito.when(uniqueIdUtils.generateUniqueId()).thenReturn(Mono.just(orderId));
        FieldsDto npgBuildSessionResponse = new FieldsDto().sessionId(sessionId)
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN)
                .securityToken("securityToken")
                .sessionId("sessionId")
                .url("http://localhost/redirectionUrl");
        /* preconditions */
        String npgOutcomeUrl = UriComponentsBuilder
                .fromUriString(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
                .queryParam("t", Instant.now().toEpochMilli())
                .build(
                        Map.of(
                                "clientId",
                                Transaction.ClientId.IO.name(),
                                "transactionId",
                                transactionId.value(),
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();

        String npgNotificationUrl = UriComponentsBuilder
                .fromUriString(sessionUrlConfig.notificationUrl())
                .build(
                        Map.of(
                                "orderId",
                                orderId,
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();

        Mockito.when(
                npgClient.buildFormForPayment(
                        eq(UUID.fromString(correlationId)),
                        eq(URI.create(sessionUrlConfig.basePath())),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        npgOutcomeUrl,
                                        transactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )
                        ),
                        argThat(
                                new NpgNotificationUrlMatcher(
                                        npgNotificationUrl,
                                        transactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )
                        ),
                        eq(
                                URI.create(npgOutcomeUrl)
                        ),
                        eq(orderId),
                        eq(null),
                        eq(NpgClient.PaymentMethod.PAYPAL),
                        eq("pspKey1"),
                        eq(contractId),
                        eq(totalAmount),
                        any()
                )
        ).thenReturn(Mono.just(npgBuildSessionResponse));
        Mockito.when(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .thenReturn(Mono.just(new CreateTokenResponseDto().token(MOCK_JWT)));

        Mockito.when(npgApiKeyHandler.getApiKeyForPaymentMethod(any(), any()))
                .thenReturn(Either.left(new NpgApiKeyMissingPspRequestedException("pspId2", Set.of())));
        /* test */
        StepVerifier
                .create(
                        client.requestNpgBuildApmPayment(
                                authorizationData,
                                correlationId,
                                true,
                                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.IO.name(),
                                null,
                                userId
                        )
                )
                .expectError(NpgApiKeyMissingPspRequestedException.class)
                .verify();

        verify(npgClient, times(0))
                .buildFormForPayment(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(orderId),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()
                );
        verify(npgClient, times(0))
                .buildForm(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(npgApiKeyHandler, times(1)).getApiKeyForPaymentMethod(NpgClient.PaymentMethod.PAYPAL, "pspId2");
    }

    @Test
    void shouldReturnBuildSessionResponseForWalletWithNpgForApmMethod() {
        String orderId = "orderIdGenerated";
        String sessionId = "sessionId";
        String correlationId = UUID.randomUUID().toString();
        TransactionId mockedTransactionId = new TransactionId("89e95dabdbb3414392e6e06f64832eba");
        UUID userId = UUID.randomUUID();
        it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId clientId = it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.IO;
        TransactionActivated transaction = new TransactionActivated(
                mockedTransactionId,
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null),
                                List.of(new PaymentTransferInfo("77777777777", false, 100, null)),
                                false,
                                new CompanyName("companyName"),
                                null
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                clientId,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                null,
                null
        );
        ApmAuthRequestDetailsDto apmDetails = new ApmAuthRequestDetailsDto();
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId1",
                "BPAY",
                "brokerName",
                "pspChannelCode",
                "BANCOMATPAY",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "NPG",
                Optional.empty(),
                Optional.empty(),
                "VISA",
                apmDetails,
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetails)
        );
        int totalAmount = authorizationData.paymentNotices().stream().map(notice -> notice.transactionAmount())
                .mapToInt(TransactionAmount::value).sum() + authorizationData.fee();
        Mockito.when(uniqueIdUtils.generateUniqueId()).thenReturn(Mono.just(orderId));
        FieldsDto npgBuildSessionResponse = new FieldsDto().sessionId(sessionId)
                .state(WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN)
                .securityToken("securityToken")
                .sessionId("sessionId")
                .url("http://localhost/redirectionUrl");
        /* preconditions */
        Mockito.when(
                npgClient.buildFormForPayment(
                        eq(UUID.fromString(correlationId)),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(orderId),
                        eq(null),
                        eq(NpgClient.PaymentMethod.BANCOMATPAY),
                        any(),
                        eq(null),
                        eq(totalAmount),
                        any()
                )
        ).thenReturn(Mono.just(npgBuildSessionResponse));
        Mockito.when(npgApiKeyHandler.getApiKeyForPaymentMethod(any(), any())).thenReturn(Either.right("pspKey1"));
        Mockito.when(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .thenReturn(Mono.just(new CreateTokenResponseDto().token(MOCK_JWT_WITH_ORDERID)));

        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);
        /* test */
        StepVerifier
                .create(
                        client.requestNpgBuildApmPayment(
                                authorizationData,
                                correlationId,
                                false,
                                clientId.name(),
                                null,
                                userId
                        )
                )
                .expectNext(responseRequestNpgBuildSession)
                .verifyComplete();

        String npgNotificationUrl = UriComponentsBuilder
                .fromUriString(sessionUrlConfig.notificationUrl())
                .build(
                        Map.of(
                                "orderId",
                                orderId,
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();
        String npgOutcomeUrl = UriComponentsBuilder
                .fromUriString(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
                .queryParam("t", Instant.now().toEpochMilli())
                .build(
                        Map.of(
                                "clientId",
                                Transaction.ClientId.IO.name(),
                                "transactionId",
                                mockedTransactionId.value(),
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();
        verify(npgClient, times(1))
                .buildFormForPayment(
                        any(),
                        eq(URI.create(sessionUrlConfig.basePath())),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        npgOutcomeUrl,
                                        mockedTransactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )
                        ),
                        argThat(
                                new NpgNotificationUrlMatcher(
                                        npgNotificationUrl,
                                        mockedTransactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )
                        ),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        npgOutcomeUrl,
                                        mockedTransactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )

                        ),
                        eq(orderId),
                        eq(null),
                        any(),
                        eq("pspKey1"),
                        eq(null),
                        eq(totalAmount),
                        any()
                );
        verify(npgClient, times(0))
                .buildForm(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(npgApiKeyHandler, times(1)).getApiKeyForPaymentMethod(NpgClient.PaymentMethod.BANCOMATPAY, "pspId1");
    }

    private static Stream<Arguments> redirectRetrieveUrlPaymentMethodsTestMethodSource() {
        return redirectPaymentTypeCodeDescription.entrySet().stream().map(
                entry -> Arguments.of(entry.getKey(), entry.getValue())
        );
    }

    @ParameterizedTest
    @MethodSource("redirectRetrieveUrlPaymentMethodsTestMethodSource")
    void shouldPerformAuthorizationRequestRetrievingRedirectionUrl(
                                                                   String paymentTypeCode,
                                                                   String mappedPaymentMethodDescription
    ) {
        String pspId = "pspId";
        TransactionId mockedTransactionId = new TransactionId("89e95dabdbb3414392e6e06f64832eba");
        it.pagopa.ecommerce.commons.domain.v2.TransactionActivated transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivated(ZonedDateTime.now().toString());
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                mockedTransactionId,
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                pspId,
                paymentTypeCode,
                "brokerName",
                "pspChannelCode",
                "REDIRECT",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "REDIRECT",
                Optional.empty(),
                Optional.empty(),
                "N/A",
                new RedirectionAuthRequestDetailsDto(),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.empty()
        );
        int totalAmount = authorizationData.paymentNotices().stream().map(PaymentNotice::transactionAmount)
                .mapToInt(TransactionAmount::value).sum() + authorizationData.fee();
        RedirectUrlRequestDto redirectUrlRequestDto = new RedirectUrlRequestDto()
                .idPaymentMethod(paymentTypeCode)
                .amount(totalAmount)
                .idPsp(pspId)
                .idTransaction(mockedTransactionId.value())
                .description(transaction.getPaymentNotices().get(0).transactionDescription().value())
                .touchpoint(RedirectUrlRequestDto.TouchpointEnum.CHECKOUT)
                .paymentMethod(mappedPaymentMethodDescription)
                .paName(it.pagopa.ecommerce.commons.v2.TransactionTestUtils.COMPANY_NAME);

        String urlBack = UriComponentsBuilder
                .fromUriString(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
                .queryParam("t", Instant.now().toEpochMilli())
                .build(
                        Map.of(
                                "clientId",
                                RedirectUrlRequestDto.TouchpointEnum.CHECKOUT.getValue(),
                                "transactionId",
                                authorizationData.transactionId().value(),
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();

        RedirectUrlResponseDto redirectUrlResponseDto = new RedirectUrlResponseDto()
                .timeout(60000)
                .url("http://redirectionUrl")
                .idPSPTransaction("idPspTransaction");
        given(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .willReturn(Mono.just(new CreateTokenResponseDto().token(MOCK_JWT)));
        given(nodeForwarderClient.proxyRequest(any(), any(), any(), any())).willReturn(
                Mono.just(
                        new NodeForwarderClient.NodeForwarderResponse<>(
                                redirectUrlResponseDto,
                                Optional.of(authorizationData.transactionId().value())
                        )
                )
        );
        Hooks.onOperatorDebug();
        /* test */
        StepVerifier.create(
                client.requestRedirectUrlAuthorization(
                        authorizationData,
                        RedirectUrlRequestDto.TouchpointEnum.CHECKOUT,
                        UUID.fromString(USER_ID)
                )
        )
                .expectNext(redirectUrlResponseDto)
                .verifyComplete();
        verify(nodeForwarderClient, times(1)).proxyRequest(
                argThat(request -> {
                    URI urlBackExpected = request.getUrlBack();
                    assertEquals(
                            redirectUrlRequestDto,
                            new RedirectUrlRequestDto()
                                    .idPaymentMethod(request.getIdPaymentMethod())
                                    .paymentMethod(request.getPaymentMethod())
                                    .amount(request.getAmount())
                                    .idPsp(request.getIdPsp())
                                    .idTransaction(request.getIdTransaction())
                                    .description(request.getDescription())
                                    .touchpoint(request.getTouchpoint())
                                    .paName(request.getPaName())
                    );
                    assertTrue(
                            new NpgOutcomeUrlMatcher(
                                    urlBack,
                                    authorizationData.transactionId().value(),
                                    null,
                                    authorizationData.paymentInstrumentId()
                            ).matches(urlBackExpected)
                    );
                    return true;
                }),
                eq(URI.create("http://redirect/%s".formatted(paymentTypeCode))),
                eq(authorizationData.transactionId().value()),
                eq(RedirectUrlResponseDto.class)
        );
    }

    @ParameterizedTest
    @MethodSource("redirectRetrieveUrlPaymentMethodsTestMethodSource")
    void shouldPerformAuthorizationRequestRetrievingRedirectionUrlWithLongPaName(
                                                                                 String paymentTypeCode,
                                                                                 String mappedPaymentMethodDescription
    ) {
        String pspId = "pspId";
        String longPaName = it.pagopa.ecommerce.commons.v2.TransactionTestUtils.COMPANY_NAME.repeat(6) + "abcde";
        String expectedPaName = it.pagopa.ecommerce.commons.v2.TransactionTestUtils.COMPANY_NAME.repeat(6) + "a...";
        TransactionId mockedTransactionId = new TransactionId("89e95dabdbb3414392e6e06f64832eba");
        assertTrue(longPaName.length() > 70);
        assertEquals(70, expectedPaName.length());

        it.pagopa.ecommerce.commons.domain.v2.TransactionActivated transaction = new it.pagopa.ecommerce.commons.domain.v2.TransactionActivated(
                mockedTransactionId,
                List.of(
                        new it.pagopa.ecommerce.commons.domain.v2.PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode("paymentContextCode"),
                                List.of(
                                        new PaymentTransferInfo(
                                                "transferPAFiscalCode",
                                                TRANSFER_DIGITAL_STAMP,
                                                TRANSFER_AMOUNT,
                                                "transferCategory"
                                        )
                                ),
                                false,
                                new CompanyName(longPaName),
                                null
                        )
                ),
                EMAIL,
                "",
                "",
                ZonedDateTime.now(),
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                "ecIdCart",
                900,
                new EmptyTransactionGatewayActivationData(),
                USER_ID
        );

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                pspId,
                paymentTypeCode,
                "brokerName",
                "pspChannelCode",
                "REDIRECT",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "REDIRECT",
                Optional.empty(),
                Optional.empty(),
                "N/A",
                new RedirectionAuthRequestDetailsDto(),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetails)
        );
        int totalAmount = authorizationData.paymentNotices().stream().map(PaymentNotice::transactionAmount)
                .mapToInt(TransactionAmount::value).sum() + authorizationData.fee();
        RedirectUrlRequestDto redirectUrlRequestDto = new RedirectUrlRequestDto()
                .idPaymentMethod(paymentTypeCode)
                .amount(totalAmount)
                .idPsp(pspId)
                .idTransaction(transaction.getTransactionId().value())
                .description(transaction.getPaymentNotices().get(0).transactionDescription().value())
                .touchpoint(RedirectUrlRequestDto.TouchpointEnum.CHECKOUT)
                .paymentMethod(mappedPaymentMethodDescription)
                .paName(expectedPaName);

        String urlBack = UriComponentsBuilder
                .fromUriString(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
                .queryParam("t", Instant.now().toEpochMilli())
                .build(
                        Map.of(
                                "clientId",
                                RedirectUrlRequestDto.TouchpointEnum.CHECKOUT.getValue(),
                                "transactionId",
                                authorizationData.transactionId().value(),
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();

        RedirectUrlResponseDto redirectUrlResponseDto = new RedirectUrlResponseDto()
                .timeout(60000)
                .url("http://redirectionUrl")
                .idPSPTransaction("idPspTransaction");
        given(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .willReturn(Mono.just(new CreateTokenResponseDto().token(MOCK_JWT)));

        given(nodeForwarderClient.proxyRequest(any(), any(), any(), any())).willReturn(
                Mono.just(
                        new NodeForwarderClient.NodeForwarderResponse<>(
                                redirectUrlResponseDto,
                                Optional.of(authorizationData.transactionId().value())
                        )
                )
        );
        Hooks.onOperatorDebug();
        /* test */
        StepVerifier.create(
                client.requestRedirectUrlAuthorization(
                        authorizationData,
                        RedirectUrlRequestDto.TouchpointEnum.CHECKOUT,
                        UUID.fromString(USER_ID)
                )
        )
                .expectNext(redirectUrlResponseDto)
                .verifyComplete();
        verify(nodeForwarderClient, times(1)).proxyRequest(
                argThat(request -> {
                    URI urlBackExpected = request.getUrlBack();
                    assertEquals(
                            redirectUrlRequestDto,
                            new RedirectUrlRequestDto()
                                    .idPaymentMethod(request.getIdPaymentMethod())
                                    .paymentMethod(request.getPaymentMethod())
                                    .amount(request.getAmount())
                                    .idPsp(request.getIdPsp())
                                    .idTransaction(request.getIdTransaction())
                                    .description(request.getDescription())
                                    .touchpoint(request.getTouchpoint())
                                    .paName(expectedPaName)
                    );
                    assertTrue(
                            new NpgOutcomeUrlMatcher(
                                    urlBack,
                                    authorizationData.transactionId().value(),
                                    null,
                                    authorizationData.paymentInstrumentId()
                            ).matches(urlBackExpected)
                    );
                    return true;
                }),
                eq(URI.create("http://redirect/%s".formatted(paymentTypeCode))),
                eq(authorizationData.transactionId().value()),
                eq(RedirectUrlResponseDto.class)
        );
    }

    @ParameterizedTest
    @MethodSource("redirectRetrieveUrlPaymentMethodsTestMethodSource")
    void shouldPerformAuthorizationRequestRetrievingRedirectionUrlWithoutPaNameWhenMultiplePaymentNotices(
                                                                                                          String paymentTypeCode,
                                                                                                          String mappedPaymentMethodDescription
    ) {
        String pspId = "pspId";
        TransactionId mockedTransactionId = new TransactionId("89e95dabdbb3414392e6e06f64832eba");
        it.pagopa.ecommerce.commons.domain.v2.TransactionActivated transaction = new it.pagopa.ecommerce.commons.domain.v2.TransactionActivated(
                mockedTransactionId,
                List.of(
                        new it.pagopa.ecommerce.commons.domain.v2.PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode("paymentContextCode"),
                                List.of(
                                        new PaymentTransferInfo(
                                                "transferPAiscalCode",
                                                TRANSFER_DIGITAL_STAMP,
                                                TRANSFER_AMOUNT,
                                                "transferCategory"
                                        )
                                ),
                                false,
                                new CompanyName("companyName"),
                                null
                        ),
                        new it.pagopa.ecommerce.commons.domain.v2.PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111112"),
                                new TransactionAmount(200),
                                new TransactionDescription("description2"),
                                new PaymentContextCode("paymentContextCode2"),
                                List.of(
                                        new PaymentTransferInfo(
                                                "transferPAiscalCode2",
                                                TRANSFER_DIGITAL_STAMP,
                                                TRANSFER_AMOUNT,
                                                "transferCategory2"
                                        )
                                ),
                                false,
                                new CompanyName("companyName2"),
                                null
                        )
                ),
                EMAIL,
                "",
                "",
                ZonedDateTime.now(),
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                "ecIdCart",
                900,
                new EmptyTransactionGatewayActivationData(),
                USER_ID
        );
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                pspId,
                paymentTypeCode,
                "brokerName",
                "pspChannelCode",
                "REDIRECT",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "REDIRECT",
                Optional.empty(),
                Optional.empty(),
                "N/A",
                new RedirectionAuthRequestDetailsDto(),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.empty()
        );
        int totalAmount = authorizationData.paymentNotices().stream().map(PaymentNotice::transactionAmount)
                .mapToInt(TransactionAmount::value).sum() + authorizationData.fee();
        RedirectUrlRequestDto redirectUrlRequestDto = new RedirectUrlRequestDto()
                .idPaymentMethod(paymentTypeCode)
                .amount(totalAmount)
                .idPsp(pspId)
                .idTransaction(transaction.getTransactionId().value())
                .description(transaction.getPaymentNotices().get(0).transactionDescription().value())
                .touchpoint(RedirectUrlRequestDto.TouchpointEnum.CHECKOUT)
                .paymentMethod(mappedPaymentMethodDescription)
                .paName(null);

        String urlBack = UriComponentsBuilder
                .fromUriString(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
                .queryParam("t", Instant.now().toEpochMilli())
                .build(
                        Map.of(
                                "clientId",
                                RedirectUrlRequestDto.TouchpointEnum.CHECKOUT.getValue(),
                                "transactionId",
                                authorizationData.transactionId().value(),
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();

        RedirectUrlResponseDto redirectUrlResponseDto = new RedirectUrlResponseDto()
                .timeout(60000)
                .url("http://redirectionUrl")
                .idPSPTransaction("idPspTransaction");
        given(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .willReturn(Mono.just(new CreateTokenResponseDto().token(MOCK_JWT)));

        given(nodeForwarderClient.proxyRequest(any(), any(), any(), any())).willReturn(
                Mono.just(
                        new NodeForwarderClient.NodeForwarderResponse<>(
                                redirectUrlResponseDto,
                                Optional.of(authorizationData.transactionId().value())
                        )
                )
        );
        Hooks.onOperatorDebug();
        /* test */
        StepVerifier.create(
                client.requestRedirectUrlAuthorization(
                        authorizationData,
                        RedirectUrlRequestDto.TouchpointEnum.CHECKOUT,
                        UUID.fromString(USER_ID)
                )
        )
                .expectNext(redirectUrlResponseDto)
                .verifyComplete();
        verify(nodeForwarderClient, times(1)).proxyRequest(
                argThat(request -> {
                    URI urlBackExpected = request.getUrlBack();
                    assertEquals(
                            redirectUrlRequestDto,
                            new RedirectUrlRequestDto()
                                    .idPaymentMethod(request.getIdPaymentMethod())
                                    .paymentMethod(request.getPaymentMethod())
                                    .amount(request.getAmount())
                                    .idPsp(request.getIdPsp())
                                    .idTransaction(request.getIdTransaction())
                                    .description(request.getDescription())
                                    .touchpoint(request.getTouchpoint())
                                    .paName(request.getPaName())
                    );
                    assertTrue(
                            new NpgOutcomeUrlMatcher(
                                    urlBack,
                                    authorizationData.transactionId().value(),
                                    null,
                                    authorizationData.paymentInstrumentId()
                            ).matches(urlBackExpected)
                    );
                    return true;
                }),
                eq(URI.create("http://redirect/%s".formatted(paymentTypeCode))),
                eq(authorizationData.transactionId().value()),
                eq(RedirectUrlResponseDto.class)
        );
    }

    private static Stream<Arguments> errorRetrievingRedirectionUrl() {
        return Stream.of(
                Arguments.of(HttpStatus.BAD_REQUEST, AlreadyProcessedException.class),
                Arguments.of(HttpStatus.UNAUTHORIZED, AlreadyProcessedException.class),
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, BadGatewayException.class),
                Arguments.of(HttpStatus.GATEWAY_TIMEOUT, BadGatewayException.class)
        );
    }

    @ParameterizedTest
    @MethodSource("errorRetrievingRedirectionUrl")
    void shouldHandleErrorRetrievingRedirectionUrl(
                                                   HttpStatus httpResponseStatusCode,
                                                   Class<? extends Exception> expectedMappedException
    ) {
        String pspId = "pspId";
        TransactionId mockedTransactionId = new TransactionId("89e95dabdbb3414392e6e06f64832eba");
        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                mockedTransactionId,
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                pspId,
                "RBPS",
                "brokerName",
                "pspChannelCode",
                "REDIRECT",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "REDIRECT",
                Optional.empty(),
                Optional.empty(),
                "N/A",
                new RedirectionAuthRequestDetailsDto(),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetails)
        );
        String idPaymentMethod = "RBPS";
        int totalAmount = authorizationData.paymentNotices().stream().map(PaymentNotice::transactionAmount)
                .mapToInt(TransactionAmount::value).sum() + authorizationData.fee();
        RedirectUrlRequestDto redirectUrlRequestDto = new RedirectUrlRequestDto()
                .idPaymentMethod(idPaymentMethod)
                .paymentMethod("Redirect payment type code description RBPS")
                .amount(totalAmount)
                .idPsp(pspId)
                .idTransaction(mockedTransactionId.value())
                .description(transaction.getPaymentNotices().get(0).transactionDescription().value())
                .touchpoint(RedirectUrlRequestDto.TouchpointEnum.CHECKOUT);

        String urlBack = UriComponentsBuilder
                .fromUriString(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
                .queryParam("t", Instant.now().toEpochMilli())
                .build(
                        Map.of(
                                "clientId",
                                RedirectUrlRequestDto.TouchpointEnum.CHECKOUT.getValue(),
                                "transactionId",
                                authorizationData.transactionId().value(),
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();
        given(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .willReturn(Mono.just(new CreateTokenResponseDto().token(MOCK_JWT)));
        given(nodeForwarderClient.proxyRequest(any(), any(), any(), any())).willReturn(
                Mono.error(
                        new NodeForwarderClientException(
                                "Error",
                                new WebClientResponseException(
                                        "Redirect error",
                                        httpResponseStatusCode.value(),
                                        httpResponseStatusCode.getReasonPhrase(),
                                        null,
                                        null,
                                        null
                                )
                        )
                )
        );
        Hooks.onOperatorDebug();
        /* test */
        StepVerifier.create(
                client.requestRedirectUrlAuthorization(
                        authorizationData,
                        RedirectUrlRequestDto.TouchpointEnum.CHECKOUT,
                        UUID.fromString(USER_ID)
                )
        )
                .expectError(expectedMappedException)
                .verify();
        verify(nodeForwarderClient, times(1)).proxyRequest(
                argThat(request -> {
                    URI urlBackExpected = request.getUrlBack();
                    assertEquals(
                            redirectUrlRequestDto,
                            new RedirectUrlRequestDto()
                                    .idPaymentMethod(request.getIdPaymentMethod())
                                    .paymentMethod(request.getPaymentMethod())
                                    .amount(request.getAmount())
                                    .idPsp(request.getIdPsp())
                                    .idTransaction(request.getIdTransaction())
                                    .description(request.getDescription())
                                    .touchpoint(request.getTouchpoint())
                    );
                    assertTrue(
                            new NpgOutcomeUrlMatcher(
                                    urlBack,
                                    authorizationData.transactionId().value(),
                                    null,
                                    authorizationData.paymentInstrumentId()
                            ).matches(urlBackExpected)
                    );
                    return true;
                }),
                eq(URI.create("http://redirect/RBPS")),
                eq(authorizationData.transactionId().value()),
                eq(RedirectUrlResponseDto.class)
        );
    }

    @Test
    void shouldHandleErrorRetrievingRedirectionUrlWithGenericException() {
        String pspId = "pspId";
        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());
        TransactionId mockedTransactionId = new TransactionId("89e95dabdbb3414392e6e06f64832eba");
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                mockedTransactionId,
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                pspId,
                "RBPS",
                "brokerName",
                "pspChannelCode",
                "REDIRECT",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "REDIRECT",
                Optional.empty(),
                Optional.empty(),
                "N/A",
                new RedirectionAuthRequestDetailsDto(),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetails)
        );
        String idPaymentMethod = "RBPS";
        int totalAmount = authorizationData.paymentNotices().stream().map(PaymentNotice::transactionAmount)
                .mapToInt(TransactionAmount::value).sum() + authorizationData.fee();
        RedirectUrlRequestDto redirectUrlRequestDto = new RedirectUrlRequestDto()
                .paymentMethod("Redirect payment type code description RBPS")
                .idPaymentMethod(idPaymentMethod)
                .amount(totalAmount)
                .idPsp(pspId)
                .idTransaction(mockedTransactionId.value())
                .description(transaction.getPaymentNotices().get(0).transactionDescription().value())
                .touchpoint(RedirectUrlRequestDto.TouchpointEnum.CHECKOUT);

        String urlBack = UriComponentsBuilder
                .fromUriString(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
                .queryParam("t", Instant.now().toEpochMilli())
                .build(
                        Map.of(
                                "clientId",
                                RedirectUrlRequestDto.TouchpointEnum.CHECKOUT.getValue(),
                                "transactionId",
                                authorizationData.transactionId().value(),
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();
        given(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .willReturn(Mono.just(new CreateTokenResponseDto().token(MOCK_JWT)));

        given(nodeForwarderClient.proxyRequest(any(), any(), any(), any())).willReturn(
                Mono.error(
                        new NodeForwarderClientException(
                                "Error",
                                new NullPointerException()
                        )
                )
        );
        Hooks.onOperatorDebug();
        /* test */
        StepVerifier.create(
                client.requestRedirectUrlAuthorization(
                        authorizationData,
                        RedirectUrlRequestDto.TouchpointEnum.CHECKOUT,
                        UUID.fromString(USER_ID)
                )
        )
                .expectError(BadGatewayException.class)
                .verify();
        verify(nodeForwarderClient, times(1)).proxyRequest(
                argThat(request -> {
                    URI urlBackExpected = request.getUrlBack();
                    assertEquals(
                            redirectUrlRequestDto,
                            new RedirectUrlRequestDto()
                                    .idPaymentMethod(request.getIdPaymentMethod())
                                    .paymentMethod(request.getPaymentMethod())
                                    .amount(request.getAmount())
                                    .idPsp(request.getIdPsp())
                                    .idTransaction(request.getIdTransaction())
                                    .description(request.getDescription())
                                    .touchpoint(request.getTouchpoint())
                    );
                    assertTrue(
                            new NpgOutcomeUrlMatcher(
                                    urlBack,
                                    authorizationData.transactionId().value(),
                                    null,
                                    authorizationData.paymentInstrumentId()
                            ).matches(urlBackExpected)
                    );
                    return true;
                }),
                eq(URI.create("http://redirect/RBPS")),
                eq(authorizationData.transactionId().value()),
                eq(RedirectUrlResponseDto.class)
        );
    }

    @Test
    void shouldReturnErrorDuringRedirectPaymentTransactionForInvalidPspURL() {
        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId",
                "RBPS",
                "brokerName",
                "pspChannelCode",
                "REDIRECT",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "REDIRECT",
                Optional.empty(),
                Optional.empty(),
                "N/A",
                new RedirectionAuthRequestDetailsDto(),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.empty()
        );

        Hooks.onOperatorDebug();
        Map<String, String> redirectUrlMapping = new HashMap<>(redirectBeApiCallUriMap);
        Set<String> codeListTypeMapping = new HashSet<>(pspTypeCodesPspIdSet);
        redirectUrlMapping.remove("pspId-RBPS");
        codeListTypeMapping.remove("pspId-RBPS");
        PaymentGatewayClient redirectClient = new PaymentGatewayClient(
                objectMapper,
                mockUuidUtils,
                confidentialMailUtils,
                npgClient,
                sessionUrlConfig,
                uniqueIdUtils,
                TOKEN_VALIDITY_TIME_SECONDS,
                TOKEN_VALIDITY_TIME_SECONDS,
                nodeForwarderClient,
                new RedirectKeysConfiguration(redirectUrlMapping, codeListTypeMapping),
                npgApiKeyHandler,
                npgAuthorizationRetryExcludedErrorCodes,
                redirectPaymentTypeCodeDescription,
                jwtTokenIssuerClient
        );
        given(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .willReturn(Mono.just(new CreateTokenResponseDto().token(MOCK_JWT)));
        /* test */
        StepVerifier.create(
                redirectClient.requestRedirectUrlAuthorization(
                        authorizationData,
                        RedirectUrlRequestDto.TouchpointEnum.CHECKOUT,
                        UUID.fromString(USER_ID)
                )
        )
                .expectError(RedirectConfigurationException.class)
                .verify();
        verify(nodeForwarderClient, times(0)).proxyRequest(any(), any(), any(), any());
    }

    private static Stream<List<String>> npgNotRetryableErrorsTestMethodSource() {
        return Stream.of(
                List.of("GW0035"),
                List.of("GW0004"),
                List.of("GW0035", "GW0004"),
                List.of("GW0035", "GW0004", "GW0001"),
                List.of("GW0035", "GW0001"),
                List.of("GW0004", "GW0001")
        );
    }

    @ParameterizedTest
    @MethodSource("npgNotRetryableErrorsTestMethodSource")
    void shouldReturnNpgNotRetryableErrorExceptionForConfiguredErrorCodes(List<String> errors) {
        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null),
                                List.of(new PaymentTransferInfo("77777777777", false, 100, null)),
                                false,
                                new CompanyName("companyName"),
                                null
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                null,
                null
        );
        CardsAuthRequestDetailsDto cardDetails = new CardsAuthRequestDetailsDto()
                .orderId(UUID.randomUUID().toString());
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId1",
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
                cardDetails,
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetails)
        );
        HttpStatus httpStatusErrorCode = HttpStatus.INTERNAL_SERVER_ERROR;
        /* preconditions */
        Mockito.when(npgClient.confirmPayment(any(), any(), any(), any()))
                .thenReturn(
                        Mono.error(
                                new NpgResponseException(
                                        "NPG error",
                                        errors,
                                        Optional.of(httpStatusErrorCode),
                                        new WebClientResponseException(
                                                "api error",
                                                httpStatusErrorCode.value(),
                                                httpStatusErrorCode.name(),
                                                null,
                                                null,
                                                null
                                        )
                                )
                        )
                );

        Mockito.when(npgApiKeyHandler.getApiKeyForPaymentMethod(any(), any())).thenReturn(Either.right("pspKey1"));
        /* test */
        StepVerifier.create(client.requestNpgCardsAuthorization(authorizationData, UUID.randomUUID().toString()))
                .consumeErrorWith(
                        error -> {
                            assertInstanceOf(NpgNotRetryableErrorException.class, error);
                            assertEquals(
                                    "Npg received error codes: %s, retry excluded error codes: %s, HTTP status code: [%s]"
                                            .formatted(
                                                    errors,
                                                    npgAuthorizationRetryExcludedErrorCodes,
                                                    httpStatusErrorCode.value()
                                            ),
                                    error.getMessage()
                            );
                        }
                )
                .verify();
        verify(npgApiKeyHandler, times(1)).getApiKeyForPaymentMethod(NpgClient.PaymentMethod.CARDS, "pspId1");
    }

    private static Stream<Arguments> redirectRetrieveUrlPaymentMethodsTestSearch() throws URISyntaxException {

        return Stream.of(
                Arguments.of(
                        RedirectUrlRequestDto.TouchpointEnum.CHECKOUT,

                        "psp1",
                        "RBPR",
                        new URI("http://localhost:8096/redirections1/CHECKOUT")
                ),
                Arguments.of(
                        RedirectUrlRequestDto.TouchpointEnum.IO,

                        "psp1",
                        "RBPR",
                        new URI("http://localhost:8096/redirections1/IO")
                ),
                Arguments.of(
                        RedirectUrlRequestDto.TouchpointEnum.CHECKOUT,
                        "psp2",
                        "RBPB",
                        new URI("http://localhost:8096/redirections2")
                ),
                Arguments.of(
                        RedirectUrlRequestDto.TouchpointEnum.IO,
                        "psp2",
                        "RBPB",
                        new URI("http://localhost:8096/redirections2")
                ),
                Arguments.of(
                        RedirectUrlRequestDto.TouchpointEnum.CHECKOUT,
                        "psp3",
                        "RBPS",
                        new URI("http://localhost:8096/redirections3")
                ),
                Arguments.of(
                        RedirectUrlRequestDto.TouchpointEnum.IO,
                        "psp3",
                        "RBPS",
                        new URI("http://localhost:8096/redirections3")
                )
        );
    }

    @ParameterizedTest
    @MethodSource("redirectRetrieveUrlPaymentMethodsTestSearch")
    void shouldReturnURIDuringSearchRedirectURLSearchingIteratively(
                                                                    RedirectUrlRequestDto.TouchpointEnum touchpoint,
                                                                    String pspId,
                                                                    String paymentMethodId,
                                                                    URI expectedUri
    ) {
        Map<String, String> redirectUrlMapping = Map.of(
                "CHECKOUT-psp1-RBPR",
                "http://localhost:8096/redirections1/CHECKOUT",
                "IO-psp1-RBPR",
                "http://localhost:8096/redirections1/IO",
                "psp2-RBPB",
                "http://localhost:8096/redirections2",
                "RBPS",
                "http://localhost:8096/redirections3"
        );
        Set<String> redirectCodeTypeList = Set.of(
                "CHECKOUT-psp1-RBPR",
                "IO-psp1-RBPR",
                "psp2-RBPB",
                "RBPS"
        );
        PaymentGatewayClient redirectClient = new PaymentGatewayClient(
                objectMapper,
                mockUuidUtils,
                confidentialMailUtils,
                npgClient,
                sessionUrlConfig,
                uniqueIdUtils,
                TOKEN_VALIDITY_TIME_SECONDS,
                TOKEN_VALIDITY_TIME_SECONDS,
                nodeForwarderClient,
                new RedirectKeysConfiguration(redirectUrlMapping, redirectCodeTypeList),
                npgApiKeyHandler,
                npgAuthorizationRetryExcludedErrorCodes,
                redirectPaymentTypeCodeDescription,
                jwtTokenIssuerClient
        );

        it.pagopa.ecommerce.commons.domain.v2.TransactionActivated transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivated(ZonedDateTime.now().toString());
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                pspId,
                paymentMethodId,
                "brokerName",
                "pspChannelCode",
                paymentMethodId,
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "REDIRECT",
                Optional.empty(),
                Optional.empty(),
                "N/A",
                new RedirectionAuthRequestDetailsDto(),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetails)
        );

        RedirectUrlResponseDto redirectUrlResponseDto = new RedirectUrlResponseDto()
                .timeout(60000)
                .url("http://redirectionUrl")
                .idPSPTransaction("idPspTransaction");
        given(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .willReturn(Mono.just(new CreateTokenResponseDto().token(MOCK_JWT)));
        given(nodeForwarderClient.proxyRequest(any(), any(), any(), any())).willReturn(
                Mono.just(
                        new NodeForwarderClient.NodeForwarderResponse<>(
                                redirectUrlResponseDto,
                                Optional.of(authorizationData.transactionId().value())
                        )
                )
        );
        Hooks.onOperatorDebug();
        /* test */
        StepVerifier.create(
                redirectClient.requestRedirectUrlAuthorization(authorizationData, touchpoint, UUID.fromString(USER_ID))
        )
                .expectNext(redirectUrlResponseDto)
                .verifyComplete();
        verify(nodeForwarderClient, times(1)).proxyRequest(
                any(),
                eq(expectedUri),
                any(),
                any()
        );
    }

    @Test
    void shouldReturnErrorDuringSearchRedirectURLforInvalidSearchKey() {
        Map<String, String> redirectUrlMapping = Map.of(
                "CHECKOUT-psp1-RBPR",
                "http://localhost:8096/redirections1/CHECKOUT",
                "IO-psp1-RBPR",
                "http://localhost:8096/redirections1/IO",
                "psp2-RBPB",
                "http://localhost:8096/redirections2",
                "RBPS",
                "http://localhost:8096/redirections3"
        );
        Set<String> redirectCodeTypeList = Set.of(
                "CHECKOUT-psp1-RBPR",
                "IO-psp1-RBPR",
                "psp2-RBPB",
                "RBPS"
        );
        RedirectUrlRequestDto.TouchpointEnum touchpoint = RedirectUrlRequestDto.TouchpointEnum.CHECKOUT;
        String pspId = "pspId";
        String redirectPaymentMethodId = "RBPP";
        PaymentGatewayClient redirectClient = new PaymentGatewayClient(
                objectMapper,
                mockUuidUtils,
                confidentialMailUtils,
                npgClient,
                sessionUrlConfig,
                uniqueIdUtils,
                TOKEN_VALIDITY_TIME_SECONDS,
                TOKEN_VALIDITY_TIME_SECONDS,
                nodeForwarderClient,
                new RedirectKeysConfiguration(redirectUrlMapping, redirectCodeTypeList),
                npgApiKeyHandler,
                npgAuthorizationRetryExcludedErrorCodes,
                redirectPaymentTypeCodeDescription,
                jwtTokenIssuerClient
        );

        it.pagopa.ecommerce.commons.domain.v2.TransactionActivated transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivated(ZonedDateTime.now().toString());
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                pspId,
                redirectPaymentMethodId,
                "brokerName",
                "pspChannelCode",
                redirectPaymentMethodId,
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "REDIRECT",
                Optional.empty(),
                Optional.empty(),
                "N/A",
                new RedirectionAuthRequestDetailsDto(),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetails)
        );

        Hooks.onOperatorDebug();

        given(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .willReturn(Mono.just(new CreateTokenResponseDto().token(MOCK_JWT)));

        /* test */
        StepVerifier.create(
                redirectClient.requestRedirectUrlAuthorization(authorizationData, touchpoint, UUID.fromString(USER_ID))
        )
                .consumeErrorWith(
                        exp -> assertEquals(
                                "Error parsing Redirect PSP BACKEND_URLS configuration, cause: Missing key for redirect return url with following search parameters: touchpoint: [%s] pspId: [%s] paymentTypeCode: [%s]"
                                        .formatted(

                                                touchpoint,
                                                pspId,
                                                redirectPaymentMethodId
                                        ),
                                exp.getMessage()
                        )
                )
                .verify();
        verify(nodeForwarderClient, times(0)).proxyRequest(
                any(),
                any(),
                any(),
                any()
        );
    }

    @ParameterizedTest
    @MethodSource("redirectRetrieveUrlPaymentMethodsTestMethodSource")
    void shouldPerformAuthorizationRequestRetrieveUrlForRedirectTransactionWithoutConfiguredPaymentMethodDescription(
                                                                                                                     String paymentTypeCode
    ) {
        String pspId = "pspId";
        TransactionId mockedTransactionId = new TransactionId("89e95dabdbb3414392e6e06f64832eba");
        it.pagopa.ecommerce.commons.domain.v2.TransactionActivated transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivated(ZonedDateTime.now().toString());
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                mockedTransactionId,
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                pspId,
                paymentTypeCode,
                "brokerName",
                "pspChannelCode",
                "REDIRECT",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "REDIRECT",
                Optional.empty(),
                Optional.empty(),
                "N/A",
                new RedirectionAuthRequestDetailsDto(),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString(),
                Optional.of(contextualOnboardDetails)
        );
        int totalAmount = authorizationData.paymentNotices().stream().map(PaymentNotice::transactionAmount)
                .mapToInt(TransactionAmount::value).sum() + authorizationData.fee();
        RedirectUrlRequestDto redirectUrlRequestDto = new RedirectUrlRequestDto()
                .idPaymentMethod(paymentTypeCode)
                .amount(totalAmount)
                .idPsp(pspId)
                .idTransaction(mockedTransactionId.value())
                .description(transaction.getPaymentNotices().get(0).transactionDescription().value())
                .touchpoint(RedirectUrlRequestDto.TouchpointEnum.CHECKOUT)
                .paymentMethod(null)
                .paName(it.pagopa.ecommerce.commons.v2.TransactionTestUtils.COMPANY_NAME);

        String urlBack = UriComponentsBuilder
                .fromUriString(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
                .queryParam("t", Instant.now().toEpochMilli())
                .build(
                        Map.of(
                                "clientId",
                                RedirectUrlRequestDto.TouchpointEnum.CHECKOUT.getValue(),
                                "transactionId",
                                authorizationData.transactionId().value(),
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();

        RedirectUrlResponseDto redirectUrlResponseDto = new RedirectUrlResponseDto()
                .timeout(60000)
                .url("http://redirectionUrl")
                .idPSPTransaction("idPspTransaction");
        given(nodeForwarderClient.proxyRequest(any(), any(), any(), any())).willReturn(
                Mono.just(
                        new NodeForwarderClient.NodeForwarderResponse<>(
                                redirectUrlResponseDto,
                                Optional.of(authorizationData.transactionId().value())
                        )
                )
        );
        given(jwtTokenIssuerClient.createJWTToken(any(CreateTokenRequestDto.class)))
                .willReturn(Mono.just(new CreateTokenResponseDto().token(MOCK_JWT)));
        PaymentGatewayClient redirectClient = new PaymentGatewayClient(
                objectMapper,
                mockUuidUtils,
                confidentialMailUtils,
                npgClient,
                sessionUrlConfig,
                uniqueIdUtils,
                TOKEN_VALIDITY_TIME_SECONDS,
                TOKEN_VALIDITY_TIME_SECONDS,
                nodeForwarderClient,
                configurationKeysConfig,
                npgApiKeyHandler,
                npgAuthorizationRetryExcludedErrorCodes,
                Map.of(),
                jwtTokenIssuerClient
        );
        Hooks.onOperatorDebug();
        /* test */
        StepVerifier.create(
                redirectClient.requestRedirectUrlAuthorization(
                        authorizationData,
                        RedirectUrlRequestDto.TouchpointEnum.CHECKOUT,
                        UUID.fromString(USER_ID)
                )
        )
                .expectNext(redirectUrlResponseDto)
                .verifyComplete();
        verify(nodeForwarderClient, times(1)).proxyRequest(
                argThat(request -> {
                    URI urlBackExpected = request.getUrlBack();
                    assertEquals(
                            redirectUrlRequestDto,
                            new RedirectUrlRequestDto()
                                    .idPaymentMethod(request.getIdPaymentMethod())
                                    .paymentMethod(request.getPaymentMethod())
                                    .amount(request.getAmount())
                                    .idPsp(request.getIdPsp())
                                    .idTransaction(request.getIdTransaction())
                                    .description(request.getDescription())
                                    .touchpoint(request.getTouchpoint())
                                    .paName(request.getPaName())
                    );
                    assertTrue(
                            new NpgOutcomeUrlMatcher(
                                    urlBack,
                                    authorizationData.transactionId().value(),
                                    null,
                                    authorizationData.paymentInstrumentId()
                            ).matches(urlBackExpected)
                    );
                    return true;
                }),
                eq(URI.create("http://redirect/%s".formatted(paymentTypeCode))),
                eq(authorizationData.transactionId().value()),
                eq(RedirectUrlResponseDto.class)
        );
    }

}
