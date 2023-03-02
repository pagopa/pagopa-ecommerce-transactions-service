package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.paymentinstruments.v1.api.DefaultApi;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PSPsResponseDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PaymentMethodResponseDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class EcommercePaymentInstrumentsClient {

    @Autowired
    @Qualifier("ecommercePaymentInstrumentsWebClient")
    private DefaultApi ecommercePaymentInstrumentsWebClient;

    public Mono<PSPsResponseDto> getPSPs(
                                         Integer amount,
                                         String language,
                                         String idPaymentMethod
    ) {
        return ecommercePaymentInstrumentsWebClient
                .getPaymentMethodsPSPs(idPaymentMethod, amount, language);
    }

    public Mono<PaymentMethodResponseDto> getPaymentMethod(String paymentMethodId) {
        return ecommercePaymentInstrumentsWebClient.getPaymentMethod(paymentMethodId);
    }
}
