package it.pagopa.transactions.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.pagopa.ecommerce.commons.client.NpgClient;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.exceptions.NpgResponseException;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldsDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.StateResponseDto;
import it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig;
import it.pagopa.ecommerce.commons.utils.UniqueIdUtils;
import it.pagopa.generated.ecommerce.gateway.v1.api.PostePayInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.api.VposInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.api.XPayInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.dto.*;
import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.CardsAuthRequestDetailsDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.configurations.NpgSessionUrlConfig;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.exceptions.GatewayTimeoutException;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import it.pagopa.transactions.utils.UUIDUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Component
public class PaymentGatewayClient {

    private final PostePayInternalApi postePayInternalApi;

    private final XPayInternalApi paymentTransactionGatewayXPayWebClient;

    private final VposInternalApi creditCardInternalApiClient;

    private final ObjectMapper objectMapper;

    private final UUIDUtils uuidUtils;

    private final ConfidentialMailUtils confidentialMailUtils;

    private final NpgClient npgClient;

    private final NpgSessionUrlConfig npgSessionUrlConfig;

    private final NpgPspApiKeysConfig npgCardsApiKeys;
    private final UniqueIdUtils uniqueIdUtils;
    private final String npgDefaultApiKey;

    @Autowired
    public PaymentGatewayClient(
            @Qualifier("paymentTransactionGatewayPostepayWebClient") PostePayInternalApi postePayInternalApi,
            @Qualifier("paymentTransactionGatewayXPayWebClient") XPayInternalApi paymentTransactionGatewayXPayWebClient,
            @Qualifier("creditCardInternalApiClient") VposInternalApi creditCardInternalApiClient,
            ObjectMapper objectMapper,
            UUIDUtils uuidUtils,
            ConfidentialMailUtils confidentialMailUtils,
            NpgClient npgClient,
            NpgPspApiKeysConfig npgCardsApiKeys,
            NpgSessionUrlConfig npgSessionUrlConfig,
            UniqueIdUtils uniqueIdUtils,
            @Value("${npg.client.apiKey}") String npgDefaultApiKey

    ) {
        this.postePayInternalApi = postePayInternalApi;
        this.paymentTransactionGatewayXPayWebClient = paymentTransactionGatewayXPayWebClient;
        this.creditCardInternalApiClient = creditCardInternalApiClient;
        this.objectMapper = objectMapper;
        this.uuidUtils = uuidUtils;
        this.confidentialMailUtils = confidentialMailUtils;
        this.npgClient = npgClient;
        this.npgCardsApiKeys = npgCardsApiKeys;
        this.npgSessionUrlConfig = npgSessionUrlConfig;
        this.uniqueIdUtils = uniqueIdUtils;
        this.npgDefaultApiKey = npgDefaultApiKey;
    }

    // TODO Handle multiple rptId

    public Mono<PostePayAuthResponseEntityDto> requestPostepayAuthorization(
                                                                            AuthorizationRequestData authorizationData
    ) {

        return Mono.just(authorizationData)
                .filter(authorizationRequestData -> "PPAY".equals(authorizationRequestData.paymentTypeCode()))
                .map(authorizationRequestData -> {
                    BigDecimal grandTotal = BigDecimal.valueOf(
                            ((long) authorizationData.paymentNotices().stream()
                                    .mapToInt(paymentNotice -> paymentNotice.transactionAmount().value()).sum())
                                    + authorizationData.fee()
                    );
                    return new PostePayAuthRequestDto()
                            .grandTotal(grandTotal)
                            .description(
                                    authorizationData.paymentNotices().get(0).transactionDescription()
                                            .value()
                            )
                            .paymentChannel(authorizationData.pspChannelCode())
                            .idTransaction(
                                    uuidUtils.uuidToBase64(authorizationData.transactionId().uuid())
                            );
                })
                .flatMap(
                        payAuthRequestDto -> postePayInternalApi
                                .authRequest(payAuthRequestDto, false, encodeMdcFields(authorizationData))
                                .onErrorMap(
                                        WebClientResponseException.class,
                                        exception -> switch (exception.getStatusCode()) {
                                        case UNAUTHORIZED -> new AlreadyProcessedException(
                                                authorizationData.transactionId()
                                        );
                                        case GATEWAY_TIMEOUT -> new GatewayTimeoutException();
                                        case INTERNAL_SERVER_ERROR -> new BadGatewayException(
                                                "PostePay API returned 500",
                                                exception.getStatusCode()
                                        );
                                        default -> exception;
                                        }
                                )
                );
    }

