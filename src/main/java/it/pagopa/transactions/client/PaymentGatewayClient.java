package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.gateway.v1.api.PaymentTransactionsControllerApi;
import it.pagopa.generated.ecommerce.gateway.v1.dto.PostePayAuthRequestDto;
import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
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
import java.util.UUID;

@Component
public class PaymentGatewayClient {
    @Autowired
    @Qualifier("paymentTransactionGatewayWebClient")
    PaymentTransactionsControllerApi paymentTransactionsControllerApi;


    public Mono<RequestAuthorizationResponseDto> requestAuthorization(AuthorizationRequestData authorizationData) {
        PostePayAuthRequestDto postePayAuthRequest = new PostePayAuthRequestDto()
                .grandTotal(BigDecimal.valueOf(authorizationData.transaction().getAmount().value() + authorizationData.fee()))
                .description(authorizationData.transaction().getDescription().value())
                .paymentChannel("")
                .idTransaction(0L);

        return paymentTransactionsControllerApi.authRequest(UUID.randomUUID(), postePayAuthRequest, "mdcInfo")
                .onErrorMap(WebClientResponseException.class, exception -> switch (exception.getStatusCode()) {
                    case UNAUTHORIZED -> new AlreadyProcessedException(authorizationData.transaction().getRptId());
                    case GATEWAY_TIMEOUT -> new GatewayTimeoutException();
                    case INTERNAL_SERVER_ERROR -> new BadGatewayException();
                    default -> exception;
                })
                .map(response -> new RequestAuthorizationResponseDto().authorizationUrl(response.getUrlRedirect()));
    }
}
