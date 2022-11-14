package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentRequestDto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentResponseDto;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeReq;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeRes;
import it.pagopa.generated.transactions.model.VerifyPaymentNoticeReq;
import it.pagopa.generated.transactions.model.VerifyPaymentNoticeRes;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.utils.soap.SoapEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import javax.xml.bind.JAXBElement;

@Component
@Slf4j
public class NodeForPspClient {

	@Autowired
	private WebClient nodoWebClient;

	@Value("${nodo.nodeforpsp.uri}")
	private String nodoPerPspUri;

	public Mono<VerifyPaymentNoticeRes> verifyPaymentNotice(JAXBElement<VerifyPaymentNoticeReq> request) {
		log.info("verifyPaymentNotice idPSP: {} ", request.getValue().getIdPSP());
		return nodoWebClient.post()
				.uri(nodoPerPspUri)
				.header("Content-Type", MediaType.TEXT_XML_VALUE)
				.header("SOAPAction", "verifyPaymentNotice")
				.body(Mono.just(new SoapEnvelope("", request)), SoapEnvelope.class)
				.retrieve()
				.onStatus(HttpStatus::isError,
						clientResponse -> clientResponse.bodyToMono(String.class)
								.flatMap(errorResponseBody -> Mono.error(
										new ResponseStatusException(clientResponse.statusCode(), errorResponseBody))))
				.bodyToMono(VerifyPaymentNoticeRes.class)
				.doOnSuccess((VerifyPaymentNoticeRes paymentInfo) -> log.info(
						"Payment activated with noticeNumber {}",
						new Object[] { request.getValue().getQrCode().getNoticeNumber() }))
				.doOnError(ResponseStatusException.class,
						error -> log.error("ResponseStatus Error : {}", new Object[] { error }))
				.doOnError(Exception.class,
						(Exception error) -> log.error("Generic Error : {}", new Object[] { error }));
	}

	public Mono<ActivatePaymentNoticeRes> activatePaymentNotice(JAXBElement<ActivatePaymentNoticeReq> request) {
		log.info("activatePaymentNotice idPSP: {} ", request.getValue().getIdPSP());
		log.info("activatePaymentNotice IdemPK: {} ", request.getValue().getIdempotencyKey());
		return nodoWebClient.post()
				.uri(nodoPerPspUri)
				.header("Content-Type", MediaType.TEXT_XML_VALUE)
				.header("SOAPAction", "activatePaymentNotice")
				.body(Mono.just(new SoapEnvelope("", request)), SoapEnvelope.class)
				.retrieve()
				.onStatus(HttpStatus::isError,
						clientResponse -> clientResponse.bodyToMono(String.class)
								.flatMap(errorResponseBody -> Mono.error(
										new ResponseStatusException(clientResponse.statusCode(), errorResponseBody))))
				.bodyToMono(ActivatePaymentNoticeRes.class)
				.doOnSuccess((ActivatePaymentNoticeRes paymentActivedDetail) -> log.info(
						"Payment activated with paymentToken {} ",
						new Object[] { paymentActivedDetail.getPaymentToken() }))
				.doOnError(ResponseStatusException.class,
						error -> log.error("ResponseStatus Error : {}", new Object[] { error }))
				.doOnError(Exception.class,
						(Exception error) -> log.error("Generic Error : {}", new Object[] { error }));
	}

	public Mono<it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentResponseDto> closePayment(ClosePaymentRequestDto request) {
		log.info("closePayment paymentTokens: {} ", request.getPaymentTokens());
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
				.bodyToMono(it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentResponseDto.class)
				.doOnSuccess(closePaymentResponse ->
						log.info("Requested closePayment for paymentTokens {}", request.getPaymentTokens()))
				.onErrorMap(ResponseStatusException.class,
						error -> {
							log.error("ResponseStatus Error:", error);
							return new BadGatewayException("");
						})
				.doOnError(Exception.class, error -> log.error("Generic Error:", error));
	}

	public Mono<ClosePaymentResponseDto> closePaymentV2(ClosePaymentRequestV2Dto request) {
		log.info("Requested closePaymentV2 for paymentTokens {}", request.getPaymentTokens());
		return nodoWebClient.post()
				.uri("/nodo/nodo-per-pm/v2/closepayment")
				.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.body(Mono.just(request), ClosePaymentRequestV2Dto.class)
				.retrieve()
				.onStatus(HttpStatus::isError, clientResponse ->
						clientResponse
								.bodyToMono(String.class)
								.flatMap(errorResponseBody ->
										Mono.error(new ResponseStatusException(clientResponse.statusCode(), errorResponseBody))))
				.bodyToMono(ClosePaymentResponseDto.class)
				.doOnSuccess(closePaymentResponse ->
						log.info("Requested closePayment for paymentTokens {}", request.getPaymentTokens()))
				.onErrorMap(ResponseStatusException.class,
						error -> {
							log.error("ResponseStatus Error:", error);
							return new BadGatewayException(error.getReason(), error);
						})
				.doOnError(Exception.class, error -> log.error("Generic Error:", error));
	}
}
