package it.pagopa.transactions.commands.handlers;

import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.client.NpgClient;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldsDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.StateResponseDto;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlRequestDto;
import it.pagopa.generated.transactions.server.model.*;
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
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
public abstract class TransactionRequestAuthorizationHandlerCommon
        implements CommandHandler<TransactionRequestAuthorizationCommand, Mono<RequestAuthorizationResponseDto>> {
    private static final String WALLET_GDI_CHECK_PATH = "/ecommerce-fe/gdi-check#gdiIframeUrl=";
    private static final String WALLET_ESITO_PATH = "/ecommerce-fe/esito";

    private final PaymentGatewayClient paymentGatewayClient;

    private final String checkoutBasePath;
    private final String checkoutNpgGdiUrl;
    private final String checkoutOutcomeUrl;

    private final TransactionTemplateWrapper transactionTemplateWrapper;

    protected TransactionRequestAuthorizationHandlerCommon(
            PaymentGatewayClient paymentGatewayClient,
            String checkoutBasePath,
            String checkoutNpgGdiUrl,
            String checkoutOutcomeUrl,
            TransactionTemplateWrapper transactionTemplateWrapper
    ) {
        this.paymentGatewayClient = paymentGatewayClient;
        this.checkoutBasePath = checkoutBasePath;
        this.checkoutNpgGdiUrl = checkoutNpgGdiUrl;
        this.checkoutOutcomeUrl = checkoutOutcomeUrl;
        this.transactionTemplateWrapper = transactionTemplateWrapper;
    }

    /**
     * XPAY authorization pipeline
     *
     * @param authorizationData the authorization requested data
     * @return the authorization output data containing authorization id and
     *         redirect URL
     */
    protected Mono<AuthorizationOutput> xpayAuthRequestPipeline(AuthorizationRequestData authorizationData) {
        return Mono.just(authorizationData)
                .flatMap(
                        paymentGatewayClient::requestXPayAuthorization
                )
                .map(
                        xPayAuthResponseEntityDto -> new AuthorizationOutput(
                                xPayAuthResponseEntityDto.getRequestId(),
                                xPayAuthResponseEntityDto.getUrlRedirect(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()
                        )
                );
    }

    /**
     * VPOS authorization pipeline
     *
     * @param authorizationData the authorization requested data
     * @return the authorization output data containing authorization id and
     *         redirect URL
     */
    protected Mono<AuthorizationOutput> vposAuthRequestPipeline(AuthorizationRequestData authorizationData) {
        return Mono.just(authorizationData)
                .flatMap(
                        paymentGatewayClient::requestCreditCardAuthorization
                )
                .map(
                        creditCardAuthResponseDto -> new AuthorizationOutput(
                                creditCardAuthResponseDto.getRequestId(),
                                creditCardAuthResponseDto.getUrlRedirect(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()
                        )
                );
    }

    /**
     * NPG authorization pipeline
     *
     * @param authorizationData the authorization requested data
     * @return the authorization output data containing authorization id, redirect URL, session id, confirm payment session id (if present)
     */
    protected Mono<AuthorizationOutput> npgAuthRequestPipeline(
            AuthorizationRequestData authorizationData, String correlationId, String clientId, UUID userId
    ) {
        return Mono.just(authorizationData).flatMap(authData -> switch (authData.authDetails()) {
            case CardsAuthRequestDetailsDto cards -> invokeNpgConfirmPayment(authorizationData, cards
                    .getOrderId(), correlationId, clientId)
            ;
            case WalletAuthRequestDetailsDto ignored -> {
                NpgClient.PaymentMethod npgPaymentMethod = NpgClient.PaymentMethod.fromServiceName(authorizationData.paymentMethodName());
                if (npgPaymentMethod.equals(NpgClient.PaymentMethod.CARDS)) {
                    yield walletNpgCardsPaymentFlow(authorizationData, correlationId, clientId, userId);
                } else {
                    yield npgApmPaymentFlow(authorizationData, correlationId, true, clientId, userId);
                }
            }
            case ApmAuthRequestDetailsDto ignored -> npgApmPaymentFlow(authorizationData, correlationId, false, clientId, userId);
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
                                                                UUID userId
    ) {
        return paymentGatewayClient.requestNpgBuildSession(authorizationData, correlationId, true, clientId, userId)
                .map(orderIdAndFieldsDto -> {
                    transactionTemplateWrapper.save(
                            new TransactionCacheInfo(
                                    authorizationData.transactionId(),
                                    new WalletPaymentInfo(
                                            orderIdAndFieldsDto.getT2().getSessionId(),
                                            orderIdAndFieldsDto.getT2().getSecurityToken(),
                                            orderIdAndFieldsDto.getT1()
                                    )
                            )
                    );
                    return orderIdAndFieldsDto;
                }
                )
                .flatMap(
                        orderIdAndFieldsDto -> invokeNpgConfirmPayment(
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
                                        Optional.of(orderIdAndFieldsDto.getT2().getSessionId()),
                                        authorizationData.contractId(),
                                        authorizationData.brand(),
                                        authorizationData.authDetails(),
                                        authorizationData.asset(),
                                        authorizationData.brandAssets()
                                ),
                                orderIdAndFieldsDto.getT1(),
                                correlationId,
                                clientId
                        )
                                .map(
                                        authorizationOutput -> new AuthorizationOutput(
                                                authorizationOutput.authorizationId(),
                                                authorizationOutput.authorizationUrl(),
                                                Optional.of(orderIdAndFieldsDto.getT2().getSessionId()),
                                                authorizationOutput.npgConfirmSessionId(),
                                                authorizationOutput.authorizationTimeoutMillis()
                                        )
                                )
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
                                                        UUID userId
    ) {
        return paymentGatewayClient
                .requestNpgBuildApmPayment(authorizationData, correlationId, isWalletPayment, clientId, userId)
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
                .map(orderIdAndFieldsDto -> {
                    transactionTemplateWrapper.save(
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
                    );
                    return orderIdAndFieldsDto;
                }
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
                                                              String clientId

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
                                    String authUrl = switch (npgResponse.getState()) {
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

                                            StringBuilder gdiCheckPathWithFragment = clientId.equals(
                                                    Transaction.ClientId.IO.toString()
                                            )
                                                    ? new StringBuilder(
                                                            WALLET_GDI_CHECK_PATH
                                                    ).append(base64redirectionUrl)
                                                            .append("&clientId=")
                                                            .append(clientId)
                                                            .append("&transactionId=")
                                                            .append(authorizationData.transactionId().value())
                                                    : new StringBuilder(formatGdiCheckUrl(base64redirectionUrl));

                                            yield URI.create(checkoutBasePath)
                                                    .resolve(
                                                            gdiCheckPathWithFragment.toString()
                                                    ).toString();

                                        }
                                        case REDIRECTED_TO_EXTERNAL_DOMAIN -> {
                                            if (npgResponse.getUrl() == null) {
                                                throw new BadGatewayException(
                                                        "Invalid NPG response for state %s, response.url is null"
                                                                .formatted(npgResponse.getState()),
                                                        HttpStatus.BAD_GATEWAY
                                                );
                                            }
                                            yield npgResponse.getUrl();
                                        }
                                        case PAYMENT_COMPLETE -> clientId.equals(Transaction.ClientId.IO.toString()) ?

                                                URI.create(checkoutBasePath)
                                                        .resolve(
                                                                new StringBuilder(
                                                                        WALLET_ESITO_PATH
                                                                ).append("#clientId=")
                                                                        .append(clientId)
                                                                        .append("&transactionId=")
                                                                        .append(
                                                                                authorizationData.transactionId()
                                                                                        .value()
                                                                        ).toString()
                                                        ).toString()

                                                : URI.create(checkoutBasePath)
                                                        .resolve(checkoutOutcomeUrl)
                                                        .toString();
                                        default -> throw new BadGatewayException(
                                                "Invalid NPG confirm payment state response: " + npgResponse.getState(),
                                                HttpStatus.BAD_GATEWAY
                                        );
                                    };
                                    return Mono.just(
                                            new AuthorizationOutput(
                                                    orderId,
                                                    authUrl,
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
                                                                       RedirectUrlRequestDto.TouchpointEnum touchpoint

    ) {
        return Mono.just(authorizationData)
                .filter(authData -> authorizationData.authDetails() instanceof RedirectionAuthRequestDetailsDto)
                .flatMap(
                        details -> paymentGatewayClient.requestRedirectUrlAuthorization(details, touchpoint)
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

    private String formatGdiCheckUrl(String iframeUrl) {
        return this.checkoutNpgGdiUrl.concat("#gdiIframeUrl=").concat(iframeUrl);
    }
}
