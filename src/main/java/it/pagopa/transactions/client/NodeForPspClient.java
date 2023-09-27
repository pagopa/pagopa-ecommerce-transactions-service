package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentResponseDto;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeV2Request;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeV2Response;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.utils.soap.SoapEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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

    private static final String CLOSE_PAYMENT_CLIENT_ID = "ecomm";
    @Autowired
    @Qualifier("nodoWebClient")
    private WebClient nodoWebClient;

    @Value("${nodo.nodeforpsp.uri}")
    private String nodoPerPspUri;

    @Value("${nodo.nodeforpsp.apikey}")
    private String nodoPerPspApiKey;

    @Value("${nodo.closepayment.apikey}")
    private String nodoClosePaymentApiKey;

    public Mono<ActivatePaymentNoticeV2Response> activatePaymentNoticeV2(
                                                                         JAXBElement<ActivatePaymentNoticeV2Request> request
    ) {
        log.info("activatePaymentNotice idPSP: {} ", request.getValue().getIdPSP());
        log.info("activatePaymentNotice IdemPK: {} ", request.getValue().getIdempotencyKey());
        return nodoWebClient.post()
                .uri(nodoPerPspUri)
                .header("Content-Type", MediaType.TEXT_XML_VALUE)
                .header("SOAPAction", "activatePaymentNoticeV2")
                .header("ocp-apim-subscription-key", nodoPerPspApiKey)
                .body(Mono.just(new SoapEnvelope("", request)), SoapEnvelope.class)
                .retrieve()
                .onStatus(
                        HttpStatus::isError,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .flatMap(
                                        errorResponseBody -> Mono.error(
                                                new ResponseStatusException(
                                                        clientResponse.statusCode(),
                                                        errorResponseBody
                                                )
                                        )
                                )
                )
                .bodyToMono(ActivatePaymentNoticeV2Response.class)
                .doOnSuccess(
                        activateResponse -> log.info(
                                "Payment activated with paymentToken {} ",
                                activateResponse.getPaymentToken()

                        )
                )
                .onErrorMap(
                        ResponseStatusException.class,
                        error -> {
                            log.error("ResponseStatus Error:", error);
                            return new BadGatewayException(error.getReason(), error.getStatus());
                        }
                )
                .doOnError(Exception.class, error -> log.error("Generic Error:", error));
    }

    public Mono<ClosePaymentResponseDto> closePaymentV2(ClosePaymentRequestV2Dto request) {
        log.info(
                "Requested closePaymentV2 for paymentTokens {} - outcome: {}",
                request.getPaymentTokens(),
                request.getOutcome().getValue()
        );
        return nodoWebClient.post()
                .uri(
                        uriBuilder -> uriBuilder.path("/nodo-auth/nodo-per-pm/v2/closepayment")
                                .queryParam("clientId", CLOSE_PAYMENT_CLIENT_ID).build()
                )
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("ocp-apim-subscription-key", nodoClosePaymentApiKey)
                .body(Mono.just(request), ClosePaymentRequestV2Dto.class)
                .retrieve()
                .onStatus(
                        HttpStatus::isError,
                        clientResponse -> clientResponse
                                .bodyToMono(String.class)
                                .flatMap(
                                        errorResponseBody -> Mono.error(
                                                new ResponseStatusException(
                                                        clientResponse.statusCode(),
                                                        errorResponseBody
                                                )
                                        )
                                )
                )
                .bodyToMono(ClosePaymentResponseDto.class)
                .doOnSuccess(
                        closePaymentResponse -> log
                                .info("Requested closePayment for paymentTokens {}", request.getPaymentTokens())
                )
                .onErrorMap(
                        ResponseStatusException.class,
                        error -> {
                            log.error("ResponseStatus Error:", error);
                            return new BadGatewayException(error.getReason(), error.getStatus());
                        }
                )
                .doOnError(Exception.class, error -> log.error("Generic Error:", error));
    }
}
