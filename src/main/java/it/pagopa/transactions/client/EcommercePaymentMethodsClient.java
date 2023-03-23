package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.paymentmethods.v1.api.DefaultApi;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.*;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class EcommercePaymentMethodsClient {

    @Autowired
    @Qualifier("ecommercePaymentInstrumentsWebClient")
    private DefaultApi ecommercePaymentInstrumentsWebClient;

    public Mono<CalculateFeeResponseDto> calculateFee(
                                                      String paymentMethodId,
                                                      CalculateFeeRequestDto calculateFeeRequestDto,
                                                      Integer maxOccurrences

    ) {
        return ecommercePaymentInstrumentsWebClient
                .calculateFees(paymentMethodId, calculateFeeRequestDto, maxOccurrences)
                .doOnError(
                        WebClientResponseException.class,
                        e -> log.info(
                                "Got bad response from payment-methods-service [HTTP {}]: {}",
                                e.getStatusCode(),
                                e.getResponseBodyAsString()
                        )
                )
                .onErrorMap(
                        err -> new InvalidRequestException("Error while invoke method for read psp list")
                );
    }

    public Mono<PaymentMethodResponseDto> getPaymentMethod(String paymentMethodId) {
        return ecommercePaymentInstrumentsWebClient.getPaymentMethod(paymentMethodId);
    }
}
