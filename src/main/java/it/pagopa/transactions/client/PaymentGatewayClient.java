package it.pagopa.transactions.client;

import com.azure.cosmos.implementation.InternalServerErrorException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.client.NodeForwarderClient;
import it.pagopa.ecommerce.commons.client.NpgClient;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.domain.Claims;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.exceptions.*;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldsDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.StateResponseDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.WorkflowStateDto;
import it.pagopa.ecommerce.commons.utils.JwtTokenUtils;
import it.pagopa.ecommerce.commons.utils.NpgApiKeyConfiguration;
import it.pagopa.ecommerce.commons.utils.UniqueIdUtils;
import it.pagopa.generated.ecommerce.gateway.v1.api.VposInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.api.XPayInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.dto.VposAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.VposAuthResponseDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.XPayAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.XPayAuthResponseEntityDto;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlRequestDto;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlResponseDto;
import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.CardsAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.WalletAuthRequestDetailsDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.configurations.NpgSessionUrlConfig;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.NpgNotRetryableErrorException;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import it.pagopa.transactions.utils.NpgBuildData;
import it.pagopa.transactions.utils.UUIDUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@Slf4j
public class PaymentGatewayClient {

    private final XPayInternalApi paymentTransactionGatewayXPayWebClient;

    private final VposInternalApi creditCardInternalApiClient;

    private final ObjectMapper objectMapper;

    private final UUIDUtils uuidUtils;

    private final ConfidentialMailUtils confidentialMailUtils;

    private final NpgClient npgClient;

    private final NpgSessionUrlConfig npgSessionUrlConfig;

    private final UniqueIdUtils uniqueIdUtils;
    private final SecretKey npgNotificationSigningKey;
    private final int npgJwtKeyValidityTime;
    private final SecretKey ecommerceSigningKey;
    private final int jwtEcommerceValidityTimeInSeconds;
    private final NodeForwarderClient<RedirectUrlRequestDto, RedirectUrlResponseDto> nodeForwarderRedirectApiClient;
    private final Map<String, URI> redirectBeApiCallUriMap;

    private final Set<String> npgAuthorizationRetryExcludedErrorCodes;

    static final Map<RedirectPaymentMethodId, String> redirectMethodsDescriptions = Map.of(
            RedirectPaymentMethodId.RBPR,
            "Poste addebito in conto Retail",
            RedirectPaymentMethodId.RBPB,
            "Poste addebito in conto Business",
            RedirectPaymentMethodId.RBPP,
            "Paga con BottonePostePay",
            RedirectPaymentMethodId.RPIC,
            "Pago in Conto Intesa",
            RedirectPaymentMethodId.RBPS,
            "SCRIGNO Internet Banking"
    );
    private final NpgApiKeyConfiguration npgApiKeyConfiguration;

    public enum RedirectPaymentMethodId {
        RBPR,
        RBPB,
        RBPP,
        RPIC,
        RBPS;

        private static final Map<String, RedirectPaymentMethodId> lookupMap = Arrays
                .stream(RedirectPaymentMethodId.values())
                .collect(Collectors.toMap(Enum::toString, Function.identity()));

        static RedirectPaymentMethodId fromPaymentTypeCode(String paymentTypeCode) {
            RedirectPaymentMethodId converted = lookupMap.get(paymentTypeCode);
            if (converted == null) {
                throw new InvalidRequestException(
                        "Unmanaged payment method with type code: [%s]".formatted(paymentTypeCode)
                );
            }
            return converted;
        }
    }

