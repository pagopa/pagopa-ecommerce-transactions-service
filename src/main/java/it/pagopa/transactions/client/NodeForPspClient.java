package it.pagopa.transactions.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.soap.MessageFactory;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPMessage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
public class NodeForPspClient {

    @Autowired
    private WebClient nodoWebClient;

    public Mono<ActivatePaymentNoticeRes> activatePaymentNotice(JAXBElement<ActivatePaymentNoticeReq> request) {

        return nodoWebClient.post().contentType(MediaType.TEXT_XML)
                .body(Mono.just(new SoapEnvelopeRequest(null, request)), SoapEnvelopeRequest.class)
                .retrieve()
                .onStatus(HttpStatus::isError,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .flatMap(errorResponseBody -> Mono.error(
                                        new ResponseStatusException(clientResponse.statusCode(), errorResponseBody))))
                .bodyToMono(String.class)
                .map(response -> {

                        ActivatePaymentNoticeRes activatePaymentNoticeRes = new ActivatePaymentNoticeRes();
                        String paymentToken = response.substring(response.indexOf("<paymentToken>") + "<paymentToken>".length());
                        activatePaymentNoticeRes.setPaymentToken(paymentToken.substring(0, paymentToken.indexOf("</paymentToken>")));
                        String totalAmount = response.substring(response.indexOf("<totalAmount>") + "<totalAmount>".length());
                        activatePaymentNoticeRes.setTotalAmount(new BigDecimal(totalAmount.substring(0, totalAmount.indexOf("</totalAmount>"))));
                        String paymentDescription = response.substring(response.indexOf("<paymentDescription>") + "<paymentDescription>".length());
                        activatePaymentNoticeRes.setPaymentDescription(paymentDescription.substring(0, paymentDescription.indexOf("</paymentDescription>")));
                        return activatePaymentNoticeRes;
                })
                .doOnSuccess((ActivatePaymentNoticeRes paymentActivedDetail) -> {
                    log.debug("Payment activated with paymentToken {}", paymentActivedDetail.getPaymentToken());
                }).doOnError(ResponseStatusException.class, error -> {
                    log.error("error : {}", error);
                }).doOnError(Exception.class, (Exception error) -> {
                    log.error("error : {}", error);
                });
    }
}
