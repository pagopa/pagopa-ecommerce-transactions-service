package it.pagopa.transactions.client;

import com.azure.cosmos.implementation.InternalServerErrorException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.client.NpgClient;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.domain.Claims;
import it.pagopa.ecommerce.commons.exceptions.NpgApiKeyMissingPspRequestedException;
import it.pagopa.ecommerce.commons.exceptions.NpgResponseException;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldsDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.StateResponseDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.WorkflowStateDto;
import it.pagopa.ecommerce.commons.utils.JwtTokenUtils;
import it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig;
import it.pagopa.ecommerce.commons.utils.UniqueIdUtils;
import it.pagopa.generated.ecommerce.gateway.v1.api.PostePayInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.api.VposInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.api.XPayInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.dto.*;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlRequestDto;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlResponseDto;
import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.CardsAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.WalletAuthRequestDetailsDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.configurations.NpgSessionUrlConfig;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.exceptions.GatewayTimeoutException;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
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
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Component
@Slf4j
public class PaymentGatewayClient {

    private final PostePayInternalApi postePayInternalApi;

    private final XPayInternalApi paymentTransactionGatewayXPayWebClient;

    private final VposInternalApi creditCardInternalApiClient;

    private final ObjectMapper objectMapper;

    private final UUIDUtils uuidUtils;

    private final ConfidentialMailUtils confidentialMailUtils;

    private final NpgClient npgClient;

    private final NpgSessionUrlConfig npgSessionUrlConfig;

    private final NpgPspApiKeysConfig npgPspApiKeysConfig;
    private final UniqueIdUtils uniqueIdUtils;
    private final String npgDefaultApiKey;
    private final SecretKey npgNotificationSigningKey;
    private final int npgJwtKeyValidityTime;

    private final CheckoutRedirectClientBuilder checkoutRedirectClientBuilder;