    public Mono<XPayAuthResponseEntityDto> requestXPayAuthorization(AuthorizationRequestData authorizationData) {

        return Mono.just(authorizationData)
                .filter(
                        authorizationRequestData -> "CP".equals(authorizationRequestData.paymentTypeCode())
                                && TransactionAuthorizationRequestData.PaymentGateway.XPAY.equals(
                                        TransactionAuthorizationRequestData.PaymentGateway
                                                .valueOf(authorizationRequestData.paymentGatewayId())
                                )
                )
                .switchIfEmpty(Mono.empty())
                .flatMap(authorizationRequestData -> {
                    final Mono<XPayAuthRequestDto> xPayAuthRequest;
                    if (authorizationData.authDetails()instanceof CardAuthRequestDetailsDto cardData) {
                        BigDecimal grandTotal = BigDecimal.valueOf(
                                ((long) authorizationData.paymentNotices().stream()
                                        .mapToInt(paymentNotice -> paymentNotice.transactionAmount().value()).sum())
                                        + authorizationData.fee()
                        );
                        xPayAuthRequest = Mono.just(
                                new XPayAuthRequestDto()
                                        .cvv(cardData.getCvv())
                                        .pan(cardData.getPan())
                                        .expiryDate(cardData.getExpiryDate())
                                        .idTransaction(
                                                uuidUtils.uuidToBase64(
                                                        authorizationData.transactionId().uuid()
                                                )
                                        )
                                        .grandTotal(grandTotal)
                        );
                    } else {
                        xPayAuthRequest = Mono.error(
                                new InvalidRequestException(
                                        "Cannot perform XPAY authorization for null input CardAuthRequestDetailsDto"
                                )
                        );
                    }
                    return xPayAuthRequest;
                })
                .flatMap(
                        xPayAuthRequestDto -> paymentTransactionGatewayXPayWebClient
                                .authXpay(xPayAuthRequestDto, encodeMdcFields(authorizationData))
                                .onErrorMap(
                                        WebClientResponseException.class,
                                        exception -> switch (exception.getStatusCode()) {
                                        case UNAUTHORIZED -> new AlreadyProcessedException(
                                                authorizationData.transactionId()
                                        ); // 401
                                        case INTERNAL_SERVER_ERROR -> new BadGatewayException(
                                                "",
                                                exception.getStatusCode()
                                        ); // 500
                                        default -> exception;
                                        }
                                )
                );
    }

    public Mono<VposAuthResponseDto> requestCreditCardAuthorization(AuthorizationRequestData authorizationData) {
        return Mono.just(authorizationData)
                .filter(
                        authorizationRequestData -> "CP".equals(authorizationRequestData.paymentTypeCode())
                                && TransactionAuthorizationRequestData.PaymentGateway.VPOS
                                        .equals(
                                                TransactionAuthorizationRequestData.PaymentGateway
                                                        .valueOf(authorizationRequestData.paymentGatewayId())
                                        )
                )
                .switchIfEmpty(Mono.empty())
                .flatMap(
                        authorizationRequestData -> confidentialMailUtils
                                .toEmail(authorizationRequestData.email())
                )
                .flatMap(email -> {
                    final Mono<VposAuthRequestDto> creditCardAuthRequest;
                    if (authorizationData.authDetails()instanceof CardAuthRequestDetailsDto cardData) {
                        BigDecimal grandTotal = BigDecimal.valueOf(
                                ((long) authorizationData.paymentNotices().stream()
                                        .mapToInt(paymentNotice -> paymentNotice.transactionAmount().value()).sum())
                                        + authorizationData.fee()
                        );
                        creditCardAuthRequest = Mono.just(
                                new VposAuthRequestDto()
                                        .pan(cardData.getPan())
                                        .expireDate(cardData.getExpiryDate())
                                        .idTransaction(
                                                uuidUtils.uuidToBase64(
                                                        authorizationData.transactionId().uuid()
                                                )
                                        )
                                        .amount(grandTotal)
                                        .emailCH(email.value())
                                        .holder(cardData.getHolderName())
                                        .securityCode(cardData.getCvv())
                                        .isFirstPayment(true) // TODO TO BE CHECKED
                                        .threeDsData(cardData.getThreeDsData())
                                        .circuit(VposAuthRequestDto.CircuitEnum.valueOf(cardData.getBrand().toString()))
                                        .idPsp(authorizationData.pspId())
                        );
                    } else {
                        creditCardAuthRequest = Mono.error(
                                new InvalidRequestException(
                                        "Cannot perform VPOS authorization for null input CreditCardAuthRequestDto"
                                )
                        );
                    }
                    return creditCardAuthRequest;
                })
                .flatMap(
                        creditCardAuthRequestDto -> creditCardInternalApiClient
                                .step0VposAuth(
                                        creditCardAuthRequestDto,
                                        encodeMdcFields(authorizationData)
                                )
                                .onErrorMap(
                                        WebClientResponseException.class,
                                        exception -> switch (exception.getStatusCode()) {
                                        case UNAUTHORIZED -> new AlreadyProcessedException(
                                                authorizationData.transactionId()
                                        ); // 401
                                        case INTERNAL_SERVER_ERROR -> new BadGatewayException(
                                                "",
                                                exception.getStatusCode()
                                        ); // 500
                                        default -> exception;
                                        }
                                )
                );
    }

