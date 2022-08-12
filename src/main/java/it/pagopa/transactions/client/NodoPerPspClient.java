package it.pagopa.transactions.client;

import javax.xml.bind.JAXBElement;

import it.pagopa.generated.nodoperpsp.model.NodoVerificaRPT;
import it.pagopa.generated.nodoperpsp.model.NodoVerificaRPTRisposta;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import it.pagopa.transactions.utils.soap.SoapEnvelope;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class NodoPerPspClient {

    @Autowired
    private WebClient nodoWebClient;

    public Mono<NodoVerificaRPTRisposta> verificaRPT(JAXBElement<NodoVerificaRPT> request) {
        return nodoWebClient.post()
                .uri("/webservices/pof/PagamentiTelematiciPspNodoservice")
                .header("Content-Type", MediaType.TEXT_XML_VALUE)
                .header("SOAPAction", "nodoVerificaRPT")
                .body(Mono.just(new SoapEnvelope("", request)), SoapEnvelope.class)
                .retrieve()
                .onStatus(HttpStatus::isError,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .flatMap(errorResponseBody -> Mono.error(
                                        new ResponseStatusException(clientResponse.statusCode(), errorResponseBody))))
                .bodyToMono(NodoVerificaRPTRisposta.class)
                .doOnSuccess((NodoVerificaRPTRisposta verificaRPTResponse) -> log.debug(
                        "Payment info for {}",
                        new Object[]{request.getValue().getCodiceIdRPT()}))
                .doOnError(ResponseStatusException.class,
                        error -> log.error("ResponseStatus Error : {}", new Object[]{error}))
                .doOnError(Exception.class,
                        (Exception error) -> log.error("Generic Error : {}", new Object[]{error}));
    }
}
