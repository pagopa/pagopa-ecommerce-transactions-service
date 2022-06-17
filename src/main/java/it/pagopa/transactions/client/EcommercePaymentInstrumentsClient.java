package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PspDto;
import it.pagopa.generated.ecommerce.sessions.v1.api.DefaultApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class EcommercePaymentInstrumentsClient {

    @Autowired
    @Qualifier("ecommercePaymentInstrumentsWebClient")
    private DefaultApi ecommercePaymentInstrumentsWebClient;

    public Flux<PspDto> getPSPs(Integer amount, String language) {

        return ecommercePaymentInstrumentsWebClient
                .getApiClient()
                .getWebClient()
                .get()
                .uri(uriBuilder -> uriBuilder
                        .queryParam("amount", amount)
                        .queryParam("lang", language)
                        .build())
                .retrieve()
                .onStatus(HttpStatus::isError,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .flatMap(errorResponseBody -> Mono.error(
                                        new ResponseStatusException(clientResponse.statusCode(), errorResponseBody))))
                .bodyToFlux(PspDto.class);
    }
}
