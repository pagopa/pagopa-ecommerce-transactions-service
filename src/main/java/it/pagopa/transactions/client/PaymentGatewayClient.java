package it.pagopa.transactions.client;

import com.azure.cosmos.implementation.InternalServerErrorException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.client.NodeForwarderClient;
import it.pagopa.ecommerce.commons.client.NpgClient;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.domain.v2.Claims;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.exceptions.*;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenRequestDto;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenResponseDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldsDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.StateResponseDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.WorkflowStateDto;
import it.pagopa.ecommerce.commons.utils.v2.JwtTokenUtils;
import it.pagopa.ecommerce.commons.utils.NpgApiKeyConfiguration;
import it.pagopa.ecommerce.commons.utils.RedirectKeysConfiguration;
import it.pagopa.ecommerce.commons.utils.UniqueIdUtils;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlRequestDto;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlResponseDto;
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
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static it.pagopa.ecommerce.commons.documents.v2.Transaction.*;

@Component
@Slf4j
public class PaymentGatewayClient {

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
    private final RedirectKeysConfiguration redirectKeysConfig;

    private final Set<String> npgAuthorizationRetryExcludedErrorCodes;

    private final NpgApiKeyConfiguration npgApiKeyConfiguration;

    private final Map<String, String> redirectPaymentTypeCodeDescription;

    private final JwtTokenIssuerClient jwtTokenIssuerClient;

    @Autowired
    public PaymentGatewayClient(

            ObjectMapper objectMapper,
            UUIDUtils uuidUtils,
            ConfidentialMailUtils confidentialMailUtils,
            NpgClient npgClient,
            NpgSessionUrlConfig npgSessionUrlConfig,
            UniqueIdUtils uniqueIdUtils,
            @Qualifier("npgNotificationSigningKey") SecretKey npgNotificationSigningKey,
            @Value("${npg.notification.jwt.validity.time}") int npgJwtKeyValidityTime,
            @Qualifier("ecommerceSigningKey") SecretKey ecommerceSigningKey,
            @Value("${payment.token.validity}") int jwtEcommerceValidityTimeInSeconds,
            NodeForwarderClient<RedirectUrlRequestDto, RedirectUrlResponseDto> nodeForwarderRedirectApiClient,
            RedirectKeysConfiguration redirectKeysConfig,
            NpgApiKeyConfiguration npgApiKeyConfiguration,
            @Value(
                "${npg.authorization.retry.excluded.error.codes}"
            ) Set<String> npgAuthorizationRetryExcludedErrorCodes,
            @Value(
                "#{${redirect.paymentTypeCodeDescriptionMapping}}"
            ) Map<String, String> redirectPaymentTypeCodeDescription,
            JwtTokenIssuerClient jwtTokenIssuerClient
    ) {
        this.objectMapper = objectMapper;
        this.uuidUtils = uuidUtils;
        this.confidentialMailUtils = confidentialMailUtils;
        this.npgClient = npgClient;
        this.npgSessionUrlConfig = npgSessionUrlConfig;
        this.uniqueIdUtils = uniqueIdUtils;
        this.npgNotificationSigningKey = npgNotificationSigningKey;
        this.npgJwtKeyValidityTime = npgJwtKeyValidityTime;
        this.nodeForwarderRedirectApiClient = nodeForwarderRedirectApiClient;
        this.redirectKeysConfig = redirectKeysConfig;
        this.ecommerceSigningKey = ecommerceSigningKey;
        this.jwtEcommerceValidityTimeInSeconds = jwtEcommerceValidityTimeInSeconds;
        this.npgApiKeyConfiguration = npgApiKeyConfiguration;
        this.npgAuthorizationRetryExcludedErrorCodes = npgAuthorizationRetryExcludedErrorCodes;
        this.redirectPaymentTypeCodeDescription = redirectPaymentTypeCodeDescription;
        this.jwtTokenIssuerClient = jwtTokenIssuerClient;
    }

    public Mono<Tuple2<String, FieldsDto>> requestNpgBuildSession(
                                                                  AuthorizationRequestData authorizationData,
                                                                  String correlationId,
                                                                  boolean isWalletPayment,
                                                                  String clientId,
                                                                  String lang,
                                                                  UUID userId

    ) {
        return requestNpgBuildSession(authorizationData, correlationId, false, isWalletPayment, clientId, lang, userId);
    }

    public Mono<Tuple2<String, FieldsDto>> requestNpgBuildApmPayment(
                                                                     AuthorizationRequestData authorizationData,
                                                                     String correlationId,
                                                                     boolean isWalletPayment,
                                                                     String clientId,
                                                                     String lang,
                                                                     UUID userId
    ) {
        return requestNpgBuildSession(authorizationData, correlationId, true, isWalletPayment, clientId, lang, userId);
    }

