package it.pagopa.transactions.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.pagopa.ecommerce.commons.client.NodeForwarderClient;
import it.pagopa.ecommerce.commons.client.NpgClient;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.exceptions.CheckoutRedirectConfigurationException;
import it.pagopa.ecommerce.commons.exceptions.NodeForwarderClientException;
import it.pagopa.ecommerce.commons.exceptions.NpgApiKeyMissingPspRequestedException;
import it.pagopa.ecommerce.commons.exceptions.NpgResponseException;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldsDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.StateResponseDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.WorkflowStateDto;
import it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig;
import it.pagopa.ecommerce.commons.utils.UniqueIdUtils;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.ecommerce.gateway.v1.api.VposInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.api.XPayInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.dto.*;
import it.pagopa.generated.ecommerce.gateway.v1.dto.VposAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.VposAuthResponseDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.XPayAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.XPayAuthResponseEntityDto;
import it.pagopa.generated.ecommerce.redirect.v1.api.B2bPspSideApi;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlRequestDto;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlResponseDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.configurations.NpgSessionUrlConfig;
import it.pagopa.transactions.configurations.SecretsConfigurations;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import it.pagopa.transactions.utils.NpgNotificationUrlMatcher;
import it.pagopa.transactions.utils.UUIDUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Stream;

import static it.pagopa.ecommerce.commons.v1.TransactionTestUtils.EMAIL;
import static it.pagopa.ecommerce.commons.v1.TransactionTestUtils.EMAIL_STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
class PaymentGatewayClientTest {

    private PaymentGatewayClient client;

    @Mock
    VposInternalApi creditCardInternalApi;

    @Mock
    XPayInternalApi xPayInternalApi;

    @Mock
    UUIDUtils mockUuidUtils;

    @Mock
    ConfidentialMailUtils confidentialMailUtils;

    @Mock
    UniqueIdUtils uniqueIdUtils;

    private final String npgDefaultApiKey = UUID.randomUUID().toString();

