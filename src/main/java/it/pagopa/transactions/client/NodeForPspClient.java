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

    private final WebClient nodoWebClient;
    private final String nodoPerPspUri;
    private final String ecommerceClientId;

    private final String nodoPerPmUri;

    @Autowired
    public NodeForPspClient(
            @Qualifier("nodoWebClient") WebClient nodoWebClient,
            @Value("${nodo.nodeforpsp.uri}") String nodoPerPspUri,
            @Value("${nodo.ecommerce.clientId}") String ecommerceClientId,
            @Value("${nodo.nodoperpm.uri}") String nodoPerPmUri
    ) {
        this.nodoWebClient = nodoWebClient;
        this.nodoPerPspUri = nodoPerPspUri;
        this.ecommerceClientId = ecommerceClientId;
        this.nodoPerPmUri = nodoPerPmUri;
    }

    public Mono<ActivatePaymentNoticeV2Response> activatePaymentNoticeV2(
                                                                         JAXBElement<ActivatePaymentNoticeV2Request> request
    ) {
        log.info("activatePaymentNotice idPSP: {} ", request.getValue().getIdPSP());
        log.info("activatePaymentNotice IdemPK: {} ", request.getValue().getIdempotencyKey());
        return nodoWebClient.post()
                .uri(nodoPerPspUri)
                .header("Content-Type", MediaType.TEXT_XML_VALUE)
                .header("SOAPAction", "activatePaymentNoticeV2")
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
                        uriBuilder -> uriBuilder.path(nodoPerPmUri)
                                .path("/closepayment")
                                .queryParam("clientId", ecommerceClientId).build()
                )
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
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
