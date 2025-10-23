package it.pagopa.transactions.commands.handlers;

import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.client.JwtIssuerClient;
import it.pagopa.ecommerce.commons.client.NpgClient;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.exceptions.JwtIssuerClientException;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenRequestDto;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenResponseDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldsDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.StateResponseDto;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlRequestDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.JwtTokenIssuerClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationOutput;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.repositories.TransactionCacheInfo;
import it.pagopa.transactions.repositories.TransactionTemplateWrapper;
import it.pagopa.transactions.repositories.WalletPaymentInfo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
public abstract class TransactionRequestAuthorizationHandlerCommon
        implements CommandHandler<TransactionRequestAuthorizationCommand, Mono<RequestAuthorizationResponseDto>> {
    private static final String WALLET_GDI_CHECK_PATH = "/ecommerce-fe/gdi-check";
    private static final String WALLET_ESITO_PATH = "/ecommerce-fe/esito";

    private final PaymentGatewayClient paymentGatewayClient;

    private final String checkoutBasePath;
    private final String checkoutNpgGdiUrl;
    private final String checkoutOutcomeUrl;
    private final String paymentWalletNpgGdiUrl;

    private final TransactionTemplateWrapper transactionTemplateWrapper;

    private final JwtTokenIssuerClient jwtTokenIssuerClient;

    private final int jwtWebviewValidityTimeInSeconds;

    protected TransactionRequestAuthorizationHandlerCommon(
            PaymentGatewayClient paymentGatewayClient,
            String checkoutBasePath,
            String checkoutNpgGdiUrl,
            String checkoutOutcomeUrl,
            TransactionTemplateWrapper transactionTemplateWrapper,
            JwtTokenIssuerClient jwtTokenIssuerClient,
            int jwtWebviewValidityTimeInSeconds,
            String paymentWalletNpgGdiUrl
    ) {
        this.paymentGatewayClient = paymentGatewayClient;
        this.checkoutBasePath = checkoutBasePath;
        this.checkoutNpgGdiUrl = checkoutNpgGdiUrl;
        this.checkoutOutcomeUrl = checkoutOutcomeUrl;
        this.transactionTemplateWrapper = transactionTemplateWrapper;
        this.jwtTokenIssuerClient = jwtTokenIssuerClient;
        this.jwtWebviewValidityTimeInSeconds = jwtWebviewValidityTimeInSeconds;
        this.paymentWalletNpgGdiUrl = paymentWalletNpgGdiUrl;
    }

    /**
     * NPG authorization pipeline
     *
     * @param authorizationData the authorization requested data
     * @return the authorization output data containing authorization id, redirect URL, session id, confirm payment session id (if present)
     */
    protected Mono<AuthorizationOutput> npgAuthRequestPipeline(
            AuthorizationRequestData authorizationData, String correlationId, String clientId, String lang, UUID userId
    ) {
        return Mono.just(authorizationData).flatMap(authData -> switch (authData.authDetails()) {
            case CardsAuthRequestDetailsDto cards -> invokeNpgConfirmPayment(authorizationData, cards
                    .getOrderId(), correlationId, clientId, Optional.ofNullable(userId))
            ;
            case WalletAuthRequestDetailsDto ignored -> {
                NpgClient.PaymentMethod npgPaymentMethod = NpgClient.PaymentMethod.valueOf(authorizationData.paymentMethodName());
                if (npgPaymentMethod.equals(NpgClient.PaymentMethod.CARDS)) {
                    yield walletNpgCardsPaymentFlow(authorizationData, correlationId, clientId, lang, userId);
                } else {
                    yield npgApmPaymentFlow(authorizationData, correlationId, true, clientId, lang, userId);
                }
            }
            case ApmAuthRequestDetailsDto ignored ->
                    npgApmPaymentFlow(authorizationData, correlationId, false, clientId, lang, userId);
            default -> Mono.empty();
        });

    }

    /**
     * Perform NPG payment flow with a card wallet. Payment flow is composed of two
     * requests made to NPG: 1) order/build 2) confirmPayment
     *
     * @param authorizationData the authorization requested data
     * @return the authorization output data with orderId, return url, confirm
     *         payment response session id and order/build session id
     */
    private Mono<AuthorizationOutput> walletNpgCardsPaymentFlow(
                                                                AuthorizationRequestData authorizationData,
                                                                String correlationId,
                                                                String clientId,
                                                                String lang,
                                                                UUID userId
    ) {
        return Mono.justOrEmpty(authorizationData.contextualOnboardDetails())
                .doOnNext(ignored -> log.info("Contextual Onboard Authorization"))
                .map(details -> Tuples.of(details.orderId(), authorizationData.sessionId().orElseThrow()))
                .switchIfEmpty(
                        paymentGatewayClient
                                .requestNpgBuildSession(
                                        authorizationData,
                                        correlationId,
                                        true,
                                        clientId,
                                        lang,
                                        userId
                                )
                                .flatMap(
                                        orderIdAndFields -> cacheTransaction(
                                                authorizationData,
                                                orderIdAndFields
                                        )
                                                .thenReturn(extractOrderIdAndSession(orderIdAndFields))
                                )

                )
                .flatMap(
                        orderIdAndSessionId -> invokeNpgConfirmPayment(
                                new AuthorizationRequestData(
                                        authorizationData.transactionId(),
                                        authorizationData.paymentNotices(),
                                        authorizationData.email(),
                                        authorizationData.fee(),
                                        authorizationData.paymentInstrumentId(),
                                        authorizationData.pspId(),
                                        authorizationData.paymentTypeCode(),
                                        authorizationData.brokerName(),
                                        authorizationData.pspChannelCode(),
                                        authorizationData.paymentMethodName(),
                                        authorizationData.paymentMethodDescription(),
                                        authorizationData.pspBusinessName(),
                                        authorizationData.pspOnUs(),
                                        authorizationData.paymentGatewayId(),
                                        Optional.of(orderIdAndSessionId.getT2()),
                                        authorizationData.contractId(),
                                        authorizationData.brand(),
                                        authorizationData.authDetails(),
                                        authorizationData.asset(),
                                        authorizationData.brandAssets(),
                                        authorizationData.idBundle(),
                                        authorizationData.contextualOnboardDetails()
                                ),
                                orderIdAndSessionId.getT1(),
                                correlationId,
                                clientId,
                                Optional.of(userId)
                        )
                                .map(
                                        authorizationOutput -> new AuthorizationOutput(
                                                authorizationOutput.authorizationId(),
                                                authorizationOutput.authorizationUrl(),
                                                Optional.of(orderIdAndSessionId.getT2()),
                                                authorizationOutput.npgConfirmSessionId(),
                                                authorizationOutput.authorizationTimeoutMillis()
                                        )
                                )
                );
    }

    private Mono<Void> cacheTransaction(
                                        AuthorizationRequestData authorizationData,
                                        Tuple2<String, FieldsDto> orderIdAndFields
    ) {
        return transactionTemplateWrapper.save(
                new TransactionCacheInfo(
                        authorizationData.transactionId(),
                        new WalletPaymentInfo(
                                orderIdAndFields.getT2().getSessionId(),
                                orderIdAndFields.getT2().getSecurityToken(),
                                orderIdAndFields.getT1()
                        )
                )
        ).then();
    }

    private Tuple2<String, String> extractOrderIdAndSession(
                                                            Tuple2<String, FieldsDto> orderIdAndFields
    ) {
        return Tuples.of(
                orderIdAndFields.getT1(),
                orderIdAndFields.getT2().getSessionId()
        );
    }

    /**
     * Perform NPG payment flow with an apm (PayPal etc) both saved in wallet or
     * not. Payment flow is composed of one request made to NPG: 1) order/build
     * (with PSP selected api key)
     *
     * @param authorizationData the authorization requested data
     * @return the authorization output data with confirm payment response session
     *         id (empty) and order/build session id
     */
    private Mono<AuthorizationOutput> npgApmPaymentFlow(
                                                        AuthorizationRequestData authorizationData,
                                                        String correlationId,
                                                        boolean isWalletPayment,
                                                        String clientId,
                                                        String lang,
                                                        UUID userId
    ) {
        return paymentGatewayClient
                .requestNpgBuildApmPayment(authorizationData, correlationId, isWalletPayment, clientId, lang, userId)
                .filter(orderIdAndFieldsDto -> {
                    String returnUrl = orderIdAndFieldsDto.getT2().getUrl();
                    boolean isReturnUrlValued = returnUrl != null && !returnUrl.isEmpty();
                    if (!isReturnUrlValued) {
                        log.error(
                                "NPG order/build APM response error: return url is not valid: [{}]. The payment was performed using wallet: [{}]",
                                returnUrl,
                                isWalletPayment
                        );
                    }
                    return isReturnUrlValued;
                })
                .switchIfEmpty(
                        Mono.error(
                                new BadGatewayException(
                                        "NPG order/build response is not valid, missing return url",
                                        HttpStatus.BAD_GATEWAY
                                )
                        )
                )
                .flatMap(
                        orderIdAndFieldsDto -> transactionTemplateWrapper.save(
                                new TransactionCacheInfo(
                                        authorizationData.transactionId(),
                                        new WalletPaymentInfo(
                                                // safe here: session id and security token presence are checked in
                                                // requestNpgBuildSession method
                                                orderIdAndFieldsDto.getT2().getSessionId(),
                                                orderIdAndFieldsDto.getT2().getSecurityToken(),
                                                orderIdAndFieldsDto.getT1()
                                        )
                                )
                        ).thenReturn(orderIdAndFieldsDto)
                ).map(
                        /*
                         * For APM payments eCommerce performs a single order/build api call to NPG.
                         * Since no confirmPayment api call is performed here there will be no
                         * confirmSessionId to be valued (the 3rd parameter, see above javadoc for
                         * returned parameter order) and then the hardcoded Optional.empty() confirm
                         * session id
                         */
                        orderIdAndFieldsDto -> new AuthorizationOutput(
                                orderIdAndFieldsDto.getT1(),
                                orderIdAndFieldsDto.getT2().getUrl(),
                                Optional.ofNullable(orderIdAndFieldsDto.getT2().getSessionId()),
                                Optional.empty(),
                                Optional.empty()
                        )
                );
    }

    /**
     * @param authorizationData authorization data
     * @param orderId           order id to be used for confirm payment
     * @param clientId          clientId from which request is coming
     * @return the authorization output data containing order id, return url and
     *         confirm payment session id
     */
    private Mono<AuthorizationOutput> invokeNpgConfirmPayment(
                                                              AuthorizationRequestData authorizationData,
                                                              String orderId,
                                                              String correlationId,
                                                              String clientId,
                                                              Optional<UUID> userId

    ) {
        return confirmPayment(authorizationData, correlationId)
                .flatMap(
                        npgCardsResponseDto -> npgCardsResponseDto.fold(
                                Mono::error,
                                npgResponse -> {
                                    Optional<String> confirmPaymentSessionId = Optional
                                            .ofNullable(npgResponse.getFieldSet())
                                            .map(FieldsDto::getSessionId);
                                    log.info("NGP auth completed session id: {}", confirmPaymentSessionId);
                                    Mono<String> authUrl = switch (npgResponse.getState()) {
                                        case GDI_VERIFICATION -> {
                                            if (npgResponse.getFieldSet() == null
                                                    || npgResponse.getFieldSet().getFields() == null
                                                    || npgResponse.getFieldSet().getFields().isEmpty()) {
                                                throw new BadGatewayException(
                                                        "Invalid NPG response for state %s, no fieldSet.field received, expected 1"
                                                                .formatted(npgResponse.getState()),
                                                        HttpStatus.BAD_GATEWAY
                                                );
                                            }
                                            String redirectionUrl = npgResponse.getFieldSet().getFields().get(0)
                                                    .getSrc();
                                            if (redirectionUrl == null) {
                                                throw new BadGatewayException(
                                                        "Invalid NPG response for state %s, fieldSet.field[0].src is null"
                                                                .formatted(npgResponse.getState()),
                                                        HttpStatus.BAD_GATEWAY
                                                );
                                            }

                                            String base64redirectionUrl = Base64.encodeBase64URLSafeString(
                                                    redirectionUrl
                                                            .getBytes(
                                                                    StandardCharsets.UTF_8
                                                            )
                                            );
                                            boolean isContextualOnboarding = authorizationData
                                                    .isWalletPaymentWithContextualOnboarding();
                                            Mono<URI> gdiCheckPathWithFragment = clientId.equals(
                                                    Transaction.ClientId.IO.toString()
                                            ) ? generateWebviewToken(
                                                    authorizationData.transactionId(),
                                                    authorizationData.paymentInstrumentId(),
                                                    orderId,
                                                    userId.orElseThrow()
                                            ).map(
                                                    webViewSessionToken -> encodeURIWithFragmentParams(
                                                            URI.create(WALLET_GDI_CHECK_PATH),
                                                            List.of(
                                                                    Tuples.of("gdiIframeUrl", base64redirectionUrl),
                                                                    Tuples.of("clientId", clientId),
                                                                    Tuples.of(
                                                                            "transactionId",
                                                                            authorizationData.transactionId().value()
                                                                    ),
                                                                    Tuples.of("sessionToken", webViewSessionToken)
                                                            )
                                                    )
                                            )
                                                    : Mono.just(
                                                            encodeURIWithFragmentParams(
                                                                    URI.create(
                                                                            isContextualOnboarding
                                                                                    ? this.paymentWalletNpgGdiUrl
                                                                                    : this.checkoutNpgGdiUrl
                                                                    ),
                                                                    List.of(
                                                                            Tuples.of(
                                                                                    "gdiIframeUrl",
                                                                                    base64redirectionUrl
                                                                            )
                                                                    )
                                                            )
                                                    );

                                            yield gdiCheckPathWithFragment.map(
                                                    path -> URI.create(checkoutBasePath)
                                                            .resolve(path).toString()
                                            );

                                        }
                                        case REDIRECTED_TO_EXTERNAL_DOMAIN -> {
                                            if (npgResponse.getUrl() == null) {
                                                throw new BadGatewayException(
                                                        "Invalid NPG response for state %s, response.url is null"
                                                                .formatted(npgResponse.getState()),
                                                        HttpStatus.BAD_GATEWAY
                                                );
                                            }
                                            yield Mono.just(npgResponse.getUrl());
                                        }
                                        case PAYMENT_COMPLETE -> clientId.equals(
                                                Transaction.ClientId.IO.toString()
                                        ) ? generateWebviewToken(
                                                authorizationData.transactionId(),
                                                authorizationData.paymentInstrumentId(),
                                                orderId,
                                                userId.orElseThrow()
                                        ).map(
                                                webViewSessionToken -> encodeURIWithFragmentParams(
                                                        URI.create(checkoutBasePath).resolve(WALLET_ESITO_PATH),
                                                        List.of(
                                                                Tuples.of("clientId", clientId),
                                                                Tuples.of(
                                                                        "transactionId",
                                                                        authorizationData.transactionId().value()
                                                                ),
                                                                Tuples.of("sessionToken", webViewSessionToken)
                                                        )
                                                ).toString()
                                        )
                                                : Mono.just(
                                                        appendTimestampAsQueryParam(
                                                                URI.create(checkoutBasePath)
                                                                        .resolve(checkoutOutcomeUrl)
                                                        ).toString()
                                                );
                                        default -> throw new BadGatewayException(
                                                "Invalid NPG confirm payment state response: " + npgResponse.getState(),
                                                HttpStatus.BAD_GATEWAY
                                        );
                                    };

                                    return authUrl.map(
                                            url -> new AuthorizationOutput(
                                                    orderId,
                                                    url,
                                                    authorizationData.sessionId(),
                                                    confirmPaymentSessionId,
                                                    Optional.empty()
                                            )
                                    );
                                }
                        )
                );
    }

    /**
     * Perform NPG confirm payment api call. This method performs basic response
     * validation checking mandatory response fields such as state
     *
     * @param authorizationData - the authorization requested data
     * @return Either valued with response, if valid, or exception for invalid
     *         response received
     */
    private Mono<Either<BadGatewayException, StateResponseDto>> confirmPayment(
                                                                               AuthorizationRequestData authorizationData,
                                                                               String correlationId
    ) {
        return paymentGatewayClient.requestNpgCardsAuthorization(authorizationData, correlationId)
                .map(npgStateResponse -> {
                    if (npgStateResponse.getState() == null) {
                        return Either.left(
                                new BadGatewayException(
                                        "Invalid NPG confirm payment, state response null!",
                                        HttpStatus.BAD_GATEWAY
                                )
                        );
                    }
                    return Either.right(npgStateResponse);
                });
    }

    protected URI getLogo(AuthorizationRequestData authorizationRequestData) {
        String paymentTypeCode = authorizationRequestData.paymentTypeCode();
        String brand = authorizationRequestData.brand();
        String asset = authorizationRequestData.asset();
        Optional<Map<String, String>> brandAssets = authorizationRequestData.brandAssets();
        String logo;
        if (paymentTypeCode.equals("CP") && brand != null) {
            logo = brandAssets.map(brandLogos -> brandLogos.get(brand)).orElse(asset);
        } else {
            logo = asset;
        }
        log.debug(
                "Payment method with payment type code: [{}], brand: [{}]. Received asset: [{}], brandAssets: [{}] mapped to logo -> [{}]",
                paymentTypeCode,
                brand,
                asset,
                brandAssets,
                logo
        );
        return URI.create(logo);
    }

    /**
     * Redirection authorization pipeline
     *
     * @param authorizationData authorization data
     * @param touchpoint        touchpoint that initiated the transaction
     * @return the authorization output data with redirection url, psp authorization
     *         id and authorization timeout
     */
    protected Mono<AuthorizationOutput> redirectionAuthRequestPipeline(
                                                                       AuthorizationRequestData authorizationData,
                                                                       RedirectUrlRequestDto.TouchpointEnum touchpoint,
                                                                       UUID userId

    ) {
        return Mono.just(authorizationData)
                .filter(authData -> authorizationData.authDetails() instanceof RedirectionAuthRequestDetailsDto)
                .flatMap(
                        details -> paymentGatewayClient.requestRedirectUrlAuthorization(details, touchpoint, userId)
                )
                .map(
                        redirectUrlResponseDto -> new AuthorizationOutput(
                                redirectUrlResponseDto.getIdPSPTransaction(),
                                redirectUrlResponseDto.getUrl(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.ofNullable(redirectUrlResponseDto.getTimeout())
                        )
                );
    }

    private URI encodeURIWithFragmentParams(
                                            URI uri,
                                            List<Tuple2<String, String>> fragmentParameters
    ) {
        String fragment = fragmentParameters
                .stream()
                .map(entry -> String.format("%s=%s", entry.getT1(), entry.getT2()))
                .collect(Collectors.joining("&"));

        return UriComponentsBuilder
                .fromUri(uri)
                // append query param to prevent caching
                .queryParam("t", Instant.now().toEpochMilli())
                .fragment(fragment).build().toUri();
    }

    // append query param to prevent caching
    private URI appendTimestampAsQueryParam(URI uri) {
        return UriComponentsBuilder.fromUri(uri).queryParam("t", Instant.now().toEpochMilli()).build().toUri();
    }

    private Mono<String> generateWebviewToken(
                                              TransactionId transactionId,
                                              String paymentInstrumentId,
                                              String orderId,
                                              UUID userId
    ) {
        return Mono.just(
                userId == null ? Map.of(
                        JwtIssuerClient.TRANSACTION_ID_CLAIM,
                        transactionId.value(),
                        JwtIssuerClient.PAYMENT_METHOD_ID_CLAIM,
                        paymentInstrumentId,
                        JwtIssuerClient.ORDER_ID_CLAIM,
                        orderId
                )
                        : Map.of(
                                JwtIssuerClient.TRANSACTION_ID_CLAIM,
                                transactionId.value(),
                                JwtIssuerClient.PAYMENT_METHOD_ID_CLAIM,
                                paymentInstrumentId,
                                JwtIssuerClient.ORDER_ID_CLAIM,
                                orderId,
                                JwtIssuerClient.USER_ID_CLAIM,
                                userId.toString()
                        )
        ).flatMap(
                claimsMap -> jwtTokenIssuerClient.createJWTToken(
                        new CreateTokenRequestDto()
                                .duration(jwtWebviewValidityTimeInSeconds)
                                .audience(JwtIssuerClient.ECOMMERCE_AUDIENCE)
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
}
