package it.pagopa.transactions.client;

import javax.xml.bind.JAXBElement;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import it.pagopa.generated.transactions.model.ActivatePaymentNoticeReq;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeRes;
import it.pagopa.transactions.utils.soap.SoapEnvelope;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class NodeForPspClient {

    @Autowired
    private WebClient nodoWebClient;

    public Mono<ActivatePaymentNoticeRes> activatePaymentNotice(JAXBElement<ActivatePaymentNoticeReq> request) {
		return nodoWebClient.post().header("Content-Type", MediaType.TEXT_XML_VALUE)
				.body(Mono.just(new SoapEnvelope("", request)), SoapEnvelope.class)
				.retrieve()
				.onStatus(HttpStatus::isError,
						clientResponse -> clientResponse.bodyToMono(String.class)
								.flatMap(errorResponseBody -> Mono.error(
										new ResponseStatusException(clientResponse.statusCode(), errorResponseBody))))
				.bodyToMono(ActivatePaymentNoticeRes.class)
				.doOnSuccess((ActivatePaymentNoticeRes paymentActivedDetail) -> log.debug(
						"Payment activated with paymentToken {}",
						new Object[] { paymentActivedDetail.getPaymentToken() }))
				.doOnError(ResponseStatusException.class,
						error -> log.error("ResponseStatus Error : {}", new Object[] { error }))
				.doOnError(Exception.class,
						(Exception error) -> log.error("Generic Error : {}", new Object[] { error }));
	}
}
