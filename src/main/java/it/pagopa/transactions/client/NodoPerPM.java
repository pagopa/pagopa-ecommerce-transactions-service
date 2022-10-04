package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.nodo.v1.api.NodoApi;
import it.pagopa.generated.ecommerce.nodo.v1.dto.InformazioniPagamentoDto;
import it.pagopa.generated.ecommerce.sessions.v1.api.DefaultApi;
import it.pagopa.generated.nodoperpsp.model.NodoVerificaRPT;
import it.pagopa.generated.nodoperpsp.model.NodoVerificaRPTRisposta;
import it.pagopa.transactions.utils.soap.SoapEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import javax.xml.bind.JAXBElement;

@Component
@Slf4j
public class NodoPerPM {

    @Autowired
    private NodoApi nodoApiClient;

    @Value("${nodoPerPM.uri}") String nodoPerPMUri;

    public Mono<InformazioniPagamentoDto> chiediInformazioniPagamento(String paymentToken) {

        //TODO Update Path?
        return nodoApiClient
                .getApiClient()
                .getWebClient()
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path(nodoPerPMUri)
                        .queryParam("idPagamento", paymentToken)
                        .build())
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .retrieve()
                .onStatus(HttpStatus::isError,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .flatMap(errorResponseBody -> Mono.error(
                                        new ResponseStatusException(clientResponse.statusCode(), errorResponseBody))))
                .bodyToMono(InformazioniPagamentoDto.class)
                .doOnSuccess((InformazioniPagamentoDto informazioniPagamentoDto) -> log.debug(
                        "Payment info for {}",
                        new Object[]{paymentToken}))
                .doOnError(ResponseStatusException.class,
                        error -> log.error("ResponseStatus Error : {}", new Object[]{error}))
                .doOnError(Exception.class,
                        (Exception error) -> log.error("Generic Error : {}", new Object[]{error}));
    }

}
