package it.pagopa.transactions.client;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import it.pagopa.nodeforpsp.ActivatePaymentNoticeReq;
import it.pagopa.nodeforpsp.ActivatePaymentNoticeRes;
import it.pagopa.transactions.utils.soap.SoapEnvelopeRequest;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class NodoClient {

    @Autowired
    private WebClient nodoWebClient;

    public Mono<ActivatePaymentNoticeRes> activatePaymentNotice(ActivatePaymentNoticeReq request) {

        return nodoWebClient.post().body(Mono.just(new SoapEnvelopeRequest("", request)), SoapEnvelopeRequest.class)
                .retrieve()
                .onStatus(HttpStatus::isError,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .flatMap(errorResponseBody -> Mono.error(
                                        new ResponseStatusException(clientResponse.statusCode(), errorResponseBody))))
                .bodyToMono(ActivatePaymentNoticeRes.class)
                .doOnSuccess((ActivatePaymentNoticeRes paymentActivedDetail) -> {
                    log.debug("Payment activated with paymentToken {}", paymentActivedDetail.getPaymentToken());
                }).doOnError(ResponseStatusException.class, error -> {
                    log.error("error : {}", error);
                }).doOnError(Exception.class, (Exception error) -> {
                    log.error("error : {}", error);
                });
    }
}
