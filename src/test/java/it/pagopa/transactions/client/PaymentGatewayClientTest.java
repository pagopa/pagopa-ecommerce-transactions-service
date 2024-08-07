package it.pagopa.transactions.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.client.NodeForwarderClient;
import it.pagopa.ecommerce.commons.client.NpgClient;
import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.activation.EmptyTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.exceptions.NodeForwarderClientException;
import it.pagopa.ecommerce.commons.exceptions.NpgApiKeyMissingPspRequestedException;
import it.pagopa.ecommerce.commons.exceptions.NpgResponseException;
import it.pagopa.ecommerce.commons.exceptions.RedirectConfigurationException;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldsDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.StateResponseDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.WorkflowStateDto;
import it.pagopa.ecommerce.commons.utils.NpgApiKeyConfiguration;
import it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig;
import it.pagopa.ecommerce.commons.utils.RedirectKeysConfiguration;
import it.pagopa.ecommerce.commons.utils.UniqueIdUtils;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlRequestDto;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlResponseDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.configurations.NpgSessionUrlConfig;
import it.pagopa.transactions.configurations.SecretsConfigurations;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.NpgNotRetryableErrorException;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import it.pagopa.transactions.utils.NpgNotificationUrlMatcher;
import it.pagopa.transactions.utils.NpgOutcomeUrlMatcher;
import it.pagopa.transactions.utils.UUIDUtils;
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

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static it.pagopa.ecommerce.commons.v1.TransactionTestUtils.*;
import static it.pagopa.ecommerce.commons.v2.TransactionTestUtils.USER_ID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
class PaymentGatewayClientTest {

    private PaymentGatewayClient client;

    @Mock
    UUIDUtils mockUuidUtils;

    @Mock
    ConfidentialMailUtils confidentialMailUtils;

    @Mock
    UniqueIdUtils uniqueIdUtils;

    private final String npgDefaultApiKey = UUID.randomUUID().toString();

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

    private final TransactionId transactionId = new TransactionId(UUID.randomUUID());

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    private static final String STRONG_KEY = "ODMzNUZBNTZENDg3NTYyREUyNDhGNDdCRUZDNzI3NDMzMzQwNTFEREZGQ0MyQzA5Mjc1RjY2NTQ1NDk5MDMxNzU5NDc0NUVFMTdDMDhGNzk4Q0Q3RENFMEJBODE1NURDREExNEY2Mzk4QzFEMTU0NTExNjUyMEExMzMwMTdDMDk";

    private static final int TOKEN_VALIDITY_TIME_SECONDS = 900;

    private final SecretKey jwtSecretKey = new SecretsConfigurations().npgNotificationSigningKey(STRONG_KEY);

    private final NodeForwarderClient<RedirectUrlRequestDto, RedirectUrlResponseDto> nodeForwarderClient = Mockito
            .mock(NodeForwarderClient.class);

    private final Set<String> codeTypeList = Arrays.stream(PaymentGatewayClient.RedirectPaymentMethodId.values())
            .map(PaymentGatewayClient.RedirectPaymentMethodId::toString)
            .map("pspId-%s"::formatted).collect(Collectors.toSet());

    private final Map<String, String> redirectBeApiCallUriMap = Arrays
            .stream(PaymentGatewayClient.RedirectPaymentMethodId.values())
            .map(PaymentGatewayClient.RedirectPaymentMethodId::toString)
            .collect(
                    Collectors.toMap("pspId-%s"::formatted, "http://redirect/%s"::formatted)
            );

    private final RedirectKeysConfiguration configurationKeysConfig = new RedirectKeysConfiguration(
            redirectBeApiCallUriMap,
            codeTypeList
    );

    private final Set<String> npgAuthorizationRetryExcludedErrorCodes = Set.of("GW0035", "GW0004");

    private final NpgApiKeyConfiguration npgApiKeyHandler = Mockito.mock(NpgApiKeyConfiguration.class);

