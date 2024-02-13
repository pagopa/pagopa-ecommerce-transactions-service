package it.pagopa.transactions.commands.handlers;

import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.client.NpgClient;
import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldsDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.StateResponseDto;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.generated.transactions.server.model.ApmAuthRequestDetailsDto;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlRequestDto;
import it.pagopa.generated.transactions.server.model.CardsAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.RedirectionAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.generated.transactions.server.model.WalletAuthRequestDetailsDto;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.repositories.TransactionCacheInfo;
import it.pagopa.transactions.repositories.TransactionTemplateWrapper;
import it.pagopa.transactions.repositories.WalletPaymentInfo;
import it.pagopa.transactions.utils.LogoMappingUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuple4;
import reactor.util.function.Tuples;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Slf4j
public abstract class TransactionRequestAuthorizationHandlerCommon
        implements CommandHandler<TransactionRequestAuthorizationCommand, Mono<RequestAuthorizationResponseDto>> {
    private static final String CHECKOUT_GDI_CHECK_PATH = "/gdi-check#gdiIframeUrl=";
    private static final String WALLET_GDI_CHECK_PATH = "/ecommerce-fe/gdi-check#gdiIframeUrl=";
    private static final String CHECKOUT_ESITO_PATH = "/esito";

    private final PaymentGatewayClient paymentGatewayClient;

    private final String checkoutBasePath;

    private final LogoMappingUtils logoMappingUtils;

    private final TransactionTemplateWrapper transactionTemplateWrapper;

    protected TransactionRequestAuthorizationHandlerCommon(
            PaymentGatewayClient paymentGatewayClient,
            String checkoutBasePath,
            LogoMappingUtils logoMappingUtils,
            TransactionTemplateWrapper transactionTemplateWrapper
    ) {
        this.paymentGatewayClient = paymentGatewayClient;
        this.checkoutBasePath = checkoutBasePath;
        this.logoMappingUtils = logoMappingUtils;
        this.transactionTemplateWrapper = transactionTemplateWrapper;
    }

    protected Mono<Tuple2<String, String>> postepayAuthRequestPipeline(AuthorizationRequestData authorizationData) {
        return Mono.just(authorizationData)
                .flatMap(
                        paymentGatewayClient::requestPostepayAuthorization
                )
                .map(
                        postePayAuthResponseEntityDto -> Tuples.of(
                                postePayAuthResponseEntityDto.getRequestId(),
                                postePayAuthResponseEntityDto.getUrlRedirect()
                        )
                );
    }

    protected Mono<Tuple2<String, String>> xpayAuthRequestPipeline(AuthorizationRequestData authorizationData) {
        return Mono.just(authorizationData)
                .flatMap(
                        paymentGatewayClient::requestXPayAuthorization
                )
                .map(
                        xPayAuthResponseEntityDto -> Tuples.of(
                                xPayAuthResponseEntityDto.getRequestId(),
                                xPayAuthResponseEntityDto.getUrlRedirect()

                        )
                );
    }

    protected Mono<Tuple2<String, String>> vposAuthRequestPipeline(AuthorizationRequestData authorizationData) {
        return Mono.just(authorizationData)
                .flatMap(
                        paymentGatewayClient::requestCreditCardAuthorization
                )
                .map(
                        creditCardAuthResponseDto -> Tuples.of(
                                creditCardAuthResponseDto.getRequestId(),
                                creditCardAuthResponseDto.getUrlRedirect()
                        )
                );
    }

    protected Mono<Tuple4<String, String, Optional<String>, Optional<String>>> npgAuthRequestPipeline(
            AuthorizationRequestData authorizationData, String correlationId
    ) {
        return Mono.just(authorizationData).flatMap(authData -> switch (authData.authDetails()) {
            case CardsAuthRequestDetailsDto cards -> invokeNpgConfirmPayment(authorizationData, cards
                    .getOrderId(), correlationId,false).map(confirmPaymentResponse -> Tuples.of(confirmPaymentResponse.getT1(), confirmPaymentResponse.getT2(), confirmPaymentResponse.getT3(), Optional.empty()));
            case WalletAuthRequestDetailsDto ignored -> {
                NpgClient.PaymentMethod npgPaymentMethod = NpgClient.PaymentMethod.fromServiceName(authorizationData.paymentMethodName());
                if (npgPaymentMethod.equals(NpgClient.PaymentMethod.CARDS)) {
                    yield walletNpgCardsPaymentFlow(authorizationData, correlationId);
                } else {
                    yield walletNpgApmPaymentFlow(authorizationData, correlationId,true);
                }
            }
            case ApmAuthRequestDetailsDto ignored ->  walletNpgApmPaymentFlow(authorizationData,correlationId, false);
            default -> Mono.empty();
        });

    }

    /**
     * Perform NPG payment flow with a card wallet. Payment flow is composed of two
     * requests made to NPG: 1) order/build 2) confirmPayment
     *
     * @param authorizationData the authorization requested data
     * @return a tuple of orderId, return url, confirm payment response session id
     *         and order/build session id
     */
    private Mono<Tuple4<String, String, Optional<String>, Optional<String>>> walletNpgCardsPaymentFlow(
                                                                                                       AuthorizationRequestData authorizationData,
                                                                                                       String correlationId
    ) {
        return paymentGatewayClient.requestNpgBuildSession(authorizationData, correlationId, true)
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
                                        authorizationData.authDetails()
                                ),
                                orderIdAndFieldsDto.getT1(),
                                correlationId,
                                true
                        )
                                .map(
                                        confirmPaymentResponse -> Tuples.of(
                                                confirmPaymentResponse.getT1(),
                                                confirmPaymentResponse.getT2(),
                                                confirmPaymentResponse.getT3(),
                                                Optional.of(orderIdAndFieldsDto.getT2().getSessionId())
                                        )
                                )
                );
    }

    /**
     * Perform NPG payment flow with an apm wallet (PayPal etx). Payment flow is
     * composed of one request made to NPG: 1) order/build (with PSP selected api
     * key)
     *
     * @param authorizationData the authorization requested data
     * @return a tuple of orderId, return url, confirm payment response session id
     *         (empty) and order/build session id
     */
    private Mono<Tuple4<String, String, Optional<String>, Optional<String>>> walletNpgApmPaymentFlow(
                                                                                                     AuthorizationRequestData authorizationData,
                                                                                                     String correlationId,
                                                                                                     boolean isWalletPayment
    ) {
        return paymentGatewayClient.requestNpgBuildApmPayment(authorizationData, correlationId, isWalletPayment)
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
                        orderIdAndFieldsDto -> Tuples.of(
                                orderIdAndFieldsDto.getT1(), // orderId
                                // safe here, return url is checked in above filter
                                orderIdAndFieldsDto.getT2().getUrl(), // redirect url
                                Optional.empty(), // confirm session id
                                // safe here, sessionId is checked in requestNpgBuildSession method
                                Optional.of(orderIdAndFieldsDto.getT2().getSessionId())// order/build session id
                        )
                );
    }

    /**
     * @param authorizationData authorization data
     * @param orderId           order id to be used for confirm payment
     * @param isWalletPayment   boolean flag for distinguish payment requests coming
     *                          from wallet or guest flows
     * @return a tuple containing order id, return url and confirm payment session
     *         id
     */
    private Mono<Tuple3<String, String, Optional<String>>> invokeNpgConfirmPayment(
                                                                                   AuthorizationRequestData authorizationData,
                                                                                   String orderId,
                                                                                   String correlationId,
                                                                                   boolean isWalletPayment

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
                                    return Mono.just(
                                            Tuples.of(
                                                    orderId,
                                                    switch (npgResponse.getState()) {
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

                                            StringBuilder gdiCheckPathWithFragment = isWalletPayment
                                                    ? new StringBuilder(
                                                            WALLET_GDI_CHECK_PATH
                                                    ).append(base64redirectionUrl).append("&clientId=IO")
                                                            .append("&transactionId=")
                                                            .append(authorizationData.transactionId().value())
                                                    : new StringBuilder(CHECKOUT_GDI_CHECK_PATH)
                                                            .append(base64redirectionUrl);

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
                                        case PAYMENT_COMPLETE -> URI.create(checkoutBasePath)
                                                .resolve(CHECKOUT_ESITO_PATH)
                                                .toString();
                                        default -> throw new BadGatewayException(
                                                "Invalid NPG confirm payment state response: " + npgResponse.getState(),
                                                HttpStatus.BAD_GATEWAY
                                        );
                                    },
                                                    confirmPaymentSessionId
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
        return logoMappingUtils.getLogo(authorizationRequestData);
    }

    /**
     * Redirection authorization pipeline
     *
     * @param authorizationData authorization data
     * @param touchpoint        touchpoint that initiated the transaction
     * @return a tuple of redirection url, psp authorization id and authorization
     *         timeout
     */
    protected Mono<Tuple3<String, String, Optional<Integer>>> redirectionAuthRequestPipeline(
                                                                                             AuthorizationRequestData authorizationData,
                                                                                             RedirectUrlRequestDto.TouchpointEnum touchpoint

    ) {
        return Mono.just(authorizationData)
                .filter(authData -> authorizationData.authDetails() instanceof RedirectionAuthRequestDetailsDto)
                .flatMap(
                        details -> paymentGatewayClient.requestRedirectUrlAuthorization(details, touchpoint)
                )
                .map(
                        redirectUrlResponseDto -> Tuples.of(
                                redirectUrlResponseDto.getIdPSPTransaction(),
                                redirectUrlResponseDto.getUrl(),
                                Optional.ofNullable(redirectUrlResponseDto.getTimeout())
                        )
                );
    }
}
