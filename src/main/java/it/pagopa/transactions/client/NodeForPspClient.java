package it.pagopa.transactions.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentResponseDto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ErrorDto;
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
    private final String nodoPerPspApiKey;
    private final String nodeForEcommerceApiKey;

    private final String nodoPerPmUri;

    /**
     * ObjectMapper instance used to decode JSON string http response
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public NodeForPspClient(
            @Qualifier("nodoWebClient") WebClient nodoWebClient,
            @Value("${nodo.nodeforpsp.uri}") String nodoPerPspUri,
            @Value("${nodo.ecommerce.clientId}") String ecommerceClientId,
            @Value("${nodo.nodoperpm.uri}") String nodoPerPmUri,
            @Value("${nodo.nodeforpsp.apikey}") String nodoPerPspApiKey,
            @Value("${nodo.nodeforecommerce.apikey}") String nodeForEcommerceApiKey
    ) {
        this.nodoWebClient = nodoWebClient;
        this.nodoPerPspUri = nodoPerPspUri;
        this.ecommerceClientId = ecommerceClientId;
        this.nodoPerPmUri = nodoPerPmUri;
        this.nodoPerPspApiKey = nodoPerPspApiKey;
        this.nodeForEcommerceApiKey = nodeForEcommerceApiKey;
    }

    public Mono<ActivatePaymentNoticeV2Response> activatePaymentNoticeV2(
                                                                         JAXBElement<ActivatePaymentNoticeV2Request> request,
                                                                         String transactionId
    ) {
        log.info(
                "ActivatePaymentNoticeV2 init for noticeNumber [{}]; idPSP: [{}], IdemPK: [{}]",
                request.getValue().getQrCode().getNoticeNumber(),
                request.getValue().getIdPSP(),
                request.getValue().getIdempotencyKey()
        );
        return nodoWebClient.post()
                .uri(nodoPerPspUri)
                .header("Content-Type", MediaType.TEXT_XML_VALUE)
                .header("SOAPAction", "activatePaymentNoticeV2")
                .header("x-transaction-id", transactionId)
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
                                "ActivatePaymentNoticeV2 completed for noticeNumber [{}], paymentToken [{}]",
                                request.getValue().getQrCode().getNoticeNumber(),
                                activateResponse.getPaymentToken()

                        )
                )
                .onErrorMap(
                        ResponseStatusException.class,
                        error -> {
                            log.error("ActivatePaymentNoticeV2 ResponseStatus Error:", error);
                            return new BadGatewayException(error.getReason(), error.getStatus());
                        }
                )
                .doOnError(Exception.class, error -> log.error("ActivatePaymentNoticeV2 Generic Error:", error));
    }

    public Mono<ClosePaymentResponseDto> closePaymentV2(ClosePaymentRequestV2Dto request) {
        log.info(
                "ClosePaymentV2 init for transactionId [{}]: paymentTokens [{}] - outcome: [{}]",
                request.getTransactionId(),
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
                .header("ocp-apim-subscription-key", nodeForEcommerceApiKey)
                .body(Mono.just(request), ClosePaymentRequestV2Dto.class)
                .retrieve()
                .onStatus(
                        HttpStatus::isError,
                        clientResponse -> clientResponse
                                .bodyToMono(String.class)
                                .switchIfEmpty(Mono.just("N/A"))
                                .flatMap(
                                        errorResponseBodyAsString -> Mono.error(
                                                new ResponseStatusException(
                                                        clientResponse.statusCode(),
                                                        errorResponseBodyAsString
                                                )
                                        )
                                )
                )
                .bodyToMono(ClosePaymentResponseDto.class)
                .doOnSuccess(
                        closePaymentResponse -> log
                                .info(
                                        "ClosePaymentV2 completed for transactionId [{}]: paymentTokens {} - outcome: {}",
                                        request.getTransactionId(),
                                        request.getPaymentTokens(),
                                        closePaymentResponse.getOutcome()
                                )
                )
                .onErrorMap(
                        ResponseStatusException.class,
                        error -> {

                            log.error(
                                    "ClosePaymentV2 Response Status Error for transactionId [{}]: {}",
                                    request.getTransactionId(),
                                    error
                            );

                            try {

                                return new BadGatewayException(
                                        objectMapper.readValue(error.getReason(), ErrorDto.class).getDescription(),
                                        error.getStatus()
                                );
                            } catch (JsonProcessingException e) {

                                return new BadGatewayException(error.getReason(), error.getStatus());
                            }

                        }
                )
                .doOnError(Exception.class, error -> log.error("ClosePaymentV2 Generic Error:", error));
    }
}
