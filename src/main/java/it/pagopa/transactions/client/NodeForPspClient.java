package it.pagopa.transactions.client;

import javax.xml.bind.JAXBElement;

import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentRequestDto;
import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentResponseDto;
import it.pagopa.transactions.exceptions.BadGatewayException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
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
		return nodoWebClient.post()
				.uri("/webservices/pof/PagamentiTelematiciPspNodoservice")
				.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_XML_VALUE)
				.header("SOAPAction", "activatePaymentNotice")
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

	public Mono<ClosePaymentResponseDto> closePayment(ClosePaymentRequestDto request) {
		return nodoWebClient.post()
				.uri("/v2/closepayment")
				.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.body(Mono.just(request), ClosePaymentRequestDto.class)
				.retrieve()
				.onStatus(HttpStatus::isError, clientResponse ->
						clientResponse
								.bodyToMono(String.class)
								.flatMap(errorResponseBody ->
										Mono.error(new ResponseStatusException(clientResponse.statusCode(), errorResponseBody))))
				.bodyToMono(ClosePaymentResponseDto.class)
				.doOnSuccess(closePaymentResponse ->
						log.debug("Requested closePayment for paymentTokens {}", request.getPaymentTokens()))
				.onErrorMap(ResponseStatusException.class,
						error -> {
							log.error("ResponseStatus Error:", error);
							return new BadGatewayException("");
						})
				.doOnError(Exception.class, error -> log.error("Generic Error:", error));
	}
}
