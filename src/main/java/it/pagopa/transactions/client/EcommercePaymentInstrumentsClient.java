package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.paymentinstruments.v1.api.DefaultApi;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.BundleOptionDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PaymentMethodResponseDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PaymentOptionDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class EcommercePaymentInstrumentsClient {

    @Autowired
    @Qualifier("ecommercePaymentInstrumentsWebClient")
    private DefaultApi ecommercePaymentInstrumentsWebClient;

    public Mono<BundleOptionDto> calculateFee(
                                              String paymentMethodId,
                                              PaymentOptionDto paymentOptionDto,
                                              Integer maxOccurrences

    ) {
        return ecommercePaymentInstrumentsWebClient.calculateFees(paymentMethodId, paymentOptionDto, maxOccurrences);
    }

    public Mono<PaymentMethodResponseDto> getPaymentMethod(String paymentMethodId) {
        return ecommercePaymentInstrumentsWebClient.getPaymentMethod(paymentMethodId);
    }
}
