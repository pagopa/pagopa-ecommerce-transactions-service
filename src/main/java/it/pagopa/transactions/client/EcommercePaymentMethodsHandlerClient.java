package it.pagopa.transactions.client;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.generated.ecommerce.paymentmethodshandler.v1.dto.PaymentMethodResponseDto;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.PaymentMethodNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class EcommercePaymentMethodsHandlerClient {

    private final it.pagopa.generated.ecommerce.paymentmethodshandler.v1.api.PaymentMethodsApi ecommercePaymentMethodsHandlerWebClientV1;

    @Autowired
    public EcommercePaymentMethodsHandlerClient(
            @Qualifier(
                "ecommercePaymentMethoHandlerdWebClientV1"
            ) it.pagopa.generated.ecommerce.paymentmethodshandler.v1.api.PaymentMethodsApi ecommercePaymentMethodsHandlerWebClientV1
    ) {
        this.ecommercePaymentMethodsHandlerWebClientV1 = ecommercePaymentMethodsHandlerWebClientV1;
    }

    public Mono<PaymentMethodResponseDto> getPaymentMethod(
                                                           String paymentMethodId,
                                                           String xClientId
    ) {
        // payment methods only support CHECKOUT and IO.
        final var client = Transaction.ClientId.fromString(xClientId) == Transaction.ClientId.IO
                ? Transaction.ClientId.IO
                : Transaction.ClientId.CHECKOUT;

        return ecommercePaymentMethodsHandlerWebClientV1.getPaymentMethod(paymentMethodId, client.name())
                .doOnError(
                        WebClientResponseException.class,
                        EcommercePaymentMethodsHandlerClient::logWebClientException
                )
                .onErrorMap(
                        err -> {
                            if (err instanceof WebClientResponseException.NotFound) {
                                return new PaymentMethodNotFoundException(paymentMethodId, xClientId);
                            } else {
                                return new InvalidRequestException("Error while invoke method retrieve card data");
                            }
                        }
                );
    }

    private static void logWebClientException(WebClientResponseException e) {
        log.info(
                "Got bad response from payment-methods-service [HTTP {}]: {}",
                e.getStatusCode(),
                e.getResponseBodyAsString()
        );
    }
}
