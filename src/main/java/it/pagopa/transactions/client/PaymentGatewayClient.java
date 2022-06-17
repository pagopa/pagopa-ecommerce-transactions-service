package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.gateway.v1.api.PaymentTransactionsControllerApi;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthResponseEntityDto;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeRes;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationRequestDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.transactions.commands.data.AuthorizationData;
import it.pagopa.transactions.configurations.WebClientsConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class PaymentGatewayClient {

    @Autowired
    @Qualifier("paymentTransactionGatewayWebClientRaw")
    WebClient webClient;

    public Mono<RequestAuthorizationResponseDto> requestAuthorization(AuthorizationData authorizationData) {
        return webClient.post()
                .uri("/request-payments/postepay")
                .body(Mono.just(new PostePayAuthRequestDto()
                        .grandTotal(BigDecimal.valueOf(authorizationData.transaction().getAmount().value() + authorizationData.fee()))
                        .description(authorizationData.transaction().getDescription().value())
                        .paymentChannel("")
                        .idTransaction(0L)), PostePayAuthRequestDto.class)
                .header("mdc_info", "mdcInfo")
                .header("clientId", UUID.randomUUID().toString())
                .retrieve()
                .onStatus(HttpStatus::isError,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .flatMap(errorResponseBody -> Mono.error(
                                        new ResponseStatusException(clientResponse.statusCode(), errorResponseBody))))
                .bodyToMono(PostePayAuthResponseEntityDto.class)
                .map(response -> new RequestAuthorizationResponseDto().authorizationUrl(response.getUrlRedirect()));
    }
}