    private Mono<Tuple2<String, FieldsDto>> requestNpgBuildSession(
                                                                   AuthorizationRequestData authorizationData,
                                                                   String correlationId,
                                                                   boolean isApmPayment,
                                                                   boolean isWalletPayment,
                                                                   String clientId,
                                                                   String lang,
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
                                                    .valueOf(authorizationData.paymentMethodName()),
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
                                                    outcomeResultUrl,
                                                    orderId,
                                                    null,
                                                    NpgClient.PaymentMethod
                                                            .valueOf(authorizationData.paymentMethodName()),
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
                                                            + authorizationData.fee(),
                                                    lang
                                            ).map(fieldsDto -> Tuples.of(orderId, fieldsDto));
                                        } else {
                                            return npgClient.buildForm(
                                                    UUID.fromString(correlationId),
                                                    merchantUrl,
                                                    outcomeResultUrl,
                                                    notificationUrl,
                                                    outcomeResultUrl,
                                                    orderId,
                                                    null,
                                                    NpgClient.PaymentMethod
                                                            .valueOf(authorizationData.paymentMethodName()),
                                                    apiKey,
                                                    authorizationData.contractId().orElseThrow(
                                                            () -> new InternalServerErrorException(
                                                                    "Invalid request missing contractId"
                                                            )
                                                    ),
                                                    lang
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
                .filter(this::isValidPaymentType)
                .switchIfEmpty(Mono.empty())
                .filter(this::isValidAuthDetails)
                .switchIfEmpty(
                        Mono.error(
                                new InvalidRequestException(
                                        "Cannot perform NPG authorization for invalid input CardsAuthRequestDetailsDto or WalletAuthRequestDetailsDto"
                                )
                        )
                )
                .flatMap(authData -> processAuthorization(authData, correlationId));
    }

    private boolean isValidPaymentType(AuthorizationRequestData data) {
        return "CP".equals(data.paymentTypeCode()) &&
                TransactionAuthorizationRequestData.PaymentGateway.NPG.equals(
                        TransactionAuthorizationRequestData.PaymentGateway.valueOf(data.paymentGatewayId())
                );
    }

    private boolean isValidAuthDetails(AuthorizationRequestData data) {
        return data.authDetails() instanceof CardsAuthRequestDetailsDto ||
                data.authDetails() instanceof WalletAuthRequestDetailsDto;
    }

    private Mono<StateResponseDto> processAuthorization(
                                                        AuthorizationRequestData data,
                                                        String correlationId
    ) {
        BigDecimal grandTotal = calculateGrandTotal(data);
        if (data.sessionId().isEmpty()) {
            return Mono.error(
                    new BadGatewayException(
                            "Missing sessionId for transactionId: " + data.transactionId(),
                            HttpStatus.BAD_GATEWAY
                    )
            );
        }

        return npgApiKeyConfiguration.getApiKeyForPaymentMethod(NpgClient.PaymentMethod.CARDS, data.pspId())
                .fold(
                        Mono::error,
                        apiKey -> npgClient.confirmPayment(
                                UUID.fromString(correlationId),
                                data.sessionId().get(),
                                grandTotal,
                                apiKey
                        ).onErrorMap(
                                NpgResponseException.class,
                                exception -> handleNpgResponseException(exception, data, correlationId)
                        )
                );
    }

    private BigDecimal calculateGrandTotal(AuthorizationRequestData data) {
        long totalAmount = data.paymentNotices().stream()
                .mapToInt(paymentNotice -> paymentNotice.transactionAmount().value())
                .sum();
        return BigDecimal.valueOf(totalAmount + data.fee());
    }

    private Throwable handleNpgResponseException(
                                                 NpgResponseException exception,
                                                 AuthorizationRequestData data,
                                                 String correlationId
    ) {
        return exception.getStatusCode()
                .map(statusCode -> {
                    List<String> errorCodes = exception.getErrors();
                    log.error(
                            "KO performing NPG confirmPayment: HTTP status code: [{}], errorCodes: {}",
                            statusCode,
                            errorCodes,
                            exception
                    );
                    if (errorCodes.stream().anyMatch(npgAuthorizationRetryExcludedErrorCodes::contains)) {
                        return new NpgNotRetryableErrorException(
                                "Npg received error codes: " + errorCodes + ", retry excluded error codes: "
                                        + npgAuthorizationRetryExcludedErrorCodes,
                                statusCode
                        );
                    }
                    if (statusCode.is4xxClientError()) {
                        return new NpgNotRetryableErrorException(
                                "Npg 4xx error for transactionId: [" + data.transactionId().value()
                                        + "], correlationId: [" + correlationId + "]",
                                statusCode
                        );
                    } else if (statusCode.is5xxServerError()) {
                        return new BadGatewayException("NPG internal server error response received", statusCode);
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
                );
    }

    private Mono<String> generateJwtToken(
                                          TransactionId transactionId,
                                          String paymentInstrumentId,
                                          UUID userId,
                                          Integer validityTime,
                                          String audience
    ) {

        return Mono.just(
                userId == null ? Map.of(
                        JwtTokenUtils.TRANSACTION_ID_CLAIM,
                        transactionId.value(),
                        JwtTokenUtils.PAYMENT_METHOD_ID_CLAIM,
                        paymentInstrumentId
                )
                        : Map.of(
                                JwtTokenUtils.TRANSACTION_ID_CLAIM,
                                transactionId.value(),
                                JwtTokenUtils.PAYMENT_METHOD_ID_CLAIM,
                                paymentInstrumentId,
                                JwtTokenUtils.USER_ID_CLAIM,
                                userId.toString()
                        )
        ).flatMap(
                claimsMap -> jwtTokenIssuerClient.createJWTToken(
                        new CreateTokenRequestDto()
                                .duration(validityTime)
                                .audience(audience)
                                .privateClaims(claimsMap)
                )
        ).doOnError(
                c -> Mono.error(
                        new JwtIssuerClientException(
                                "Error while generating jwt token for webview",
                                c
                        )
                )
        )
                .map(CreateTokenResponseDto::getToken);
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
                                                                        RedirectUrlRequestDto.TouchpointEnum touchpoint,
                                                                        UUID userId
    ) {
        return generateJwtToken(
                authorizationData.transactionId(),
                authorizationData.paymentInstrumentId(),
                userId,
                jwtEcommerceValidityTimeInSeconds,
                "ecommerce"
        )
                .flatMap(
                        outcomeJwtToken -> {

                            String paymentTypeCode = authorizationData.paymentTypeCode();

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
                                            redirectPaymentTypeCodeDescription.getOrDefault(paymentTypeCode, null)
                                    )
                                    .idPaymentMethod(paymentTypeCode)
                                    .paName(shortenRedirectPaName(paName));// optional
                            Either<RedirectConfigurationException, URI> pspConfiguredUrl = redirectKeysConfig
                                    .getRedirectUrlForPsp(
                                            touchpoint.name(),
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

    private String shortenRedirectPaName(@Nullable String paName) {
        if (paName == null || paName.length() <= 70) {
            return paName;
        } else {
            return paName.substring(0, 67) + "...";
        }
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

    private Mono<NpgBuildData> retrieveNpgBuildDataInformation(
                                                               AuthorizationRequestData authorizationRequestData,
                                                               UUID userId
    ) {
        return uniqueIdUtils.generateUniqueId()
                .map(orderId -> {
                    Map<String, String> claimsMap = new HashMap<>();
                    claimsMap.put(JwtTokenUtils.ORDER_ID_CLAIM, orderId);
                    claimsMap.put(JwtTokenUtils.TRANSACTION_ID_CLAIM, authorizationRequestData.transactionId().value());
                    claimsMap
                            .put(JwtTokenUtils.PAYMENT_METHOD_ID_CLAIM, authorizationRequestData.paymentInstrumentId());
                    if (userId != null) {
                        claimsMap.put(JwtTokenUtils.USER_ID_CLAIM, userId.toString());
                    }
                    return claimsMap;
                })
                .flatMap(
                        claims -> jwtTokenIssuerClient
                                .createJWTToken(
                                        new CreateTokenRequestDto().privateClaims(claims).audience("npg")
                                                .duration(npgJwtKeyValidityTime)
                                ).doOnError(Mono::error)
                                .map(response -> Tuples.of(claims, response))
                )
                .flatMap(
                        response -> jwtTokenIssuerClient
                                .createJWTToken(
                                        new CreateTokenRequestDto().privateClaims(response.getT1())
                                                .audience("ecommerce").duration(jwtEcommerceValidityTimeInSeconds)
                                )
                                .doOnError(Mono::error).map(
                                        res -> new NpgBuildData(
                                                response.getT1().get(JwtTokenUtils.ORDER_ID_CLAIM),
                                                response.getT2().getToken(),
                                                res.getToken()
                                        )
                                )
                );
    }

    private URI generateOutcomeUrl(
                                   String clientId,
                                   TransactionId transactionId,
                                   String sessionToken
    ) {
        final var touchPoint = switch (ClientId.valueOf(clientId)) {
            case IO -> ClientId.IO;
            default -> ClientId.CHECKOUT;
        };
        return UriComponentsBuilder
                .fromUriString(npgSessionUrlConfig.basePath().concat(npgSessionUrlConfig.outcomeSuffix()))
                // append query param to prevent caching
                .queryParam("t", Instant.now().toEpochMilli())
                .build(
                        Map.of(
                                "clientId",
                                touchPoint.toString(),
                                "transactionId",
                                transactionId.value(),
                                "sessionToken",
                                sessionToken
                        )
                );
    }
}
