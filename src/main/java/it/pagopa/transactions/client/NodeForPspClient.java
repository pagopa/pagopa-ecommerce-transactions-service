package it.pagopa.transactions.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.ws.client.core.support.WebServiceGatewaySupport;
import org.springframework.ws.soap.client.core.SoapActionCallback;

import it.pagopa.nodeforpsp.ActivatePaymentNoticeReq;
import it.pagopa.nodeforpsp.ActivatePaymentNoticeRes;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NodeForPspClient extends WebServiceGatewaySupport {

    @Value("${nodo.uri}")
    private String nodeUri;

    public ActivatePaymentNoticeRes activatePaymentNotice(ActivatePaymentNoticeReq request) {

        log.debug("Requesting activation for notice number " + request.getQrCode().getNoticeNumber());

        return (ActivatePaymentNoticeRes) getWebServiceTemplate().marshalSendAndReceive(nodeUri, request,
                new SoapActionCallback("activatePaymentNotice"));
    }
}