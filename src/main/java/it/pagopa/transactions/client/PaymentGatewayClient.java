package it.pagopa.transactions.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.pagopa.generated.ecommerce.gateway.v1.api.CreditCardInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.api.PostePayInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.api.XPayInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.dto.*;
import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.exceptions.GatewayTimeoutException;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Component
public class PaymentGatewayClient {
    @Autowired
    @Qualifier("paymentTransactionGatewayPostepayWebClient")
    PostePayInternalApi postePayInternalApi;

    @Autowired
    @Qualifier("paymentTransactionGatewayXPayWebClient")
    XPayInternalApi paymentTransactionGatewayXPayWebClient;

    @Autowired
    @Qualifier("creditCardInternalApiClient")
    CreditCardInternalApi creditCardInternalApiClient;

    @Autowired
    private ObjectMapper objectMapper;

    // TODO Handle multiple rptId

    public Mono<PostePayAuthResponseEntityDto> requestPostepayAuthorization(
                                                                            AuthorizationRequestData authorizationData
    ) {

        return Mono.just(authorizationData)
                .filter(authorizationRequestData -> "PPAY".equals(authorizationRequestData.paymentTypeCode()))
                .map(authorizationRequestData -> {
                    BigDecimal grandTotal = BigDecimal.valueOf(
                            ((long) authorizationData.transaction().getPaymentNotices().stream()
                                    .mapToInt(paymentNotice -> paymentNotice.transactionAmount().value()).sum())
                                    + authorizationData.fee()
                    );
                    return new PostePayAuthRequestDto()
                            .grandTotal(grandTotal)
                            .description(
                                    authorizationData.transaction().getPaymentNotices().get(0).transactionDescription()
                                            .value()
                            )
                            .paymentChannel(authorizationData.pspChannelCode())
                            .idTransaction(authorizationData.transaction().getTransactionId().value().toString());
                })
                .flatMap(
                        payAuthRequestDto -> postePayInternalApi
                                .authRequest(payAuthRequestDto, false, encodeMdcFields(authorizationData))
                                .onErrorMap(
                                        WebClientResponseException.class,
                                        exception -> switch (exception.getStatusCode()) {
                                        case UNAUTHORIZED -> new AlreadyProcessedException(
                                                authorizationData.transaction().getTransactionId()
                                        );
                                        case GATEWAY_TIMEOUT -> new GatewayTimeoutException();
                                        case INTERNAL_SERVER_ERROR -> new BadGatewayException(
                                                "PostePay API returned 500"
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
                                && "XPAY".equals(authorizationRequestData.paymentGatewayId())
                )
                .switchIfEmpty(Mono.empty())
                .flatMap(authorizationRequestData -> {
                    final Mono<XPayAuthRequestDto> xPayAuthRequest;
                    if (authorizationData.authDetails()instanceof CardAuthRequestDetailsDto cardData) {
                        BigDecimal grandTotal = BigDecimal.valueOf(
                                ((long) authorizationData.transaction().getPaymentNotices().stream()
                                        .mapToInt(paymentNotice -> paymentNotice.transactionAmount().value()).sum())
                                        + authorizationData.fee()
                        );
                        xPayAuthRequest = Mono.just(
                                new XPayAuthRequestDto()
                                        .cvv(cardData.getCvv())
                                        .pan(cardData.getPan())
                                        .exipiryDate(cardData.getExpiryDate())
                                        .idTransaction(
                                                authorizationData.transaction().getTransactionId().value().toString()
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
                                .authRequestXpay(xPayAuthRequestDto, encodeMdcFields(authorizationData))
                                .onErrorMap(
                                        WebClientResponseException.class,
                                        exception -> switch (exception.getStatusCode()) {
                                        case UNAUTHORIZED -> new AlreadyProcessedException(
                                                authorizationData.transaction().getTransactionId()
                                        ); // 401
                                        case INTERNAL_SERVER_ERROR -> new BadGatewayException(""); // 500
                                        default -> exception;
                                        }
                                )
                );
    }

    public Mono<CreditCardAuthResponseDto> requestCreditCardAuthorization(AuthorizationRequestData authorizationData) {
        return Mono.just(authorizationData)
                .filter(
                        authorizationRequestData -> "CP".equals(authorizationRequestData.paymentTypeCode())
                                && "VPOS".equals(authorizationRequestData.paymentGatewayId())
                )
                .switchIfEmpty(Mono.empty())
                .flatMap(authorizationRequestData -> {
                    final Mono<CreditCardAuthRequestDto> creditCardAuthRequest;
                    if (authorizationData.authDetails()instanceof CardAuthRequestDetailsDto cardData) {
                        BigDecimal grandTotal = BigDecimal.valueOf(
                                ((long) authorizationData.transaction().getPaymentNotices().stream()
                                        .mapToInt(paymentNotice -> paymentNotice.transactionAmount().value()).sum())
                                        + authorizationData.fee()
                        );
                        creditCardAuthRequest = Mono.just(
                                new CreditCardAuthRequestDto()
                                        .pan(cardData.getPan())
                                        .expireDate(cardData.getExpiryDate())
                                        .idTransaction(
                                                authorizationData.transaction().getTransactionId().value().toString()
                                        )
                                        .amount(grandTotal)
                                        .emailCh(authorizationData.transaction().getEmail().value())
                                        .holder(cardData.getHolderName())
                                        .securitycode(cardData.getCvv())
                                        .isFirstPayment(true) // TO BE CHECKED
                                        .threeDsData("threeDsData") // TI BE CHECKED
                                        .circuit("VISA") // TO BE PASSED FROM CLIENT
                                        .reqRefNumber("reqRefNumber") // Is this the authRequestId ?
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
                                .step0CreditCard(
                                        "Web",
                                        creditCardAuthRequestDto,
                                        encodeMdcFields(authorizationData)
                                )
                                .onErrorMap(
                                        WebClientResponseException.class,
                                        exception -> switch (exception.getStatusCode()) {
                                        case UNAUTHORIZED -> new AlreadyProcessedException(
                                                authorizationData.transaction().getTransactionId()
                                        ); // 401
                                        case INTERNAL_SERVER_ERROR -> new BadGatewayException(""); // 500
                                        default -> exception;
                                        }
                                )
                );
    }

    private String encodeMdcFields(AuthorizationRequestData authorizationData) {
        String mdcData;
        try {
            mdcData = objectMapper.writeValueAsString(
                    Map.of("transactionId", authorizationData.transaction().getTransactionId().value())
            );
        } catch (JsonProcessingException e) {
            mdcData = "";
        }

        return Base64.getEncoder().encodeToString(mdcData.getBytes(StandardCharsets.UTF_8));
    }
}