    private final NpgSessionUrlConfig sessionUrlConfig = new NpgSessionUrlConfig(
            "http://localhost:1234",
            "/ecommerce-fe/esito",
            "/ecommerce-fe/annulla",
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

    private final Map<String, URI> checkoutRedirectBeApiCallUriMap = Map
            .of("pspId", URI.create("http://redirect/pspId"), "malformedUrlPspId", URI.create("malformedUrl"));

    @BeforeEach
    private void init() {
        client = new PaymentGatewayClient(
                xPayInternalApi,
                creditCardInternalApi,
                objectMapper,
                mockUuidUtils,
                confidentialMailUtils,
                npgClient,
                npgPspApiKeysConfig,
                sessionUrlConfig,
                uniqueIdUtils,
                npgDefaultApiKey,
                jwtSecretKey,
                TOKEN_VALIDITY_TIME_SECONDS,
                nodeForwarderClient,
                checkoutRedirectBeApiCallUriMap
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
                                false
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
                Mockito.mock(RequestAuthorizationRequestDetailsDto.class)
        );

        /* test */

        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        StepVerifier.create(client.requestCreditCardAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        verifyNoInteractions(xPayInternalApi, creditCardInternalApi);
    }

    @Test
    void shouldReturnAuthorizationResponseForCreditCardWithXPay() throws JsonProcessingException {

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
                                false
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto()
                .cvv("345")
                .pan("16589654852")
                .expiryDate("203012")
                .detailType("card")
                .holderName("John Doe")
                .brand(CardAuthRequestDetailsDto.BrandEnum.VISA)
                .threeDsData("threeDsData");

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId",
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
                cardDetails
        );

        XPayAuthRequestDto xPayAuthRequestDto = new XPayAuthRequestDto()
                .cvv(cardDetails.getCvv())
                .pan(cardDetails.getPan())
                .expiryDate(cardDetails.getExpiryDate())
                .idTransaction(transactionId.value())
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                );

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionId.value()));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        XPayAuthResponseEntityDto xPayResponse = new XPayAuthResponseEntityDto()
                .requestId("requestId")
                .urlRedirect("https://example.com");
        /* preconditions */
        Mockito.when(xPayInternalApi.authXpay(xPayAuthRequestDto, encodedMdcFields))
                .thenReturn(Mono.just(xPayResponse));

        Mockito.when(mockUuidUtils.uuidToBase64(transactionId.uuid()))
                .thenReturn(xPayAuthRequestDto.getIdTransaction());

        /* test */
        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNext(xPayResponse)
                .verifyComplete();

        StepVerifier.create(client.requestCreditCardAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        verify(xPayInternalApi, times(1)).authXpay(any(), any());
        verify(creditCardInternalApi, times(0)).step0VposAuth(any(), any());
    }

    @Test
    void shouldReturnAuthorizationResponseForCreditCardWithVPOS() throws Exception {
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
                                false
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto()
                .cvv("345")
                .pan("16589654852")
                .expiryDate("203012")
                .detailType("card")
                .holderName("John Doe")
                .brand(CardAuthRequestDetailsDto.BrandEnum.VISA)
                .threeDsData("threeDsData");
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId",
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
                cardDetails
        );

        VposAuthRequestDto vposAuthRequestDto = new VposAuthRequestDto()
                .securityCode(cardDetails.getCvv())
                .pan(cardDetails.getPan())
                .expireDate(cardDetails.getExpiryDate())
                .idTransaction(transactionId.value())
                .amount(
                        BigDecimal.valueOf(
                                transaction.getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                )
                .emailCH(EMAIL_STRING)
                .circuit(VposAuthRequestDto.CircuitEnum.fromValue(cardDetails.getBrand().toString()))
                .holder(cardDetails.getHolderName())
                .isFirstPayment(true)
                .threeDsData("threeDsData")
                .idPsp(authorizationData.pspId());

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionId.value()));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        VposAuthResponseDto vposAuthResponseDto = new VposAuthResponseDto()
                .requestId("requestId")
                .urlRedirect("https://example.com");

        /* preconditions */
        Mockito.when(creditCardInternalApi.step0VposAuth(vposAuthRequestDto, encodedMdcFields))
                .thenReturn(Mono.just(vposAuthResponseDto));

        Mockito.when(mockUuidUtils.uuidToBase64(any()))
                .thenReturn(vposAuthRequestDto.getIdTransaction());

        Mockito.when(confidentialMailUtils.toEmail(EMAIL)).thenReturn(Mono.just(new Email(EMAIL_STRING)));

        /* test */
        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        StepVerifier.create(client.requestCreditCardAuthorization(authorizationData))
                .expectNext(vposAuthResponseDto)
                .verifyComplete();

        verify(xPayInternalApi, times(0)).authXpay(any(), any());
        verify(creditCardInternalApi, times(1)).step0VposAuth(any(), any());
    }

    @Test
    void shouldReturnAuthorizationResponseForCardsWithNpg() throws Exception {
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
                                false
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
                cardDetails
        );
        StateResponseDto ngpStateResponse = new StateResponseDto().url("https://example.com");
        /* preconditions */
        Mockito.when(npgClient.confirmPayment(any(), any(), any(), any())).thenReturn(Mono.just(ngpStateResponse));

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
    }

    @Test
    void shouldThrowAlreadyProcessedOn401ForCardsWithNpg() throws Exception {
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
                                false
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
                cardDetails
        );

        /* preconditions */
        Mockito.when(npgClient.confirmPayment(any(), any(), any(), any()))
                .thenReturn(
                        Mono.error(
                                new NpgResponseException(
                                        "NPG error",
                                        List.of(),
                                        Optional.of(HttpStatus.UNAUTHORIZED),
                                        new WebClientResponseException(
                                                "api error",
                                                HttpStatus.UNAUTHORIZED.value(),
                                                "Unauthorized",
                                                null,
                                                null,
                                                null
                                        )
                                )
                        )
                );
        /* test */

        StepVerifier.create(client.requestNpgCardsAuthorization(authorizationData, UUID.randomUUID().toString()))
                .expectErrorMatches(
                        error -> error instanceof AlreadyProcessedException &&
                                ((AlreadyProcessedException) error).getTransactionId()
                                        .equals(transaction.getTransactionId())
                )
                .verify();
    }

    @Test
    void shouldThrowGatewayTimeoutExceptionForCardsWithNpg() throws Exception {
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
                                false
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
                cardDetails
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
        /* test */
        StepVerifier.create(client.requestNpgCardsAuthorization(authorizationData, UUID.randomUUID().toString()))
                .expectErrorMatches(
                        error -> error instanceof BadGatewayException
                )
                .verify();
    }

    @Test
    void shouldThrowInternalServerErrorExceptionForCardsWithNpg() throws Exception {
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
                                false
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
                cardDetails
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
        /* test */
        StepVerifier.create(client.requestNpgCardsAuthorization(authorizationData, UUID.randomUUID().toString()))
                .expectErrorMatches(
                        error -> error instanceof BadGatewayException
                )
                .verify();
    }

    @Test
    void shouldThrowAlreadyProcessedOn401ForCreditCardWithXpay() throws JsonProcessingException {
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
                                false
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto()
                .detailType("card")
                .cvv("345")
                .pan("16589654852")
                .expiryDate("203012")
                .brand(CardAuthRequestDetailsDto.BrandEnum.VISA)
                .threeDsData("threeDsData");
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId",
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
                cardDetails
        );

        XPayAuthRequestDto xPayAuthRequestDto = new XPayAuthRequestDto()
                .cvv(cardDetails.getCvv())
                .pan(cardDetails.getPan())
                .expiryDate(cardDetails.getExpiryDate())
                .idTransaction(transactionId.value())
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                );

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionId.value()));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        /* preconditions */
        Mockito.when(xPayInternalApi.authXpay(xPayAuthRequestDto, encodedMdcFields))
                .thenReturn(
                        Mono.error(
                                new WebClientResponseException(
                                        "api error",
                                        HttpStatus.UNAUTHORIZED.value(),
                                        "Unauthorized",
                                        null,
                                        null,
                                        null
                                )
                        )
                );

        Mockito.when(mockUuidUtils.uuidToBase64(any()))
                .thenReturn(xPayAuthRequestDto.getIdTransaction());

        /* test */

        StepVerifier.create(client.requestCreditCardAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectErrorMatches(
                        error -> error instanceof AlreadyProcessedException &&
                                ((AlreadyProcessedException) error).getTransactionId()
                                        .equals(transaction.getTransactionId())
                )
                .verify();

        verify(xPayInternalApi, times(1)).authXpay(any(), any());
        verify(creditCardInternalApi, times(0)).step0VposAuth(any(), any());
    }

    @Test
    void shouldThrowBadGatewayOn500ForCreditCardWithXPay() throws JsonProcessingException {
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
                                false
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto()
                .cvv("345")
                .pan("16589654852")
                .expiryDate("203012")
                .brand(CardAuthRequestDetailsDto.BrandEnum.VISA)
                .threeDsData("threeDsData");
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId",
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
                cardDetails
        );

        XPayAuthRequestDto xPayAuthRequestDto = new XPayAuthRequestDto()
                .cvv(cardDetails.getCvv())
                .pan(cardDetails.getPan())
                .expiryDate(cardDetails.getExpiryDate())
                .idTransaction(transactionId.value())
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                );

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionId.value()));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        /* preconditions */
        Mockito.when(xPayInternalApi.authXpay(xPayAuthRequestDto, encodedMdcFields))
                .thenReturn(
                        Mono.error(
                                new WebClientResponseException(
                                        "api error",
                                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                        "Internal server error",
                                        null,
                                        null,
                                        null
                                )
                        )
                );

        Mockito.when(mockUuidUtils.uuidToBase64(any()))
                .thenReturn(xPayAuthRequestDto.getIdTransaction());
        /* test */
        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectErrorMatches(error -> error instanceof BadGatewayException)
                .verify();

        StepVerifier.create(client.requestCreditCardAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        verify(xPayInternalApi, times(1)).authXpay(any(), any());
        verify(creditCardInternalApi, times(0)).step0VposAuth(any(), any());
    }

    @Test
    void shouldThrowBadGatewayOn500ForCreditCardWithVPOS() throws Exception {
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
                                false
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto()
                .cvv("345")
                .pan("16589654852")
                .expiryDate("203012")
                .brand(CardAuthRequestDetailsDto.BrandEnum.VISA)
                .threeDsData("threeDsData");
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId",
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
                cardDetails
        );

        VposAuthRequestDto vposAuthRequestDto = new VposAuthRequestDto()
                .securityCode(cardDetails.getCvv())
                .pan(cardDetails.getPan())
                .expireDate(cardDetails.getExpiryDate())
                .idTransaction(transactionId.value())
                .amount(
                        BigDecimal.valueOf(
                                transaction.getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                )
                .emailCH(EMAIL_STRING)
                .circuit(VposAuthRequestDto.CircuitEnum.fromValue(cardDetails.getBrand().toString()))
                .holder(cardDetails.getHolderName())
                .isFirstPayment(true)
                .threeDsData("threeDsData")
                .idPsp(authorizationData.pspId());

        String mdcInfo = objectMapper.writeValueAsString(Map.of("transactionId", transactionId.value()));
        String encodedMdcFields = Base64.getEncoder().encodeToString(mdcInfo.getBytes(StandardCharsets.UTF_8));

        /* preconditions */
        Mockito.when(creditCardInternalApi.step0VposAuth(vposAuthRequestDto, encodedMdcFields))
                .thenReturn(
                        Mono.error(
                                new WebClientResponseException(
                                        "api error",
                                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                        "Internal server error",
                                        null,
                                        null,
                                        null
                                )
                        )
                );
        Mockito.when(mockUuidUtils.uuidToBase64(any()))
                .thenReturn(vposAuthRequestDto.getIdTransaction());

        Mockito.when(confidentialMailUtils.toEmail(EMAIL)).thenReturn(Mono.just(new Email(EMAIL_STRING)));

        /* test */
        StepVerifier.create(client.requestCreditCardAuthorization(authorizationData))
                .expectErrorMatches(error -> error instanceof BadGatewayException)
                .verify();

        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        verify(xPayInternalApi, times(0)).authXpay(any(), any());
        verify(creditCardInternalApi, times(1)).step0VposAuth(any(), any());
    }

    @Test
    void fallbackOnEmptyMdcInfoOnMapperErrorForCreditCardWithXPay() throws JsonProcessingException {
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
                                false
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto()
                .cvv("345")
                .pan("16589654852")
                .expiryDate("203012")
                .brand(CardAuthRequestDetailsDto.BrandEnum.VISA)
                .threeDsData("threeDsData");
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId",
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
                cardDetails
        );

        XPayAuthRequestDto xPayAuthRequestDto = new XPayAuthRequestDto()
                .cvv(cardDetails.getCvv())
                .pan(cardDetails.getPan())
                .expiryDate(cardDetails.getExpiryDate())
                .idTransaction(transactionId.value())
                .grandTotal(
                        BigDecimal.valueOf(
                                transaction.getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                );

        String encodedMdcFields = "";

        XPayAuthResponseEntityDto xPayResponse = new XPayAuthResponseEntityDto()
                .requestId("requestId")
                .urlRedirect("https://example.com");

        /* preconditions */
        Mockito.when(objectMapper.writeValueAsString(Map.of("transactionId", transactionId.value())))
                .thenThrow(new JsonProcessingException("") {
                });
        Mockito.when(xPayInternalApi.authXpay(xPayAuthRequestDto, encodedMdcFields))
                .thenReturn(Mono.just(xPayResponse));
        Mockito.when(mockUuidUtils.uuidToBase64(any()))
                .thenReturn(xPayAuthRequestDto.getIdTransaction());
        /* test */
        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNext(xPayResponse)
                .verifyComplete();

        StepVerifier.create(client.requestCreditCardAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        verify(xPayInternalApi, times(1)).authXpay(any(), any());
        verify(creditCardInternalApi, times(0)).step0VposAuth(any(), any());
    }

    @Test
    void fallbackOnEmptyMdcInfoOnMapperErrorForCreditCardWithVPOS() throws Exception {
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
                                false
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
        );
        CardAuthRequestDetailsDto cardDetails = new CardAuthRequestDetailsDto()
                .cvv("345")
                .pan("16589654852")
                .expiryDate("203012")
                .brand(CardAuthRequestDetailsDto.BrandEnum.VISA)
                .threeDsData("threeDsData");
        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                transaction.getTransactionId(),
                transaction.getPaymentNotices(),
                transaction.getEmail(),
                10,
                "paymentInstrumentId",
                "pspId",
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
                cardDetails
        );

        VposAuthRequestDto vposAuthRequestDto = new VposAuthRequestDto()
                .securityCode(cardDetails.getCvv())
                .pan(cardDetails.getPan())
                .expireDate(cardDetails.getExpiryDate())
                .idTransaction(transactionId.value())
                .amount(
                        BigDecimal.valueOf(
                                transaction.getPaymentNotices().stream()
                                        .mapToInt(PaymentNotice -> PaymentNotice.transactionAmount().value()).sum()
                                        + authorizationData.fee()
                        )
                )
                .emailCH(EMAIL_STRING)
                .circuit(VposAuthRequestDto.CircuitEnum.fromValue(cardDetails.getBrand().toString()))
                .holder(cardDetails.getHolderName())
                .isFirstPayment(true)
                .threeDsData("threeDsData")
                .idPsp(authorizationData.pspId());

        String encodedMdcFields = "";

        VposAuthResponseDto creditCardAuthResponseDto = new VposAuthResponseDto()
                .requestId("requestId")
                .urlRedirect("https://example.com");

        /* preconditions */
        Mockito.when(objectMapper.writeValueAsString(Map.of("transactionId", transactionId.value())))
                .thenThrow(new JsonProcessingException("") {
                });
        Mockito.when(creditCardInternalApi.step0VposAuth(vposAuthRequestDto, encodedMdcFields))
                .thenReturn(Mono.just(creditCardAuthResponseDto));
        Mockito.when(mockUuidUtils.uuidToBase64(any()))
                .thenReturn(vposAuthRequestDto.getIdTransaction());
        Mockito.when(confidentialMailUtils.toEmail(EMAIL)).thenReturn(Mono.just(new Email(EMAIL_STRING)));

        /* test */
        StepVerifier.create(client.requestCreditCardAuthorization(authorizationData))
                .expectNext(creditCardAuthResponseDto)
                .verifyComplete();

        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        verify(xPayInternalApi, times(0)).authXpay(any(), any());
        verify(creditCardInternalApi, times(1)).step0VposAuth(any(), any());
    }

    @Test
    void shouldThrowInvalidRequestWhenCardDetailsAreMissingForCreditCardWithXPay() {
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
                                false
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
                null
        );

        /* test */
        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectError(InvalidRequestException.class)
                .verify();

        StepVerifier.create(client.requestCreditCardAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        verify(xPayInternalApi, times(0)).authXpay(any(), any());
        verify(creditCardInternalApi, times(0)).step0VposAuth(any(), any());
    }

    @Test
    void shouldThrowInvalidRequestWhenCardDetailsAreMissingForCreditCardWithVPOS() {
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
                                false
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
                null
        );

        /* preconditions */
        Mockito.when(confidentialMailUtils.toEmail(EMAIL)).thenReturn(Mono.just(new Email(EMAIL_STRING)));

        /* test */
        StepVerifier.create(client.requestCreditCardAuthorization(authorizationData))
                .expectError(InvalidRequestException.class)
                .verify();

        StepVerifier.create(client.requestXPayAuthorization(authorizationData))
                .expectNextCount(0)
                .verifyComplete();

        verify(xPayInternalApi, times(0)).authXpay(any(), any());
        verify(creditCardInternalApi, times(0)).step0VposAuth(any(), any());
    }

    @Test
    void shouldReturnBuildSessionResponseForWalletWithNpgWithCards() {
        String walletId = UUID.randomUUID().toString();
        String orderId = "orderIdGenerated";
        String sessionId = "sessionId";
        String contractId = "contractId";
        String correlationId = UUID.randomUUID().toString();
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
                                false
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
                walletDetails
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

        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);
        /* test */
        StepVerifier.create(client.requestNpgBuildSession(authorizationData, correlationId, true))
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
        String npgNotificationUrlPrefix = npgNotificationUrl
                .substring(0, npgNotificationUrl.indexOf("sessionToken=") + "sessionToken=".length());

        verify(npgClient, times(1))
                .buildForm(
                        any(),
                        eq(URI.create(sessionUrlConfig.basePath())),
                        eq(
                                URI
                                        .create(sessionUrlConfig.basePath())
                                        .resolve(
                                                URI.create(
                                                        sessionUrlConfig.outcomeSuffix()
                                                                + "#clientId=IO&transactionId=%s"
                                                                        .formatted(transactionId.value())
                                                )
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
                                URI.create(sessionUrlConfig.basePath())
                                        .resolve(URI.create(sessionUrlConfig.cancelSuffix()))
                        ),
                        eq(orderId),
                        eq(null),
                        any(),
                        any(),
                        eq(contractId)
                );
        verify(npgClient, times(0))
                .buildFormForPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldThrowAlreadyProcessedOn401ForWalletWithNpg() {
        String walletId = UUID.randomUUID().toString();
        String orderId = "orderIdGenerated";
        String contractId = "contractId";
        String correlationId = UUID.randomUUID().toString();
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
                                false
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
                walletDetails
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
                                        Optional.of(HttpStatus.UNAUTHORIZED),
                                        new WebClientResponseException(
                                                "api error",
                                                HttpStatus.UNAUTHORIZED.value(),
                                                "Unauthorized",
                                                null,
                                                null,
                                                null
                                        )
                                )
                        )
                );
        /* test */

        StepVerifier.create(client.requestNpgBuildSession(authorizationData, correlationId, true))
                .expectErrorMatches(
                        error -> error instanceof AlreadyProcessedException &&
                                ((AlreadyProcessedException) error).getTransactionId()
                                        .equals(transaction.getTransactionId())
                )
                .verify();
    }

    @Test
    void shouldThrowGatewayTimeoutExceptionForWalletWithNpg() {
        String walletId = UUID.randomUUID().toString();
        String orderId = "orderIdGenerated";
        String contractId = "contractId";
        String correlationId = UUID.randomUUID().toString();
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
                                false
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
                walletDetails
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
        StepVerifier.create(client.requestNpgBuildSession(authorizationData, correlationId, true))
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
                                false
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
                walletDetails
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
        StepVerifier.create(client.requestNpgBuildSession(authorizationData, correlationId, true))
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
                                false
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
                walletDetails
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

        StepVerifier.create(client.requestNpgBuildSession(authorizationData, correlationId, true))
                .expectErrorMatches(error -> error instanceof BadGatewayException)
                .verify();
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
                        eq(
                                URI
                                        .create(sessionUrlConfig.basePath())
                                        .resolve(
                                                URI.create(
                                                        sessionUrlConfig.outcomeSuffix()
                                                                + "#clientId=IO&transactionId=%s"
                                                                        .formatted(transactionId.value())
                                                )
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
                                URI.create(sessionUrlConfig.basePath())
                                        .resolve(URI.create(sessionUrlConfig.cancelSuffix()))
                        ),
                        eq(orderId),
                        eq(null),
                        any(),
                        any(),
                        eq(contractId)
                );
        verify(npgClient, times(0))
                .buildFormForPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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
                                false
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
                walletDetails
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

        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);
        /* test */
        StepVerifier.create(client.requestNpgBuildApmPayment(authorizationData, correlationId, true))
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
        String npgNotificationUrlPrefix = npgNotificationUrl
                .substring(0, npgNotificationUrl.indexOf("sessionToken=") + "sessionToken=".length());
        verify(npgClient, times(1))
                .buildFormForPayment(
                        any(),
                        eq(URI.create(sessionUrlConfig.basePath())),
                        eq(
                                URI
                                        .create(sessionUrlConfig.basePath())
                                        .resolve(
                                                URI.create(
                                                        sessionUrlConfig.outcomeSuffix()
                                                                + "#clientId=IO&transactionId=%s"
                                                                        .formatted(transactionId.value())
                                                )
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
                                URI.create(sessionUrlConfig.basePath())
                                        .resolve(URI.create(sessionUrlConfig.cancelSuffix()))
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
    }

    @Test
    void shouldThrowErrorForWalletWithNpgForGenericApmMethodAndMissingKey() {
        String walletId = UUID.randomUUID().toString();
        String orderId = "orderIdGenerated";
        String sessionId = "sessionId";
        String contractId = "contractId";
        String correlationId = UUID.randomUUID().toString();
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
                                false
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
                walletDetails
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
                        eq(
                                URI
                                        .create(sessionUrlConfig.basePath())
                                        .resolve(
                                                URI.create(
                                                        sessionUrlConfig.outcomeSuffix()
                                                                + "#clientId=IO&transactionId=%s"
                                                                        .formatted(transactionId.value())
                                                )
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
                                URI.create(sessionUrlConfig.basePath())
                                        .resolve(URI.create(sessionUrlConfig.cancelSuffix()))
                        ),
                        eq(orderId),
                        eq(null),
                        eq(NpgClient.PaymentMethod.PAYPAL),
                        eq("pspKey1"),
                        eq(contractId),
                        eq(totalAmount)
                )
        ).thenReturn(Mono.just(npgBuildSessionResponse));

        /* test */
        StepVerifier.create(client.requestNpgBuildApmPayment(authorizationData, correlationId, true))
                .expectError(NpgApiKeyMissingPspRequestedException.class)
                .verify();

        verify(npgClient, times(0))
                .buildFormForPayment(any(), any(), any(), any(), any(), eq(orderId), any(), any(), any(), any(), any());
        verify(npgClient, times(0))
                .buildForm(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldReturnBuildSessionResponseForWalletWithNpgForApmMethod() {
        String walletId = UUID.randomUUID().toString();
        String orderId = "orderIdGenerated";
        String sessionId = "sessionId";
        String correlationId = UUID.randomUUID().toString();
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
                                false
                        )
                ),
                TransactionTestUtils.EMAIL,
                null,
                null,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
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
                apmDetails
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

        Tuple2<String, FieldsDto> responseRequestNpgBuildSession = Tuples.of(orderId, npgBuildSessionResponse);
        /* test */
        StepVerifier.create(client.requestNpgBuildApmPayment(authorizationData, correlationId, false))
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
        String npgNotificationUrlPrefix = npgNotificationUrl
                .substring(0, npgNotificationUrl.indexOf("sessionToken=") + "sessionToken=".length());
        verify(npgClient, times(1))
                .buildFormForPayment(
                        any(),
                        eq(URI.create(sessionUrlConfig.basePath())),
                        eq(
                                URI
                                        .create(sessionUrlConfig.basePath())
                                        .resolve(
                                                URI.create(
                                                        sessionUrlConfig.outcomeSuffix()
                                                                + "#clientId=IO&transactionId=%s"
                                                                        .formatted(transactionId.value())
                                                )
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
                                URI.create(sessionUrlConfig.basePath())
                                        .resolve(URI.create(sessionUrlConfig.cancelSuffix()))
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
        TransactionActivated transaction = TransactionTestUtils.transactionActivated(ZonedDateTime.now().toString());
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
                new RedirectionAuthRequestDetailsDto()
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
                .urlBack(
                        URI.create(
                                "http://localhost:1234/ecommerce-fe/esito#clientId=REDIRECT&transactionId="
                                        .concat(transaction.getTransactionId().value())
                        )
                )
                .paymentMethod(mappedPaymentMethodDescription);
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
                client.requestRedirectUrlAuthorization(authorizationData, RedirectUrlRequestDto.TouchpointEnum.CHECKOUT)
        )
                .expectNext(redirectUrlResponseDto)
                .verifyComplete();
        verify(nodeForwarderClient, times(1)).proxyRequest(
                redirectUrlRequestDto,
                URI.create("http://redirect/pspId"),
                authorizationData.transactionId().value(),
                RedirectUrlResponseDto.class
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
                new RedirectionAuthRequestDetailsDto()
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
                .touchpoint(RedirectUrlRequestDto.TouchpointEnum.CHECKOUT)
                .urlBack(
                        URI.create(
                                "http://localhost:1234/ecommerce-fe/esito#clientId=REDIRECT&transactionId="
                                        .concat(transaction.getTransactionId().value())
                        )
                );

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
                client.requestRedirectUrlAuthorization(authorizationData, RedirectUrlRequestDto.TouchpointEnum.CHECKOUT)
        )
                .expectError(expectedMappedException)
                .verify();
        verify(nodeForwarderClient, times(1)).proxyRequest(
                redirectUrlRequestDto,
                URI.create("http://redirect/pspId"),
                authorizationData.transactionId().value(),
                RedirectUrlResponseDto.class
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
                new RedirectionAuthRequestDetailsDto()
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
                .touchpoint(RedirectUrlRequestDto.TouchpointEnum.CHECKOUT)
                .urlBack(
                        URI.create(
                                "http://localhost:1234/ecommerce-fe/esito#clientId=REDIRECT&transactionId="
                                        .concat(transaction.getTransactionId().value())
                        )
                );
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
                client.requestRedirectUrlAuthorization(authorizationData, RedirectUrlRequestDto.TouchpointEnum.CHECKOUT)
        )
                .expectError(BadGatewayException.class)
                .verify();
        verify(nodeForwarderClient, times(1)).proxyRequest(
                redirectUrlRequestDto,
                URI.create("http://redirect/pspId"),
                authorizationData.transactionId().value(),
                RedirectUrlResponseDto.class
        );
    }

    @Test
    void shouldReturnErrorDuringRedirectPaymentTransactionForInvalidPspURL() {
        String pspId = "unknownPspId";
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
                new RedirectionAuthRequestDetailsDto()
        );

        Hooks.onOperatorDebug();
        /* test */
        StepVerifier.create(
                client.requestRedirectUrlAuthorization(authorizationData, RedirectUrlRequestDto.TouchpointEnum.CHECKOUT)
        )
                .expectError(CheckoutRedirectConfigurationException.class)
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
                new RedirectionAuthRequestDetailsDto()
        );

        Hooks.onOperatorDebug();
        /* test */
        InvalidRequestException exception = assertThrows(
                InvalidRequestException.class,
                () -> client.requestRedirectUrlAuthorization(
                        authorizationData,
                        RedirectUrlRequestDto.TouchpointEnum.CHECKOUT
                )
        );
        verify(nodeForwarderClient, times(0)).proxyRequest(any(), any(), any(), any());
        assertEquals("Unmanaged payment method with type code: [CC]", exception.getMessage());
    }

}
