package it.pagopa.transactions.commands.handlers;

import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.FieldsDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.StateResponseDto;
import it.pagopa.ecommerce.commons.generated.npg.v1.dto.WorkflowStateDto;
import it.pagopa.generated.transactions.server.model.CardsAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.generated.transactions.server.model.WalletAuthRequestDetailsDto;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.utils.LogoMappingUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

@Slf4j
public abstract class TransactionRequestAuthorizationHandlerCommon
        implements CommandHandler<TransactionRequestAuthorizationCommand, Mono<RequestAuthorizationResponseDto>> {
    private static final String CHECKOUT_GDI_CHECK_PATH = "/gdi-check#gdiIframeUrl=";
    private static final String CHECKOUT_ESITO_PATH = "/esito";

    private final PaymentGatewayClient paymentGatewayClient;

    private final String checkoutBasePath;

    private final LogoMappingUtils logoMappingUtils;

    protected TransactionRequestAuthorizationHandlerCommon(
            PaymentGatewayClient paymentGatewayClient,
            String checkoutBasePath,
            LogoMappingUtils logoMappingUtils
    ) {
        this.paymentGatewayClient = paymentGatewayClient;
        this.checkoutBasePath = checkoutBasePath;
        this.logoMappingUtils = logoMappingUtils;
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

    protected Mono<Tuple3<String, String, Optional<String>>> npgAuthRequestPipeline(
                                                                                    AuthorizationRequestData authorizationData
    ) {
        return Mono.just(authorizationData).flatMap( authData -> switch (authData.authDetails()){
            case CardsAuthRequestDetailsDto ignored ->
                    invokeNpgConfirmPayment(authorizationData);
            case WalletAuthRequestDetailsDto ignored ->
                    paymentGatewayClient.requestNpgBuildSession(authorizationData)
                            .filter(fieldsDto -> Objects.equals(fieldsDto.getState(), WorkflowStateDto.READY_FOR_PAYMENT))
                            .switchIfEmpty(Mono.error(new BadGatewayException("Error while invoke NPG build session",HttpStatus.BAD_GATEWAY)))
                            .flatMap(fieldsDto-> invokeNpgConfirmPayment(authorizationData));


            default -> Mono.empty();
        });

    }

    private Mono<Tuple3<String, String, Optional<String>>> invokeNpgConfirmPayment(
                                                                                   AuthorizationRequestData authorizationData
    ) {
        return Mono.just(authorizationData)
                .flatMap(
                        this::confirmPayment
                )
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
                                                    // safe cast here, filter against authDetails performed into
                                                    // requestNpgCardsAuthorization method
                                                    ((CardsAuthRequestDetailsDto) authorizationData.authDetails())
                                                            .getOrderId(),
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
                                            yield URI.create(checkoutBasePath)
                                                    .resolve(
                                                            CHECKOUT_GDI_CHECK_PATH + Base64.encodeBase64URLSafeString(
                                                                    redirectionUrl
                                                                            .getBytes(StandardCharsets.UTF_8)
                                                            )
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
                                                                               AuthorizationRequestData authorizationData
    ) {
        return Mono.just(authorizationData)
                .flatMap(paymentGatewayClient::requestNpgCardsAuthorization)
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
}