    @Autowired
    public PaymentGatewayClient(
            @Qualifier("paymentTransactionGatewayPostepayWebClient") PostePayInternalApi postePayInternalApi,
            @Qualifier("paymentTransactionGatewayXPayWebClient") XPayInternalApi paymentTransactionGatewayXPayWebClient,
            @Qualifier("creditCardInternalApiClient") VposInternalApi creditCardInternalApiClient,
            ObjectMapper objectMapper,
            UUIDUtils uuidUtils,
            ConfidentialMailUtils confidentialMailUtils,
            NpgClient npgClient,
            NpgPspApiKeysConfig npgPspApiKeysConfig,
            NpgSessionUrlConfig npgSessionUrlConfig,
            UniqueIdUtils uniqueIdUtils,
            @Value("${npg.client.apiKey}") String npgDefaultApiKey,
            SecretKey npgNotificationSigningKey,
            @Value("${npg.notification.jwt.validity.time}") int npgJwtKeyValidityTime,

            CheckoutRedirectClientBuilder checkoutRedirectClientBuilder
    ) {
        this.postePayInternalApi = postePayInternalApi;
        this.paymentTransactionGatewayXPayWebClient = paymentTransactionGatewayXPayWebClient;
        this.creditCardInternalApiClient = creditCardInternalApiClient;
        this.objectMapper = objectMapper;
        this.uuidUtils = uuidUtils;
        this.confidentialMailUtils = confidentialMailUtils;
        this.npgClient = npgClient;
        this.npgPspApiKeysConfig = npgPspApiKeysConfig;
        this.npgSessionUrlConfig = npgSessionUrlConfig;
        this.uniqueIdUtils = uniqueIdUtils;
        this.npgDefaultApiKey = npgDefaultApiKey;
        this.npgNotificationSigningKey = npgNotificationSigningKey;
        this.npgJwtKeyValidityTime = npgJwtKeyValidityTime;
        this.checkoutRedirectClientBuilder = checkoutRedirectClientBuilder;
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

    public Mono<Tuple2<String, FieldsDto>> requestNpgBuildSession(AuthorizationRequestData authorizationData) {
        return requestNpgBuildSession(authorizationData, false);
    }

    public Mono<Tuple2<String, FieldsDto>> requestNpgBuildApmPayment(AuthorizationRequestData authorizationData) {
        return requestNpgBuildSession(authorizationData, true);
    }

    private Mono<Tuple2<String, FieldsDto>> requestNpgBuildSession(
                                                                   AuthorizationRequestData authorizationData,
                                                                   boolean isApmPayment
    ) {
        WorkflowStateDto expectedResponseState = isApmPayment ? WorkflowStateDto.REDIRECTED_TO_EXTERNAL_DOMAIN
                : WorkflowStateDto.READY_FOR_PAYMENT;
        return uniqueIdUtils.generateUniqueId()
                .zipWhen(
                        orderId -> new JwtTokenUtils()
                                .generateToken(
                                        npgNotificationSigningKey,
                                        npgJwtKeyValidityTime,
                                        new Claims(
                                                authorizationData.transactionId(),
                                                orderId,
                                                authorizationData.paymentInstrumentId()
                                        )
                                ).fold(
                                        Mono::error,
                                        Mono::just
                                )
                )
                .flatMap(
                        orderIdJwtToken -> {
                            String orderId = orderIdJwtToken.getT1();
                            String jwtToken = orderIdJwtToken.getT2();
                            UUID correlationId = UUID.randomUUID();
                            URI returnUrlBasePath = URI.create(npgSessionUrlConfig.basePath());
                            URI outcomeResultUrl = UriComponentsBuilder.fromUriString(
                                    returnUrlBasePath.resolve(npgSessionUrlConfig.outcomeSuffix()).toString()
                                            .concat("#clientId=IO&transactionId=")
                                            .concat(authorizationData.transactionId().value())
                            ).build().toUri();
                            URI merchantUrl = returnUrlBasePath;
                            URI cancelUrl = returnUrlBasePath.resolve(npgSessionUrlConfig.cancelSuffix());

                            URI notificationUrl = UriComponentsBuilder
                                    .fromHttpUrl(npgSessionUrlConfig.notificationUrl())
                                    .build(
                                            Map.of(
                                                    "orderId",
                                                    orderId,
                                                    "sessionToken",
                                                    jwtToken
                                            )
                                    );
                            /*
                             * FIXME: here we are using the same api key used for CARDS but they have to
                             * been differentiated for each payment methods. This issue is tracked with Jira
                             * task CHK-2265
                             */
                            Either<NpgApiKeyMissingPspRequestedException, String> buildApiKey = isApmPayment
                                    ? npgPspApiKeysConfig.get(authorizationData.pspId())
                                    : Either.right(npgDefaultApiKey);
                            return buildApiKey.fold(
                                    Mono::error,
                                    apiKey -> {
                                        if (isApmPayment) {
                                            return npgClient.buildFormForPayment(
                                                    correlationId,
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
                                                    ),
                                                    authorizationData.paymentNotices().stream()
                                                            .mapToInt(
                                                                    paymentNotice -> paymentNotice.transactionAmount()
                                                                            .value()
                                                            ).sum()
                                                            + authorizationData.fee()
                                            ).map(fieldsDto -> Tuples.of(orderId, fieldsDto));
                                        } else {
                                            return npgClient.buildForm(
                                                    correlationId,
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
                    final UUID correlationId = UUID.randomUUID();
                    final var pspNpgApiKey = npgPspApiKeysConfig.get(authorizationData.pspId());
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
                        case BAD_REQUEST -> new AlreadyProcessedException(
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

    /**
     * Perform authorization request with PSP retrieving redirection URL
     *
     * @param authorizationData authorization data
     * @return RedirectUrlResponseDto response bean
     */
    public Mono<RedirectUrlResponseDto> requestRedirectUrlAuthorization(AuthorizationRequestData authorizationData) {
        return Mono
                .just(checkoutRedirectClientBuilder.getApiClientForPsp(authorizationData.pspId()))
                .flatMap(
                        clientEither -> clientEither.fold(
                                Mono::error,
                                client -> client
                                        .retrieveRedirectUrl(
                                                new RedirectUrlRequestDto()
                                                        .paymentMethod(
                                                                RedirectUrlRequestDto.PaymentMethodEnum.BANK_ACCOUNT
                                                        )
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
                                                                        .map(p -> p.transactionDescription().value())
                                                                        .orElseThrow()
                                                        )
                                                        .urlBack(
                                                                UriComponentsBuilder
                                                                        .fromUriString(
                                                                                // url back to be checked with f.e.
                                                                                URI
                                                                                        .create(
                                                                                                npgSessionUrlConfig
                                                                                                        .basePath()
                                                                                        )
                                                                                        .resolve(
                                                                                                npgSessionUrlConfig
                                                                                                        .outcomeSuffix()
                                                                                        ).toString()
                                                                                        .concat(
                                                                                                "#clientId=REDIRECT&transactionId="
                                                                                        )
                                                                                        .concat(
                                                                                                authorizationData
                                                                                                        .transactionId()
                                                                                                        .value()
                                                                                        )
                                                                        ).build()
                                                                        .toUri()
                                                        )
                                                        // TODO what field use for PA NAME?
                                                        .paName(null)// optional
                                                        // TODO what is this field?
                                                        .idPaymentMethod(null)// optional
                                        ).onErrorMap(
                                                WebClientResponseException.class,
                                                exception -> {
                                                    String pspId = authorizationData.pspId();
                                                    HttpStatus httpStatus = exception.getStatusCode();
                                                    log.error(
                                                            "Error communicating with PSP: [{}] to retrieve redirection URL. Received HTTP status code: {}",
                                                            pspId,
                                                            httpStatus
                                                    );
                                                    return switch (exception.getStatusCode()) {
                                                        case BAD_REQUEST, UNAUTHORIZED -> new AlreadyProcessedException(
                                                                authorizationData.transactionId()
                                                        );
                                                        default -> new BadGatewayException(
                                                                "KO performing redirection URL api call for PSP: [%s]"
                                                                        .formatted(pspId),
                                                                exception.getStatusCode()
                                                        );
                                                    };
                                                }
                                        )
                        )
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
}