    @Autowired
    public PaymentGatewayClient(
            @Qualifier("paymentTransactionGatewayXPayWebClient") XPayInternalApi paymentTransactionGatewayXPayWebClient,
            @Qualifier("creditCardInternalApiClient") VposInternalApi creditCardInternalApiClient,
            ObjectMapper objectMapper,
            UUIDUtils uuidUtils,
            ConfidentialMailUtils confidentialMailUtils,
            NpgClient npgClient,
            NpgSessionUrlConfig npgSessionUrlConfig,
            UniqueIdUtils uniqueIdUtils,
            SecretKey npgNotificationSigningKey,
            @Value("${npg.notification.jwt.validity.time}") int npgJwtKeyValidityTime,
            SecretKey ecommerceSigningKey,
            @Value("${payment.token.validity}") int jwtEcommerceValidityTimeInSeconds,
            NodeForwarderClient<RedirectUrlRequestDto, RedirectUrlResponseDto> nodeForwarderRedirectApiClient,
            Map<String, URI> redirectBeApiCallUriMap,
            NpgApiKeyConfiguration npgApiKeyConfiguration,
            @Value(
                "${npg.authorization.retry.excluded.error.codes}"
            ) Set<String> npgAuthorizationRetryExcludedErrorCodes
    ) {
        this.paymentTransactionGatewayXPayWebClient = paymentTransactionGatewayXPayWebClient;
        this.creditCardInternalApiClient = creditCardInternalApiClient;
        this.objectMapper = objectMapper;
        this.uuidUtils = uuidUtils;
        this.confidentialMailUtils = confidentialMailUtils;
        this.npgClient = npgClient;
        this.npgSessionUrlConfig = npgSessionUrlConfig;
        this.uniqueIdUtils = uniqueIdUtils;
        this.npgNotificationSigningKey = npgNotificationSigningKey;
        this.npgJwtKeyValidityTime = npgJwtKeyValidityTime;
        this.nodeForwarderRedirectApiClient = nodeForwarderRedirectApiClient;
        this.redirectBeApiCallUriMap = redirectBeApiCallUriMap;
        this.ecommerceSigningKey = ecommerceSigningKey;
        this.jwtEcommerceValidityTimeInSeconds = jwtEcommerceValidityTimeInSeconds;
        this.npgApiKeyConfiguration = npgApiKeyConfiguration;
        this.npgAuthorizationRetryExcludedErrorCodes = npgAuthorizationRetryExcludedErrorCodes;
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

    public Mono<Tuple2<String, FieldsDto>> requestNpgBuildSession(
                                                                  AuthorizationRequestData authorizationData,
                                                                  String correlationId,
                                                                  boolean isWalletPayment,
                                                                  String clientId,
                                                                  UUID userId

    ) {
        return requestNpgBuildSession(authorizationData, correlationId, false, isWalletPayment, clientId, userId);
    }

    public Mono<Tuple2<String, FieldsDto>> requestNpgBuildApmPayment(
                                                                     AuthorizationRequestData authorizationData,
                                                                     String correlationId,
                                                                     boolean isWalletPayment,
                                                                     String clientId,
                                                                     UUID userId
    ) {
        return requestNpgBuildSession(authorizationData, correlationId, true, isWalletPayment, clientId, userId);
    }

    private Mono<Tuple2<String, FieldsDto>> requestNpgBuildSession(
                                                                   AuthorizationRequestData authorizationData,
                                                                   String correlationId,
                                                                   boolean isApmPayment,
                                                                   boolean isWalletPayment,
                                                                   String clientId,
                                                                   UUID userId
    ) {
        WorkflowStateDto expectedResponseState = isApmPayment ? WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN
                : WorkflowStateDto.READY_FOR_PAYMENT;
        return retrieveNpgBuildDataInformation(authorizationData, userId)
                .flatMap(
                        npgBuildData -> {
                            String orderId = npgBuildData.orderId();
                            String notificationJwtToken = npgBuildData.notificationJwtToken();
                            String outcomeJwtToken = npgBuildData.outcomeJwtToken();
                            URI returnUrlBasePath = URI.create(npgSessionUrlConfig.basePath());
                            URI outcomeResultUrl = generateOutcomeUrl(
                                    clientId,
                                    authorizationData.transactionId(),
                                    outcomeJwtToken
                            );
                            URI merchantUrl = returnUrlBasePath;
                            URI cancelUrl = returnUrlBasePath.resolve(npgSessionUrlConfig.cancelSuffix());

                            URI notificationUrl = UriComponentsBuilder
                                    .fromHttpUrl(npgSessionUrlConfig.notificationUrl())
                                    .build(
                                            Map.of(
                                                    "orderId",
                                                    orderId,
                                                    "sessionToken",
                                                    notificationJwtToken
                                            )
                                    );
                            Either<NpgApiKeyConfigurationException, String> buildApiKey = isApmPayment
                                    ? npgApiKeyConfiguration.getApiKeyForPaymentMethod(
                                            NpgClient.PaymentMethod
                                                    .fromServiceName(authorizationData.paymentMethodName()),
                                            authorizationData.pspId()
                                    )
                                    : Either.right(npgApiKeyConfiguration.getDefaultApiKey());
                            return buildApiKey.fold(
                                    Mono::error,
                                    apiKey -> {
                                        if (isApmPayment) {
                                            return npgClient.buildFormForPayment(
                                                    UUID.fromString(correlationId),
                                                    merchantUrl,
                                                    outcomeResultUrl,
                                                    notificationUrl,
                                                    cancelUrl,
                                                    orderId,
                                                    null,
                                                    NpgClient.PaymentMethod
                                                            .fromServiceName(authorizationData.paymentMethodName()),
                                                    apiKey,
                                                    isWalletPayment ? authorizationData.contractId().orElseThrow(
                                                            () -> new InternalServerErrorException(
                                                                    "Invalid request missing contractId"
                                                            )
                                                    ) : null,
                                                    authorizationData.paymentNotices().stream()
                                                            .mapToInt(
                                                                    paymentNotice -> paymentNotice.transactionAmount()
                                                                            .value()
                                                            ).sum()
                                                            + authorizationData.fee()
                                            ).map(fieldsDto -> Tuples.of(orderId, fieldsDto));
                                        } else {
                                            return npgClient.buildForm(
                                                    UUID.fromString(correlationId),
                                                    merchantUrl,
                                                    outcomeResultUrl,
                                                    notificationUrl,
                                                    cancelUrl,
                                                    orderId,
                                                    null,
                                                    NpgClient.PaymentMethod
                                                            .fromServiceName(authorizationData.paymentMethodName()),
                                                    apiKey,
                                                    authorizationData.contractId().orElseThrow(
                                                            () -> new InternalServerErrorException(
                                                                    "Invalid request missing contractId"
                                                            )
                                                    )
                                            ).map(fieldsDto -> Tuples.of(orderId, fieldsDto));
                                        }
                                    }
                            );
                        }
                ).onErrorMap(
                        NpgResponseException.class,
                        exception -> exception
                                .getStatusCode()
                                .map(statusCode -> {
                                    List<String> errorCodes = exception.getErrors();
                                    log.error(
                                            "KO performing NPG buildForm: HTTP status code: [%s], errorCodes: %s"
                                                    .formatted(statusCode, errorCodes),
                                            exception
                                    );
                                    if (errorCodes.stream()
                                            .anyMatch(npgAuthorizationRetryExcludedErrorCodes::contains)) {
                                        return new NpgNotRetryableErrorException(
                                                "Npg received error codes: %s, retry excluded error codes: %s"
                                                        .formatted(errorCodes, npgAuthorizationRetryExcludedErrorCodes),
                                                statusCode
                                        );
                                    }
                                    if (statusCode.is4xxClientError()) {
                                        return new NpgNotRetryableErrorException(
                                                "Npg 4xx error for transactionId: [%s], correlationId: [%s]".formatted(
                                                        authorizationData.transactionId().value(),
                                                        correlationId
                                                ),
                                                statusCode
                                        );
                                    } else if (statusCode.is5xxServerError()) {
                                        return new BadGatewayException(
                                                "NPG internal server error response received",
                                                statusCode
                                        );
                                    } else {
                                        return new BadGatewayException(
                                                "Received NPG error response with unmanaged HTTP response status code",
                                                statusCode
                                        );
                                    }
                                })
                                .orElse(
                                        new BadGatewayException(
                                                "Received NPG error response with unknown HTTP response status code",
                                                null
                                        )
                                )
                )
                .filter(
                        orderIdAndFieldsDto -> {
                            FieldsDto fields = orderIdAndFieldsDto.getT2();
                            WorkflowStateDto receivedState = fields.getState();
                            boolean sessionIdValid = fields.getSessionId() != null
                                    && !fields.getSessionId().isEmpty();
                            boolean securityTokenValid = fields.getSecurityToken() != null
                                    && !fields.getSecurityToken().isEmpty();
                            boolean isOk = sessionIdValid && securityTokenValid && Objects
                                    .equals(fields.getState(), expectedResponseState);
                            if (!isOk) {
                                log.error(
                                        "NPG order/build response error! Received state: [{}], expected state: [{}]. Session id is valid: [{}], security token is valid: [{}]",
                                        receivedState,
                                        expectedResponseState,
                                        sessionIdValid,
                                        securityTokenValid
                                );
                            }
                            return isOk;
                        }
                )
                .switchIfEmpty(
                        Mono.error(
                                new BadGatewayException("Error while invoke NPG build session", HttpStatus.BAD_GATEWAY)
                        )
                );
    }

    public Mono<StateResponseDto> requestNpgCardsAuthorization(
                                                               AuthorizationRequestData authorizationData,
                                                               String correlationId
    ) {
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
                                || authorizationData
                                        .authDetails() instanceof WalletAuthRequestDetailsDto
                )
                .switchIfEmpty(
                        Mono.error(
                                new InvalidRequestException(
                                        "Cannot perform NPG authorization for invalid input CardsAuthRequestDetailsDto or WalletAuthRequestDetailsDto"
                                )
                        )
                )
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
                    final var pspNpgApiKey = npgApiKeyConfiguration
                            .getApiKeyForPaymentMethod(NpgClient.PaymentMethod.CARDS, authorizationData.pspId());
                    return pspNpgApiKey.fold(
                            Mono::error,
                            apiKey -> npgClient.confirmPayment(
                                    UUID.fromString(correlationId),
                                    authorizationData.sessionId().get(),
                                    grandTotal,
                                    apiKey
                            )
                                    .onErrorMap(
                                            NpgResponseException.class,
                                            exception -> exception
                                                    .getStatusCode()
                                                    .map(statusCode -> {
                                                        List<String> errorCodes = exception.getErrors();
                                                        log.error(
                                                                "KO performing NPG confirmPayment: HTTP status code: [%s], errorCodes: %s"
                                                                        .formatted(statusCode, errorCodes),
                                                                exception
                                                        );
                                                        if (errorCodes.stream().anyMatch(
                                                                npgAuthorizationRetryExcludedErrorCodes::contains
                                                        )) {
                                                            return new NpgNotRetryableErrorException(
                                                                    "Npg received error codes: %s, retry excluded error codes: %s"
                                                                            .formatted(
                                                                                    errorCodes,
                                                                                    npgAuthorizationRetryExcludedErrorCodes
                                                                            ),
                                                                    statusCode
                                                            );
                                                        }
                                                        if (statusCode.is4xxClientError()) {
                                                            return new NpgNotRetryableErrorException(
                                                                    "Npg 4xx error for transactionId: [%s], correlationId: [%s]"
                                                                            .formatted(
                                                                                    authorizationData.transactionId()
                                                                                            .value(),
                                                                                    correlationId
                                                                            ),
                                                                    statusCode
                                                            );
                                                        } else if (statusCode.is5xxServerError()) {
                                                            return new BadGatewayException(
                                                                    "NPG internal server error response received",
                                                                    statusCode
                                                            );
                                                        } else {
                                                            return new BadGatewayException(
                                                                    "Received NPG error response with unmanaged HTTP response status code",
                                                                    statusCode
                                                            );
                                                        }
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

    /**
     * Perform authorization request with PSP retrieving redirection URL
     *
     * @param authorizationData authorization data
     * @param touchpoint        the touchpoint used to initiate the transaction
     * @return RedirectUrlResponseDto response bean
     */
    public Mono<RedirectUrlResponseDto> requestRedirectUrlAuthorization(
                                                                        AuthorizationRequestData authorizationData,
                                                                        RedirectUrlRequestDto.TouchpointEnum touchpoint
    ) {
        return new JwtTokenUtils()
                .generateToken(
                        ecommerceSigningKey,
                        jwtEcommerceValidityTimeInSeconds,
                        new Claims(
                                authorizationData.transactionId(),
                                null,
                                authorizationData.paymentInstrumentId(),
                                null
                        )
                ).fold(
                        Mono::error,
                        outcomeJwtToken -> {

                            RedirectPaymentMethodId idPaymentMethod = RedirectPaymentMethodId
                                    .fromPaymentTypeCode(authorizationData.paymentTypeCode());

                            /*
                             * `paName` is shown to users on the payment gateway redirect page. If there is
                             * only one payment notice we use its `companyName` as `paName`, otherwise there
                             * would be an ambiguity, so we don't pass it into the authorization request
                             */
                            String paName = null;

                            if (authorizationData.paymentNotices().size() == 1) {
                                paName = authorizationData.paymentNotices().get(0).companyName().value();
                            }

                            RedirectUrlRequestDto request = new RedirectUrlRequestDto()
                                    .amount(
                                            authorizationData
                                                    .paymentNotices()
                                                    .stream()
                                                    .mapToInt(
                                                            p -> p.transactionAmount().value()
                                                    )
                                                    .sum()
                                                    + authorizationData.fee()
                                    )
                                    .idPsp(authorizationData.pspId())
                                    .idTransaction(authorizationData.transactionId().value())
                                    .description(
                                            authorizationData
                                                    .paymentNotices()
                                                    .stream()
                                                    .findFirst()
                                                    .map(
                                                            p -> p.transactionDescription()
                                                                    .value()
                                                    )
                                                    .orElseThrow()
                                    )
                                    .urlBack(
                                            generateOutcomeUrl(
                                                    touchpoint.getValue(),
                                                    authorizationData.transactionId(),
                                                    outcomeJwtToken
                                            )
                                    )
                                    .touchpoint(touchpoint)
                                    .paymentMethod(
                                            redirectMethodsDescriptions.get(idPaymentMethod)
                                    )
                                    .idPaymentMethod(idPaymentMethod.toString())
                                    .paName(paName);// optional
                            Either<RedirectConfigurationException, URI> pspConfiguredUrl = getRedirectUrlForPsp(
                                    authorizationData.pspId(),
                                    authorizationData.paymentTypeCode()
                            );

                            return pspConfiguredUrl.fold(
                                    Mono::error,
                                    proxyPspUrl -> nodeForwarderRedirectApiClient
                                            .proxyRequest(
                                                    request,
                                                    proxyPspUrl,
                                                    authorizationData.transactionId().value(),
                                                    RedirectUrlResponseDto.class
                                            ).onErrorMap(
                                                    NodeForwarderClientException.class,
                                                    exception -> {
                                                        String pspId = authorizationData.pspId();
                                                        Optional<HttpStatus> responseHttpStatus = Optional
                                                                .ofNullable(exception.getCause())
                                                                .filter(WebClientResponseException.class::isInstance)
                                                                .map(
                                                                        e -> ((WebClientResponseException) e)
                                                                                .getStatusCode()
                                                                );
                                                        log.error(
                                                                "Error communicating with PSP: [%s] to retrieve redirection URL. Received HTTP status code: %s"
                                                                        .formatted(
                                                                                pspId,
                                                                                responseHttpStatus
                                                                        ),
                                                                exception
                                                        );
                                                        if (responseHttpStatus.isPresent()) {
                                                            HttpStatus httpStatus = responseHttpStatus.get();
                                                            if (httpStatus.is4xxClientError()) {
                                                                return new AlreadyProcessedException(
                                                                        authorizationData.transactionId()
                                                                );
                                                            } else if (httpStatus.is5xxServerError()) {
                                                                return new BadGatewayException(
                                                                        "KO performing redirection URL api call for PSP: [%s]"
                                                                                .formatted(pspId),
                                                                        httpStatus
                                                                );
                                                            } else {
                                                                return new BadGatewayException(
                                                                        "Unhandled error performing redirection URL api call for PSP: [%s]"
                                                                                .formatted(pspId),
                                                                        null
                                                                );
                                                            }
                                                        } else {
                                                            return new BadGatewayException(
                                                                    "Unhandled error performing redirection URL api call for PSP: [%s]"
                                                                            .formatted(pspId),
                                                                    null
                                                            );
                                                        }
                                                    }
                                            )
                                            .map(NodeForwarderClient.NodeForwarderResponse::body)
                            );
                        }
                );

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

    private Either<RedirectConfigurationException, URI> getRedirectUrlForPsp(
                                                                             String pspId,
                                                                             String paymentTypeCode
    ) {
        String urlKey = "%s-%s".formatted(pspId, paymentTypeCode);
        if (redirectBeApiCallUriMap.containsKey(urlKey)) {
            return Either.right(redirectBeApiCallUriMap.get(urlKey));
        } else {
            return Either.left(
                    new RedirectConfigurationException(
                            "Missing key for redirect return url with key: [%s]".formatted(urlKey),
                            RedirectConfigurationType.BACKEND_URLS
                    )
            );
        }
    }

    private Mono<NpgBuildData> retrieveNpgBuildDataInformation(
                                                               AuthorizationRequestData authorizationRequestData,
                                                               UUID userId
    ) {
        return uniqueIdUtils.generateUniqueId()
                .flatMap(
                        orderId -> new JwtTokenUtils()
                                .generateToken(
                                        npgNotificationSigningKey,
                                        npgJwtKeyValidityTime,
                                        new Claims(
                                                authorizationRequestData.transactionId(),
                                                orderId,
                                                authorizationRequestData.paymentInstrumentId(),
                                                userId
                                        )
                                ).fold(
                                        Mono::error,
                                        notificationToken -> new JwtTokenUtils()
                                                .generateToken(
                                                        ecommerceSigningKey,
                                                        jwtEcommerceValidityTimeInSeconds,
                                                        new Claims(
                                                                authorizationRequestData.transactionId(),
                                                                orderId,
                                                                authorizationRequestData.paymentInstrumentId(),
                                                                userId
                                                        )
                                                ).fold(
                                                        Mono::error,
                                                        outcomeToken -> Mono.just(
                                                                new NpgBuildData(
                                                                        orderId,
                                                                        notificationToken,
                                                                        outcomeToken
                                                                )
                                                        )
                                                )
                                )

                );
    }

    private URI generateOutcomeUrl(
                                   String clientId,
                                   TransactionId transactionId,
                                   String sessionToken
    ) {
        return UriComponentsBuilder
                .fromUriString(npgSessionUrlConfig.basePath().concat(npgSessionUrlConfig.outcomeSuffix()))
                .build(
                        Map.of(
                                "clientId",
                                clientId,
                                "transactionId",
                                transactionId.value(),
                                "sessionToken",
                                sessionToken
                        )
                );
    }
}
