package it.pagopa.transactions.client;

import javax.xml.bind.JAXBElement;

import it.pagopa.generated.nodoperpsp.model.NodoAttivaRPT;
import it.pagopa.generated.nodoperpsp.model.NodoAttivaRPTRisposta;
import it.pagopa.generated.nodoperpsp.model.NodoVerificaRPT;
import it.pagopa.generated.nodoperpsp.model.NodoVerificaRPTRisposta;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${nodo.nodoperpsp.uri}")
    private String nodoPerPspUri;

    public Mono<NodoVerificaRPTRisposta> verificaRPT(JAXBElement<NodoVerificaRPT> request) {
        log.info("verificaRPT idRpt {} ", request.getValue().getCodiceIdRPT());
        return nodoWebClient.post()
                .uri(nodoPerPspUri)
                .header("Content-Type", MediaType.TEXT_XML_VALUE)
                .header("SOAPAction", "nodoVerificaRPT")
                .body(Mono.just(new SoapEnvelope("", request)), SoapEnvelope.class)
                .retrieve()
                .onStatus(HttpStatus::isError,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .flatMap(errorResponseBody -> Mono.error(
                                        new ResponseStatusException(clientResponse.statusCode(), errorResponseBody))))
                .bodyToMono(NodoVerificaRPTRisposta.class)
                .doOnSuccess((NodoVerificaRPTRisposta verificaRPTResponse) -> log.info(
                        "Payment info for idRpt {}",
                        new Object[]{request.getValue().getCodiceIdRPT()}))
                .doOnError(ResponseStatusException.class,
                        error -> log.error("ResponseStatus Error : {}", new Object[]{error}))
                .doOnError(Exception.class,
                        (Exception error) -> log.error("Generic Error : {}", new Object[]{error}));
    }

    public Mono<NodoAttivaRPTRisposta> attivaRPT(JAXBElement<NodoAttivaRPT> request) {
        log.info("attivaRPT idRpt {} ", request.getValue().getCodiceIdRPT());
        return nodoWebClient.post()
                .uri(nodoPerPspUri)
                .header("Content-Type", MediaType.TEXT_XML_VALUE)
                .header("SOAPAction", "nodoAttivaRPT")
                .body(Mono.just(new SoapEnvelope("", request)), SoapEnvelope.class)
                .retrieve()
                .onStatus(HttpStatus::isError,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .flatMap(errorResponseBody -> Mono.error(
                                        new ResponseStatusException(clientResponse.statusCode(), errorResponseBody))))
                .bodyToMono(NodoAttivaRPTRisposta.class)
                .doOnSuccess((NodoAttivaRPTRisposta attivaRPTResponse) -> log.info(
                        "Payment info for idRpt {}",
                        new Object[]{request.getValue().getCodiceIdRPT()}))
                .doOnError(ResponseStatusException.class,
                        error -> log.error("ResponseStatus Error : {}", new Object[]{error}))
                .doOnError(Exception.class,
                        (Exception error) -> log.error("Generic Error : {}", new Object[]{error}));
    }
}
