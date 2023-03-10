package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.paymentinstruments.v1.api.DefaultApi;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.BundleOptionDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PaymentMethodResponseDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PaymentOptionDto;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class EcommercePaymentInstrumentsClient {

    @Autowired
    @Qualifier("ecommercePaymentInstrumentsWebClient")
    private DefaultApi ecommercePaymentInstrumentsWebClient;

    public Mono<BundleOptionDto> calculateFee(
                                              String paymentMethodId,
                                              PaymentOptionDto paymentOptionDto,
                                              Integer maxOccurrences

    ) {
        return ecommercePaymentInstrumentsWebClient.calculateFees(paymentMethodId, paymentOptionDto, maxOccurrences)
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
