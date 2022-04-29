package it.pagopa.transactions.client;

import javax.xml.bind.JAXBElement;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.core.SoapActionCallback;

import it.pagopa.nodeforpsp.ActivatePaymentNoticeReq;
import it.pagopa.nodeforpsp.ActivatePaymentNoticeRes;

@Component
public class NodeForPspClient {

    @Autowired
    private WebServiceTemplate webServiceTemplate;

    public ActivatePaymentNoticeRes activatePaymentNotice(JAXBElement<ActivatePaymentNoticeReq> request) {

        return (ActivatePaymentNoticeRes) webServiceTemplate.marshalSendAndReceive(request,
                new SoapActionCallback("activatePaymentNotice"));
    }
}