package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.gateway.v1.api.PaymentTransactionsControllerApi;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthErrorDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthRequestDto;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthResponseEntityDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.transactions.commands.data.AuthorizationData;
import it.pagopa.transactions.exceptions.AlreadyAuthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class PaymentGatewayClient {
    @Autowired
    @Qualifier("paymentTransactionGatewayWebClient")
    PaymentTransactionsControllerApi paymentTransactionsControllerApi;


    public Mono<RequestAuthorizationResponseDto> requestAuthorization(AuthorizationData authorizationData) {
        PostePayAuthRequestDto postePayAuthRequest = new PostePayAuthRequestDto()
                .grandTotal(BigDecimal.valueOf(authorizationData.transaction().getAmount().value() + authorizationData.fee()))
                .description(authorizationData.transaction().getDescription().value())
                .paymentChannel("")
                .idTransaction(0L);

        return paymentTransactionsControllerApi.authRequest(UUID.randomUUID(), postePayAuthRequest, "mdcInfo")
                .onErrorMap(WebClientResponseException.class, exception -> {
                    if (exception.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                        return new AlreadyAuthorizedException(authorizationData.transaction().getRptId());
                    } else {
                        return exception;
                    }
                })
                .map(response -> new RequestAuthorizationResponseDto().authorizationUrl(response.getUrlRedirect()));
    }
}
