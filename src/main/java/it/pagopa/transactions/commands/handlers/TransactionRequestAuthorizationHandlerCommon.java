package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
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
import reactor.util.function.Tuples;

import java.net.URI;
import java.nio.charset.StandardCharsets;

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

    protected Mono<Tuple2<String, String>> npgAuthRequestPipeline(AuthorizationRequestData authorizationData) {
        return Mono.just(authorizationData)
                .flatMap(
                        paymentGatewayClient::requestNpgCardsAuthorization
                )
                .map(
                        npgCardsResponseDto -> Tuples.of(
                                "sessionId",
                                switch (npgCardsResponseDto.getState()) {
                                case GDI_VERIFICATION -> URI.create(checkoutBasePath)
                                        .resolve(
                                                CHECKOUT_GDI_CHECK_PATH + Base64.encodeBase64URLSafeString(
                                                        npgCardsResponseDto.getFieldSet().getFields().get(0).getSrc()
                                                                .getBytes(StandardCharsets.UTF_8)
                                                )
                                        ).toString();
                                case REDIRECTED_TO_EXTERNAL_DOMAIN -> npgCardsResponseDto.getUrl();
                                case PAYMENT_COMPLETE -> URI.create(checkoutBasePath).resolve(CHECKOUT_ESITO_PATH)
                                        .toString();
                                default -> throw new BadGatewayException(
                                        "Invalid NPG confirm payment state response: " + npgCardsResponseDto.getState(),
                                        HttpStatus.BAD_GATEWAY
                                );
                                }
                        )

                );
    }

    protected URI getLogo(AuthorizationRequestData authorizationRequestData) {
        return logoMappingUtils.getLogo(authorizationRequestData);
    }
}
