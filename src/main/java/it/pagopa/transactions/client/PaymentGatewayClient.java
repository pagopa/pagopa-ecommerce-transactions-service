package it.pagopa.transactions.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.pagopa.generated.ecommerce.gateway.v1.api.PostePayInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthResponseEntityDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.exceptions.GatewayTimeoutException;
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
    PostePayInternalApi paymentTransactionGatewayPostepayWebClient;

    @Autowired
    private ObjectMapper objectMapper;


    public Mono<PostePayAuthResponseEntityDto> requestAuthorization(AuthorizationRequestData authorizationData) {
        PostePayAuthRequestDto postePayAuthRequest = new PostePayAuthRequestDto()
                .grandTotal(BigDecimal.valueOf(authorizationData.transaction().getAmount().value() + authorizationData.fee()))
                .description(authorizationData.transaction().getDescription().value())
                .paymentChannel(authorizationData.pspChannelCode())
                .idTransaction(authorizationData.transaction().getTransactionId().value().toString());

        String encodedMdcFields = encodeMdcFields(authorizationData);

        return paymentTransactionGatewayPostepayWebClient.authRequest(postePayAuthRequest, false, encodedMdcFields)
                .onErrorMap(WebClientResponseException.class, exception -> switch (exception.getStatusCode()) {
                    case UNAUTHORIZED -> new AlreadyProcessedException(authorizationData.transaction().getRptId());
                    case GATEWAY_TIMEOUT -> new GatewayTimeoutException();
                    case INTERNAL_SERVER_ERROR -> new BadGatewayException("");
                    default -> exception;
                });
    }

    private String encodeMdcFields(AuthorizationRequestData authorizationData) {
        String mdcData;
        try {
            mdcData = objectMapper.writeValueAsString(Map.of("ecommerceTransactionId", authorizationData.transaction().getTransactionId().value()));
        } catch (JsonProcessingException e) {
            mdcData = "";
        }

        return Base64.getEncoder().encodeToString(mdcData.getBytes(StandardCharsets.UTF_8));
    }
}