    public Mono<FieldsDto> requestNpgBuildSession(AuthorizationRequestData authorizationData) {
        return uniqueIdUtils.generateUniqueId()
                .flatMap(
                        orderId -> {
                            UUID correlationId = UUID.randomUUID();
                            URI returnUrlBasePath = URI.create(npgSessionUrlConfig.basePath());
                            URI resultUrl = returnUrlBasePath.resolve(npgSessionUrlConfig.outcomeSuffix());
                            URI merchantUrl = returnUrlBasePath;
                            URI cancelUrl = returnUrlBasePath.resolve(npgSessionUrlConfig.cancelSuffix());
                            URI notificationUrl = UriComponentsBuilder
                                    .fromHttpUrl(npgSessionUrlConfig.notificationUrl())
                                    .build(
                                            Map.of(
                                                    "orderId",
                                                    orderId,
                                                    "paymentMethodId",
                                                    authorizationData.paymentInstrumentId()
                                            )
                                    );
                            return npgClient.buildForm(
                                    correlationId,
                                    merchantUrl,
                                    resultUrl,
                                    notificationUrl,
                                    cancelUrl,
                                    orderId,
                                    null,
                                    NpgClient.PaymentMethod.fromServiceName(authorizationData.paymentMethodName()),
                                    npgDefaultApiKey,
                                    authorizationData.contractId().get()
                            );
                        }
                ).onErrorMap(
                        NpgResponseException.class,
                        exception -> exception
                                .getStatusCode()
                                .map(statusCode -> switch (statusCode) {
                                case UNAUTHORIZED -> new AlreadyProcessedException(
                                        authorizationData.transactionId()
                                ); // 401
                                case INTERNAL_SERVER_ERROR -> new BadGatewayException(
                                        "NPG internal server error response received",
                                        statusCode
                                ); // 500
                                default -> new BadGatewayException(
                                        "Received NPG error response with unmanaged HTTP response status code",
                                        statusCode
                                );
                                })
                                .orElse(
                                        new BadGatewayException(
                                                "Received NPG error response with unknown HTTP response status code",
                                                null
                                        )
                                )
                );
    }

    public Mono<StateResponseDto> requestNpgCardsAuthorization(AuthorizationRequestData authorizationData) {
        return Mono.just(authorizationData)
                .filter(
                        authorizationRequestData -> "CP".equals(authorizationRequestData.paymentTypeCode())
                                && TransactionAuthorizationRequestData.PaymentGateway.NPG.equals(
                                        TransactionAuthorizationRequestData.PaymentGateway
                                                .valueOf(authorizationRequestData.paymentGatewayId())
                                )
                )
                .switchIfEmpty(Mono.empty())
                .filter(
                        authorizationRequestData -> authorizationData
                                .authDetails() instanceof CardsAuthRequestDetailsDto
                )
                .switchIfEmpty(
                        Mono.error(
                                new InvalidRequestException(
                                        "Cannot perform NPG authorization for invalid input CardsAuthRequestDetailsDto"
                                )
                        )
                )
                .map(AuthorizationRequestData::authDetails)
                .cast(CardsAuthRequestDetailsDto.class)
                .flatMap(authorizationRequestData -> {
                    final BigDecimal grandTotal = BigDecimal.valueOf(
                            ((long) authorizationData.paymentNotices().stream()
                                    .mapToInt(paymentNotice -> paymentNotice.transactionAmount().value()).sum())
                                    + authorizationData.fee()
                    );
                    if (authorizationData.sessionId().isEmpty()) {
                        return Mono.error(
                                new BadGatewayException(
                                        "Missing sessionId for transactionId: "
                                                + authorizationData.transactionId(),
                                        HttpStatus.BAD_GATEWAY
                                )
                        );
                    }
                    final UUID correlationId = UUID.randomUUID();
                    final var pspNpgApiKey = npgCardsApiKeys.get(authorizationData.pspId());
                    return pspNpgApiKey.fold(
                            Mono::error,
                            apiKey -> npgClient.confirmPayment(
                                    correlationId,
                                    authorizationData.sessionId().get(),
                                    grandTotal,
                                    apiKey
                            )
                                    .onErrorMap(
                                            NpgResponseException.class,
                                            exception -> exception
                                                    .getStatusCode()
                                                    .map(statusCode -> switch (statusCode) {
                        case UNAUTHORIZED -> new AlreadyProcessedException(
                                authorizationData.transactionId()
                        ); // 401
                        case INTERNAL_SERVER_ERROR -> new BadGatewayException(
                                "NPG internal server error response received",
                                statusCode
                        ); // 500
                        default -> new BadGatewayException(
                                "Received NPG error response with unmanaged HTTP response status code",
                                statusCode
                        );
                    })
                                                    .orElse(
                                                            new BadGatewayException(
                                                                    "Received NPG error response with unknown HTTP response status code",
                                                                    null
                                                            )
                                                    )
                                    )
                    );
                });
    }

    private String encodeMdcFields(AuthorizationRequestData authorizationData) {
        String mdcData;
        try {
            mdcData = objectMapper.writeValueAsString(
                    Map.of("transactionId", authorizationData.transactionId().value())
            );
        } catch (JsonProcessingException e) {
            mdcData = "";
        }

        return Base64.getEncoder().encodeToString(mdcData.getBytes(StandardCharsets.UTF_8));
    }
}
