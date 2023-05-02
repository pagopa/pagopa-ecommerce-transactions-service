package it.pagopa.transactions.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.pagopa.generated.ecommerce.gateway.v1.api.PostePayInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.api.VposInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.api.XPayInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.dto.*;
import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.exceptions.GatewayTimeoutException;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import it.pagopa.transactions.utils.UUIDUtils;
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

    private final PostePayInternalApi postePayInternalApi;

    private final XPayInternalApi paymentTransactionGatewayXPayWebClient;

    private final VposInternalApi creditCardInternalApiClient;

    private final ObjectMapper objectMapper;

    private final UUIDUtils uuidUtils;

    private final ConfidentialMailUtils confidentialMailUtils;

    @Autowired
    public PaymentGatewayClient(
            @Qualifier("paymentTransactionGatewayPostepayWebClient") PostePayInternalApi postePayInternalApi,
            @Qualifier("paymentTransactionGatewayXPayWebClient") XPayInternalApi paymentTransactionGatewayXPayWebClient,
            @Qualifier("creditCardInternalApiClient") VposInternalApi creditCardInternalApiClient,
            ObjectMapper objectMapper,
            UUIDUtils uuidUtils,
            ConfidentialMailUtils confidentialMailUtils
    ) {
        this.postePayInternalApi = postePayInternalApi;
        this.paymentTransactionGatewayXPayWebClient = paymentTransactionGatewayXPayWebClient;
        this.creditCardInternalApiClient = creditCardInternalApiClient;
        this.objectMapper = objectMapper;
        this.uuidUtils = uuidUtils;
        this.confidentialMailUtils = confidentialMailUtils;
    }

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
                            .idTransaction(
                                    uuidUtils.uuidToBase64(authorizationData.transaction().getTransactionId().uuid())
                            );
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
                                        .expiryDate(cardData.getExpiryDate())
                                        .idTransaction(
                                                uuidUtils.uuidToBase64(
                                                        authorizationData.transaction().getTransactionId().uuid()
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
                                                authorizationData.transaction().getTransactionId()
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
                                && "VPOS".equals(authorizationRequestData.paymentGatewayId())
                )
                .switchIfEmpty(Mono.empty())
                .flatMap(
                        authorizationRequestData -> confidentialMailUtils
                                .toEmail(authorizationRequestData.transaction().getEmail())
                )
                .flatMap(email -> {
                    final Mono<VposAuthRequestDto> creditCardAuthRequest;
                    if (authorizationData.authDetails()instanceof CardAuthRequestDetailsDto cardData) {
                        BigDecimal grandTotal = BigDecimal.valueOf(
                                ((long) authorizationData.transaction().getPaymentNotices().stream()
                                        .mapToInt(paymentNotice -> paymentNotice.transactionAmount().value()).sum())
                                        + authorizationData.fee()
                        );
                        creditCardAuthRequest = Mono.just(
                                new VposAuthRequestDto()
                                        .pan(cardData.getPan())
                                        .expireDate(cardData.getExpiryDate())
                                        .idTransaction(
                                                uuidUtils.uuidToBase64(
                                                        authorizationData.transaction().getTransactionId().uuid()
                                                )
                                        )
                                        .amount(grandTotal)
                                        .emailCH(email.value())
                                        .holder(cardData.getHolderName())
                                        .securityCode(cardData.getCvv())
                                        .isFirstPayment(true) // TODO TO BE CHECKED
                                        .threeDsData(cardData.getThreeDsData())
                                        .circuit(
                                                VposAuthRequestDto.CircuitEnum.fromValue(cardData.getBrand().toString())
                                        )
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
                                                authorizationData.transaction().getTransactionId()
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
