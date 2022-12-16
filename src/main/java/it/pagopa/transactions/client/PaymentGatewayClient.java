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

    //TODO Handle multiple rptId

    public Mono<Tuple2<Optional<PostePayAuthResponseEntityDto>,Optional<XPayAuthResponseEntityDto>>> requestGeneralAuthorization(AuthorizationRequestData authorizationData) {
        Mono<Optional<PostePayAuthResponseEntityDto>> postePayAuthResponseEntityDtoMono = requestPostepayAuthorization(authorizationData).map(Optional::of).switchIfEmpty(Mono.just(Optional.empty()));
        Mono<Optional<XPayAuthResponseEntityDto>> xPayAuthResponseEntityDtoMono = requestXPayAuthorization(authorizationData).map(Optional::of).switchIfEmpty(Mono.just(Optional.empty()));
        return Mono.zip(postePayAuthResponseEntityDtoMono,xPayAuthResponseEntityDtoMono);
    }

    private Mono<PostePayAuthResponseEntityDto> requestPostepayAuthorization(AuthorizationRequestData authorizationData) {

        return Mono.just(authorizationData)
                .filter(authorizationRequestData -> "PPAY".equals(authorizationRequestData.paymentTypeCode()))
                .switchIfEmpty(Mono.empty())
            .map(authorizationRequestData -> {
                BigDecimal grandTotal = BigDecimal.valueOf(((long) authorizationData.transaction().getNoticeCodes().stream().mapToInt(noticeCode -> noticeCode.transactionAmount().value()).sum()) + authorizationData.fee());
                return new PostePayAuthRequestDto()
                    .grandTotal(grandTotal)
                    .description(authorizationData.transaction().getNoticeCodes().get(0).transactionDescription().value())
                    .paymentChannel(authorizationData.pspChannelCode())
                    .idTransaction(authorizationData.transaction().getTransactionId().value().toString());
            })
            .flatMap(payAuthRequestDto ->
                    paymentTransactionGatewayPostepayWebClient.authRequest(payAuthRequestDto, false, encodeMdcFields(authorizationData))
                    .onErrorMap(WebClientResponseException.class, exception -> switch (exception.getStatusCode()) {
                        //FIXME Handle multiple rptId
                            case UNAUTHORIZED -> new AlreadyProcessedException(authorizationData.transaction().getNoticeCodes().get(0).rptId());
                            case GATEWAY_TIMEOUT -> new GatewayTimeoutException();
                            case INTERNAL_SERVER_ERROR -> new BadGatewayException("");
                            default -> exception;
                        }
                    )
            );
    }

    private Mono<XPayAuthResponseEntityDto> requestXPayAuthorization(AuthorizationRequestData authorizationData) {

        return Mono.just(authorizationData)
                .filter(authorizationRequestData -> "CP".equals(authorizationRequestData.paymentTypeCode()) && "XPAY".equals(authorizationRequestData.paymentGatewayId()))
                .switchIfEmpty(Mono.empty())
                .flatMap(authorizationRequestData -> {
                    final Mono<XPayAuthRequestDto> xPayAuthRequest;
                    if (authorizationData.authDetails() instanceof CardAuthRequestDetailsDto cardData) {
                        BigDecimal grandTotal = BigDecimal.valueOf(((long) authorizationData.transaction().getNoticeCodes().stream().mapToInt(noticeCode -> noticeCode.transactionAmount().value()).sum()) + authorizationData.fee());
                        xPayAuthRequest = Mono.just(new XPayAuthRequestDto()
                                .cvv(cardData.getCvv())
                                .pan(cardData.getPan())
                                .exipiryDate(cardData.getExpiryDate().format(DateTimeFormatter.ofPattern("yyyyMM")))
                                .idTransaction(authorizationData.transaction().getTransactionId().value().toString())
                                .grandTotal(grandTotal));
                    } else {
                        xPayAuthRequest = Mono.error(new InvalidRequestException("Cannot perform XPAY authorization for null input CardAuthRequestDetailsDto"));
                    }
                    return xPayAuthRequest;
                })
                .flatMap(xPayAuthRequestDto ->
                        paymentTransactionGatewayXPayWebClient.authRequestXpay(xPayAuthRequestDto, encodeMdcFields(authorizationData))
                                .onErrorMap(WebClientResponseException.class, exception -> switch (exception.getStatusCode()) {
                                    case UNAUTHORIZED ->
                                            new AlreadyProcessedException(authorizationData.transaction().getNoticeCodes().get(0).rptId()); //401
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
