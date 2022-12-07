package it.pagopa.transactions.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.pagopa.generated.ecommerce.gateway.v1.api.PostePayInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.api.XPayInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthResponseEntityDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.XPayAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.XPayAuthResponseEntityDto;
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
import reactor.util.function.Tuple2;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

@Component
public class PaymentGatewayClient {
    @Autowired
    @Qualifier("paymentTransactionGatewayPostepayWebClient")
    PostePayInternalApi paymentTransactionGatewayPostepayWebClient;

    @Autowired
    @Qualifier("paymentTransactionGatewayXPayWebClient")
    XPayInternalApi paymentTransactionGatewayXPayWebClient;

    @Autowired
    private ObjectMapper objectMapper;

    public Mono<Tuple2<Optional<PostePayAuthResponseEntityDto>,Optional<XPayAuthResponseEntityDto>>> requestGeneralAuthorization(AuthorizationRequestData authorizationData) {
        Mono<Optional<PostePayAuthResponseEntityDto>> postePayAuthResponseEntityDtoMono = requestPostepayAuthorization(authorizationData).map(Optional::of).switchIfEmpty(Mono.just(Optional.empty()));
        Mono<Optional<XPayAuthResponseEntityDto>> xPayAuthResponseEntityDtoMono = requestXPayAuthorization(authorizationData).map(Optional::of).switchIfEmpty(Mono.just(Optional.empty()));
        return Mono.zip(postePayAuthResponseEntityDtoMono,xPayAuthResponseEntityDtoMono);
    }

    private Mono<PostePayAuthResponseEntityDto> requestPostepayAuthorization(AuthorizationRequestData authorizationData) {

        return Mono.just(authorizationData)
                .filter(authorizationRequestData -> "PPAY".equals(authorizationRequestData.paymentTypeCode()))
                .switchIfEmpty(Mono.empty())
            .map(authorizationRequestData ->
                new PostePayAuthRequestDto()
                    .grandTotal(BigDecimal.valueOf(((long) authorizationData.transaction().getAmount().value()) + authorizationData.fee()))
                    .description(authorizationData.transaction().getDescription().value())
                    .paymentChannel(authorizationData.pspChannelCode())
                    .idTransaction(authorizationData.transaction().getTransactionId().value().toString()))
            .flatMap(payAuthRequestDto ->
                    paymentTransactionGatewayPostepayWebClient.authRequest(payAuthRequestDto, false, encodeMdcFields(authorizationData))
                    .onErrorMap(WebClientResponseException.class, exception -> switch (exception.getStatusCode()) {
                            case UNAUTHORIZED -> new AlreadyProcessedException(authorizationData.transaction().getRptId());
                            case GATEWAY_TIMEOUT -> new GatewayTimeoutException();
                            case INTERNAL_SERVER_ERROR -> new BadGatewayException("");
                            default -> exception;
                        }
                    )
            );
    }

    private Mono<XPayAuthResponseEntityDto> requestXPayAuthorization(AuthorizationRequestData authorizationData) {
        CardAuthRequestDetailsDto cardDetails;
        if (authorizationData.authDetails() instanceof CardAuthRequestDetailsDto cardAuthRequestDetailsDto) {
            cardDetails = cardAuthRequestDetailsDto;
        } else {
            throw new InvalidRequestException("Cannot perform XPAY authorization for null input card details");
        }
        return Mono.just(authorizationData)
                .filter(authorizationRequestData -> "CP".equals(authorizationRequestData.paymentTypeCode()) && "XPAY".equals(authorizationRequestData.gatewayId()))
                .switchIfEmpty(Mono.empty())
                .map(authorizationRequestData ->
                        new XPayAuthRequestDto()
                                .cvv(cardDetails.getCvv())
                                .pan(cardDetails.getPan())
                                .exipiryDate(cardDetails.getExpiryDate().format(DateTimeFormatter.ofPattern("yyyyMM")))
                                .idTransaction(authorizationRequestData.transaction().getTransactionId().value().toString())
                                .grandTotal(BigDecimal.valueOf(((long) authorizationRequestData.transaction().getAmount().value()) + authorizationRequestData.fee())))
                .flatMap(xPayAuthRequestDto ->
                        paymentTransactionGatewayXPayWebClient.authRequestXpay(xPayAuthRequestDto, encodeMdcFields(authorizationData))
                                .onErrorMap(WebClientResponseException.class, exception -> switch (exception.getStatusCode()) {
                                    case UNAUTHORIZED ->
                                            new AlreadyProcessedException(authorizationData.transaction().getRptId()); //401
                                    case INTERNAL_SERVER_ERROR -> new BadGatewayException(""); //500
                                    default -> exception;
                                })
                );
        }

    private String encodeMdcFields(AuthorizationRequestData authorizationData) {
        String mdcData;
        try {
            mdcData = objectMapper.writeValueAsString(Map.of("transactionId", authorizationData.transaction().getTransactionId().value()));
        } catch (JsonProcessingException e) {
            mdcData = "";
        }

        return Base64.getEncoder().encodeToString(mdcData.getBytes(StandardCharsets.UTF_8));
    }
}