    @BeforeEach
    private void init() {
        client = new PaymentGatewayClient(
                objectMapper,
                mockUuidUtils,
                confidentialMailUtils,
                npgClient,
                sessionUrlConfig,
                uniqueIdUtils,
                jwtSecretKey,
                TOKEN_VALIDITY_TIME_SECONDS,
                jwtSecretKey,
                TOKEN_VALIDITY_TIME_SECONDS,
                nodeForwarderClient,
                configurationKeysConfig,
                npgApiKeyHandler,
                npgAuthorizationRetryExcludedErrorCodes

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
                                new CompanyName("companyName")
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        /* test */

        StepVerifier.create(client.requestRedirectUrlAuthorization(authorizationData, any(), any()))
                .expectNextCount(0)
                .verifyComplete();

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
                                new CompanyName("companyName")
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
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
                                new CompanyName("companyName")
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
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
                                new CompanyName("companyName")
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
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
                                new CompanyName("companyName")
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
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
                                new CompanyName("companyName")
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
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
                        eq(contractId)
                )
        ).thenReturn(Mono.just(npgBuildSessionResponse));

        Mockito.when(npgApiKeyHandler.getDefaultApiKey()).thenReturn(npgDefaultApiKey);

        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);
        /* test */
        StepVerifier
                .create(
                        client.requestNpgBuildSession(
                                authorizationData,
                                correlationId,
                                true,
                                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.IO.name(),
                                userId
                        )
                )
                .expectNext(responseRequestNpgBuildSession)
                .verifyComplete();

        String npgNotificationUrl = UriComponentsBuilder
                .fromHttpUrl(sessionUrlConfig.notificationUrl())
                .build(
                        Map.of(
                                "orderId",
                                orderId,
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();

        String npgOutcomeUrl = UriComponentsBuilder
                .fromHttpUrl(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
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

        String outcomeUrlPrefix = npgOutcomeUrl
                .substring(0, npgOutcomeUrl.indexOf("sessionToken=") + "sessionToken=".length());

        String npgNotificationUrlPrefix = npgNotificationUrl
                .substring(0, npgNotificationUrl.indexOf("sessionToken=") + "sessionToken=".length());
        verify(npgClient, times(1))
                .buildForm(
                        any(),
                        eq(URI.create(sessionUrlConfig.basePath())),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        outcomeUrlPrefix,
                                        transactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )
                        ),
                        argThat(
                                new NpgNotificationUrlMatcher(
                                        npgNotificationUrlPrefix,
                                        transactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )
                        ),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        outcomeUrlPrefix,
                                        transactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )

                        ),
                        eq(orderId),
                        eq(null),
                        any(),
                        any(),
                        eq(contractId)
                );
        verify(npgClient, times(0))
                .buildFormForPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
                                new CompanyName("companyName")
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );
        Mockito.when(uniqueIdUtils.generateUniqueId()).thenReturn(Mono.just(orderId));
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
                        eq(contractId)
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
                                new CompanyName("companyName")
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );
        Mockito.when(uniqueIdUtils.generateUniqueId()).thenReturn(Mono.just(orderId));
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
                        eq(contractId)
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
                                new CompanyName("companyName")
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );
        Mockito.when(uniqueIdUtils.generateUniqueId()).thenReturn(Mono.just(orderId));
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
                        eq(contractId)
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
                                new CompanyName("companyName")
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );
        Mockito.when(uniqueIdUtils.generateUniqueId()).thenReturn(Mono.just(orderId));
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
                        eq(contractId)
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
                                userId
                        )
                )
                .expectErrorMatches(error -> error instanceof BadGatewayException)
                .verify();

        String npgOutcomeUrl = UriComponentsBuilder
                .fromHttpUrl(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
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

        String outcomeUrlPrefix = npgOutcomeUrl
                .substring(0, npgOutcomeUrl.indexOf("sessionToken=") + "sessionToken=".length());

        String npgNotificationUrl = UriComponentsBuilder
                .fromHttpUrl(sessionUrlConfig.notificationUrl())
                .build(
                        Map.of(
                                "orderId",
                                orderId,
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();
        String npgNotificationUrlPrefix = npgNotificationUrl
                .substring(0, npgNotificationUrl.indexOf("sessionToken=") + "sessionToken=".length());
        verify(npgClient, times(1))
                .buildForm(
                        any(),
                        eq(URI.create(sessionUrlConfig.basePath())),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        outcomeUrlPrefix,
                                        transactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )

                        ),
                        argThat(
                                new NpgNotificationUrlMatcher(
                                        npgNotificationUrlPrefix,
                                        transactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )
                        ),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        outcomeUrlPrefix,
                                        transactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )

                        ),
                        eq(orderId),
                        eq(null),
                        any(),
                        any(),
                        eq(contractId)
                );
        verify(npgClient, times(0))
                .buildFormForPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
                                new CompanyName("companyName")
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
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
                        eq(totalAmount)
                )
        ).thenReturn(Mono.just(npgBuildSessionResponse));

        Mockito.when(npgApiKeyHandler.getApiKeyForPaymentMethod(any(), any())).thenReturn(Either.right("pspKey1"));

        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);
        /* test */
        StepVerifier
                .create(
                        client.requestNpgBuildApmPayment(
                                authorizationData,
                                correlationId,
                                true,
                                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.IO.name(),
                                userId
                        )
                )
                .expectNext(responseRequestNpgBuildSession)
                .verifyComplete();

        String npgOutcomeUrl = UriComponentsBuilder
                .fromHttpUrl(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
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

        String outcomeUrlPrefix = npgOutcomeUrl
                .substring(0, npgOutcomeUrl.indexOf("sessionToken=") + "sessionToken=".length());

        String npgNotificationUrl = UriComponentsBuilder
                .fromHttpUrl(sessionUrlConfig.notificationUrl())
                .build(
                        Map.of(
                                "orderId",
                                orderId,
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();
        String npgNotificationUrlPrefix = npgNotificationUrl
                .substring(0, npgNotificationUrl.indexOf("sessionToken=") + "sessionToken=".length());
        verify(npgClient, times(1))
                .buildFormForPayment(
                        any(),
                        eq(URI.create(sessionUrlConfig.basePath())),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        outcomeUrlPrefix,
                                        transactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )

                        ),
                        argThat(
                                new NpgNotificationUrlMatcher(
                                        npgNotificationUrlPrefix,
                                        transactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )
                        ),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        outcomeUrlPrefix,
                                        transactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )

                        ),
                        eq(orderId),
                        eq(null),
                        any(),
                        eq("pspKey1"),
                        eq(contractId),
                        eq(totalAmount)
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
                                new CompanyName("companyName")
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
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
                .fromHttpUrl(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
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

        String outcomeUrlPrefix = npgOutcomeUrl
                .substring(0, npgOutcomeUrl.indexOf("sessionToken=") + "sessionToken=".length());

        String npgNotificationUrl = UriComponentsBuilder
                .fromHttpUrl(sessionUrlConfig.notificationUrl())
                .build(
                        Map.of(
                                "orderId",
                                orderId,
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();
        String npgNotificationUrlPrefix = npgNotificationUrl
                .substring(0, npgNotificationUrl.indexOf("sessionToken=") + "sessionToken=".length());
        Mockito.when(
                npgClient.buildFormForPayment(
                        eq(UUID.fromString(correlationId)),
                        eq(URI.create(sessionUrlConfig.basePath())),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        outcomeUrlPrefix,
                                        transactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )
                        ),
                        argThat(
                                new NpgNotificationUrlMatcher(
                                        npgNotificationUrlPrefix,
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
                        eq(totalAmount)
                )
        ).thenReturn(Mono.just(npgBuildSessionResponse));

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
                                userId
                        )
                )
                .expectError(NpgApiKeyMissingPspRequestedException.class)
                .verify();

        verify(npgClient, times(0))
                .buildFormForPayment(any(), any(), any(), any(), any(), eq(orderId), any(), any(), any(), any(), any());
        verify(npgClient, times(0))
                .buildForm(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(npgApiKeyHandler, times(1)).getApiKeyForPaymentMethod(NpgClient.PaymentMethod.PAYPAL, "pspId2");
    }

    @Test
    void shouldReturnBuildSessionResponseForWalletWithNpgForApmMethod() {
        String orderId = "orderIdGenerated";
        String sessionId = "sessionId";
        String correlationId = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();
        Transaction.ClientId clientId = it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.IO;
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
                                new CompanyName("companyName")
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                clientId,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
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
                        eq(totalAmount)
                )
        ).thenReturn(Mono.just(npgBuildSessionResponse));
        Mockito.when(npgApiKeyHandler.getApiKeyForPaymentMethod(any(), any())).thenReturn(Either.right("pspKey1"));

        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);
        /* test */
        StepVerifier
                .create(
                        client.requestNpgBuildApmPayment(
                                authorizationData,
                                correlationId,
                                false,
                                clientId.name(),
                                userId
                        )
                )
                .expectNext(responseRequestNpgBuildSession)
                .verifyComplete();

        String npgNotificationUrl = UriComponentsBuilder
                .fromHttpUrl(sessionUrlConfig.notificationUrl())
                .build(
                        Map.of(
                                "orderId",
                                orderId,
                                "sessionToken",
                                "sessionToken"
                        )
                ).toString();
        String npgOutcomeUrl = UriComponentsBuilder
                .fromHttpUrl(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
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
        String npgNotificationUrlPrefix = npgNotificationUrl
                .substring(0, npgNotificationUrl.indexOf("sessionToken=") + "sessionToken=".length());
        String outcomeUrlPrefix = npgOutcomeUrl
                .substring(0, npgOutcomeUrl.indexOf("sessionToken=") + "sessionToken=".length());
        verify(npgClient, times(1))
                .buildFormForPayment(
                        any(),
                        eq(URI.create(sessionUrlConfig.basePath())),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        outcomeUrlPrefix,
                                        transactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )
                        ),
                        argThat(
                                new NpgNotificationUrlMatcher(
                                        npgNotificationUrlPrefix,
                                        transactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )
                        ),
                        argThat(
                                new NpgOutcomeUrlMatcher(
                                        outcomeUrlPrefix,
                                        transactionId.value(),
                                        orderId,
                                        authorizationData.paymentInstrumentId()
                                )

                        ),
                        eq(orderId),
                        eq(null),
                        any(),
                        eq("pspKey1"),
                        eq(null),
                        eq(totalAmount)
                );
        verify(npgClient, times(0))
                .buildForm(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(npgApiKeyHandler, times(1)).getApiKeyForPaymentMethod(NpgClient.PaymentMethod.BANCOMATPAY, "pspId1");
    }

    private static Stream<Arguments> redirectRetrieveUrlPaymentMethodsTestMethodSource() {
        return Stream.of(
                Arguments.of(PaymentGatewayClient.RedirectPaymentMethodId.RBPR, "Poste addebito in conto Retail"),
                Arguments.of(PaymentGatewayClient.RedirectPaymentMethodId.RBPB, "Poste addebito in conto Business"),
                Arguments.of(PaymentGatewayClient.RedirectPaymentMethodId.RBPP, "Paga con BottonePostePay"),
                Arguments.of(PaymentGatewayClient.RedirectPaymentMethodId.RPIC, "Pago in Conto Intesa"),
                Arguments.of(PaymentGatewayClient.RedirectPaymentMethodId.RBPS, "SCRIGNO Internet Banking")
        );
    }

    @ParameterizedTest
    @MethodSource("redirectRetrieveUrlPaymentMethodsTestMethodSource")
    void shouldPerformAuthorizationRequestRetrievingRedirectionUrl(
                                                                   PaymentGatewayClient.RedirectPaymentMethodId paymentTypeCode,
                                                                   String mappedPaymentMethodDescription
    ) {
        String pspId = "pspId";
        it.pagopa.ecommerce.commons.domain.v2.TransactionActivated transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivated(ZonedDateTime.now().toString());
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                pspId,
                paymentTypeCode.toString(),
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );
        int totalAmount = authorizationData.paymentNotices().stream().map(PaymentNotice::transactionAmount)
                .mapToInt(TransactionAmount::value).sum() + authorizationData.fee();
        RedirectUrlRequestDto redirectUrlRequestDto = new RedirectUrlRequestDto()
                .idPaymentMethod(paymentTypeCode.toString())
                .amount(totalAmount)
                .idPsp(pspId)
                .idTransaction(transaction.getTransactionId().value())
                .description(transaction.getPaymentNotices().get(0).transactionDescription().value())
                .touchpoint(RedirectUrlRequestDto.TouchpointEnum.CHECKOUT)
                .paymentMethod(mappedPaymentMethodDescription)
                .paName(it.pagopa.ecommerce.commons.v2.TransactionTestUtils.COMPANY_NAME);

        String urlBack = UriComponentsBuilder
                .fromHttpUrl(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
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

        String urlBackPrefix = urlBack
                .substring(0, urlBack.indexOf("sessionToken=") + "sessionToken=".length());

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
                                    urlBackPrefix,
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
                                                                                                          PaymentGatewayClient.RedirectPaymentMethodId paymentTypeCode,
                                                                                                          String mappedPaymentMethodDescription
    ) {
        String pspId = "pspId";
        it.pagopa.ecommerce.commons.domain.v2.TransactionActivated transaction = new it.pagopa.ecommerce.commons.domain.v2.TransactionActivated(
                new TransactionId(TRANSACTION_ID),
                List.of(
                        new it.pagopa.ecommerce.commons.domain.PaymentNotice(
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
                                new CompanyName("companyName")
                        ),
                        new it.pagopa.ecommerce.commons.domain.PaymentNotice(
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
                                new CompanyName("companyName2")
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
                paymentTypeCode.toString(),
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );
        int totalAmount = authorizationData.paymentNotices().stream().map(PaymentNotice::transactionAmount)
                .mapToInt(TransactionAmount::value).sum() + authorizationData.fee();
        RedirectUrlRequestDto redirectUrlRequestDto = new RedirectUrlRequestDto()
                .idPaymentMethod(paymentTypeCode.toString())
                .amount(totalAmount)
                .idPsp(pspId)
                .idTransaction(transaction.getTransactionId().value())
                .description(transaction.getPaymentNotices().get(0).transactionDescription().value())
                .touchpoint(RedirectUrlRequestDto.TouchpointEnum.CHECKOUT)
                .paymentMethod(mappedPaymentMethodDescription)
                .paName(null);

        String urlBack = UriComponentsBuilder
                .fromHttpUrl(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
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

        String urlBackPrefix = urlBack
                .substring(0, urlBack.indexOf("sessionToken=") + "sessionToken=".length());

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
                                    urlBackPrefix,
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
        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );
        PaymentGatewayClient.RedirectPaymentMethodId idPaymentMethod = PaymentGatewayClient.RedirectPaymentMethodId.RBPS;
        int totalAmount = authorizationData.paymentNotices().stream().map(PaymentNotice::transactionAmount)
                .mapToInt(TransactionAmount::value).sum() + authorizationData.fee();
        RedirectUrlRequestDto redirectUrlRequestDto = new RedirectUrlRequestDto()
                .idPaymentMethod(idPaymentMethod.toString())
                .paymentMethod(PaymentGatewayClient.redirectMethodsDescriptions.get(idPaymentMethod))
                .amount(totalAmount)
                .idPsp(pspId)
                .idTransaction(transaction.getTransactionId().value())
                .description(transaction.getPaymentNotices().get(0).transactionDescription().value())
                .touchpoint(RedirectUrlRequestDto.TouchpointEnum.CHECKOUT);

        String urlBack = UriComponentsBuilder
                .fromHttpUrl(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
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

        String urlBackPrefix = urlBack
                .substring(0, urlBack.indexOf("sessionToken=") + "sessionToken=".length());

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
                                    urlBackPrefix,
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
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );
        PaymentGatewayClient.RedirectPaymentMethodId idPaymentMethod = PaymentGatewayClient.RedirectPaymentMethodId.RBPS;
        int totalAmount = authorizationData.paymentNotices().stream().map(PaymentNotice::transactionAmount)
                .mapToInt(TransactionAmount::value).sum() + authorizationData.fee();
        RedirectUrlRequestDto redirectUrlRequestDto = new RedirectUrlRequestDto()
                .paymentMethod(PaymentGatewayClient.redirectMethodsDescriptions.get(idPaymentMethod))
                .idPaymentMethod(idPaymentMethod.toString())
                .amount(totalAmount)
                .idPsp(pspId)
                .idTransaction(transaction.getTransactionId().value())
                .description(transaction.getPaymentNotices().get(0).transactionDescription().value())
                .touchpoint(RedirectUrlRequestDto.TouchpointEnum.CHECKOUT);

        String urlBack = UriComponentsBuilder
                .fromHttpUrl(sessionUrlConfig.basePath().concat(sessionUrlConfig.outcomeSuffix()))
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

        String urlBackPrefix = urlBack
                .substring(0, urlBack.indexOf("sessionToken=") + "sessionToken=".length());

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
                                    urlBackPrefix,
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        Hooks.onOperatorDebug();
        Map<String, String> redirectUrlMapping = new HashMap<>(redirectBeApiCallUriMap);
        Set<String> codeListTypeMapping = new HashSet<>(codeTypeList);
        redirectUrlMapping.remove("pspId-RBPS");
        codeListTypeMapping.remove("pspId-RBPS");
        PaymentGatewayClient redirectClient = new PaymentGatewayClient(
                objectMapper,
                mockUuidUtils,
                confidentialMailUtils,
                npgClient,
                sessionUrlConfig,
                uniqueIdUtils,
                jwtSecretKey,
                TOKEN_VALIDITY_TIME_SECONDS,
                jwtSecretKey,
                TOKEN_VALIDITY_TIME_SECONDS,
                nodeForwarderClient,
                new RedirectKeysConfiguration(redirectUrlMapping, codeListTypeMapping),
                npgApiKeyHandler,
                npgAuthorizationRetryExcludedErrorCodes
        );
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

    @Test
    void shouldReturnErrorDuringRedirectPaymentTransactionForUnmanagedPaymentTypeCode() {
        String pspId = "pspId";
        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                pspId,
                "CC",
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        Hooks.onOperatorDebug();
        /* test */
        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> client.requestRedirectUrlAuthorization(
                        authorizationData,
                        RedirectUrlRequestDto.TouchpointEnum.CHECKOUT,
                        UUID.fromString(USER_ID)
                )
        );
        verify(nodeForwarderClient, times(0)).proxyRequest(any(), any(), any(), any());
        assertEquals("Unmanaged payment method with type code: [CC]", exception.getMessage());
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
                                new CompanyName("companyName")
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
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
                Optional.of(Map.of("VISA", "http://visaAsset"))
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
                        PaymentGatewayClient.RedirectPaymentMethodId.RBPR,
                        new URI("http://localhost:8096/redirections1/CHECKOUT")
                ),
                Arguments.of(
                        RedirectUrlRequestDto.TouchpointEnum.IO,

                        "psp1",
                        PaymentGatewayClient.RedirectPaymentMethodId.RBPR,
                        new URI("http://localhost:8096/redirections1/IO")
                ),
                Arguments.of(
                        RedirectUrlRequestDto.TouchpointEnum.CHECKOUT,
                        "psp2",
                        PaymentGatewayClient.RedirectPaymentMethodId.RBPB,
                        new URI("http://localhost:8096/redirections2")
                ),
                Arguments.of(
                        RedirectUrlRequestDto.TouchpointEnum.IO,
                        "psp2",
                        PaymentGatewayClient.RedirectPaymentMethodId.RBPB,
                        new URI("http://localhost:8096/redirections2")
                ),
                Arguments.of(
                        RedirectUrlRequestDto.TouchpointEnum.CHECKOUT,
                        "psp3",
                        PaymentGatewayClient.RedirectPaymentMethodId.RBPS,
                        new URI("http://localhost:8096/redirections3")
                ),
                Arguments.of(
                        RedirectUrlRequestDto.TouchpointEnum.IO,
                        "psp3",
                        PaymentGatewayClient.RedirectPaymentMethodId.RBPS,
                        new URI("http://localhost:8096/redirections3")
                )
        );
    }

    @ParameterizedTest
    @MethodSource("redirectRetrieveUrlPaymentMethodsTestSearch")
    void shouldReturnURIDuringSearchRedirectURLSearchingIteratively(
                                                                    RedirectUrlRequestDto.TouchpointEnum touchpoint,
                                                                    String pspId,
                                                                    PaymentGatewayClient.RedirectPaymentMethodId paymentMethodId,
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
                jwtSecretKey,
                TOKEN_VALIDITY_TIME_SECONDS,
                jwtSecretKey,
                TOKEN_VALIDITY_TIME_SECONDS,
                nodeForwarderClient,
                new RedirectKeysConfiguration(redirectUrlMapping, redirectCodeTypeList),
                npgApiKeyHandler,
                npgAuthorizationRetryExcludedErrorCodes
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
                paymentMethodId.name(),
                "brokerName",
                "pspChannelCode",
                paymentMethodId.toString(),
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "REDIRECT",
                Optional.empty(),
                Optional.empty(),
                "N/A",
                new RedirectionAuthRequestDetailsDto(),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

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
        PaymentGatewayClient.RedirectPaymentMethodId redirectPaymentMethodId = PaymentGatewayClient.RedirectPaymentMethodId.RBPP;
        PaymentGatewayClient redirectClient = new PaymentGatewayClient(
                objectMapper,
                mockUuidUtils,
                confidentialMailUtils,
                npgClient,
                sessionUrlConfig,
                uniqueIdUtils,
                jwtSecretKey,
                TOKEN_VALIDITY_TIME_SECONDS,
                jwtSecretKey,
                TOKEN_VALIDITY_TIME_SECONDS,
                nodeForwarderClient,
                new RedirectKeysConfiguration(redirectUrlMapping, redirectCodeTypeList),
                npgApiKeyHandler,
                npgAuthorizationRetryExcludedErrorCodes
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
                redirectPaymentMethodId.toString(),
                "brokerName",
                "pspChannelCode",
                redirectPaymentMethodId.toString(),
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "REDIRECT",
                Optional.empty(),
                Optional.empty(),
                "N/A",
                new RedirectionAuthRequestDetailsDto(),
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset"))
        );

        Hooks.onOperatorDebug();
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

}
