package it.pagopa.transactions.configurations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.pagopa.generated.nodoperpsp.model.NodoAttivaRPT;
import it.pagopa.generated.nodoperpsp.model.NodoVerificaRPT;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeReq;
import it.pagopa.generated.transactions.model.VerifyPaymentNoticeReq;
import it.pagopa.transactions.utils.NodoConnectionString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NodoConfig {

    @Value("${nodo.connection.string}")
    private String nodoConnectionParamsAsString;

    @Autowired
    private it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryNodoPerPsp;

    @Autowired
    private it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp;

    @Bean
    public NodoConnectionString nodoConnectionString() {
        NodoConnectionString nodoConnectionString = null;
        try {
            nodoConnectionString = new ObjectMapper()
                    .readValue(nodoConnectionParamsAsString, NodoConnectionString.class);
        } catch (JsonProcessingException e) {
            // exception not logged here since it can expose sensitive information contained
            // into nodo connection string
            throw new IllegalStateException("Exception parsing JSON nodo connection string");
        }
        return nodoConnectionString;
    }

    public NodoVerificaRPT baseNodoVerificaRPTRequest() {
        NodoConnectionString nodoConnectionParams = nodoConnectionString();
        NodoVerificaRPT request = objectFactoryNodoPerPsp.createNodoVerificaRPT();
        request.setIdentificativoPSP(nodoConnectionParams.getIdPSP());
        request.setIdentificativoCanale(nodoConnectionParams.getIdChannel());
        request.setIdentificativoCanale(nodoConnectionParams.getIdChannel());
        request.setIdentificativoIntermediarioPSP(nodoConnectionParams.getIdBrokerPSP());
        request.setPassword(nodoConnectionParams.getPassword());
        request.setCodificaInfrastrutturaPSP("QR-CODE");
        return request;
    }

    public NodoAttivaRPT baseNodoAttivaRPTRequest() {
        NodoConnectionString nodoConnectionParams = nodoConnectionString();
        NodoAttivaRPT request = objectFactoryNodoPerPsp.createNodoAttivaRPT();
        request.setIdentificativoPSP(nodoConnectionParams.getIdPSP());
        request.setIdentificativoCanale(nodoConnectionParams.getIdChannel());
        request.setIdentificativoIntermediarioPSP(nodoConnectionParams.getIdBrokerPSP());
        request.setIdentificativoIntermediarioPSPPagamento(nodoConnectionParams.getIdBrokerPSP());
        request.setIdentificativoCanalePagamento(nodoConnectionParams.getIdChannelPayment());
        request.setPassword(nodoConnectionParams.getPassword());
        request.setCodificaInfrastrutturaPSP("QR-CODE");
        return request;
    }

    public VerifyPaymentNoticeReq baseVerifyPaymentNoticeReq() {
        NodoConnectionString nodoConnectionParams = nodoConnectionString();
        VerifyPaymentNoticeReq request = objectFactoryNodeForPsp.createVerifyPaymentNoticeReq();
        request.setIdPSP(nodoConnectionParams.getIdPSP());
        request.setIdChannel(nodoConnectionParams.getIdChannel());
        request.setIdBrokerPSP(nodoConnectionParams.getIdBrokerPSP());
        request.setPassword(nodoConnectionParams.getPassword());
        return request;
    }

    public ActivatePaymentNoticeReq baseActivatePaymentNoticeReq() {
        NodoConnectionString nodoConnectionParams = nodoConnectionString();
        ActivatePaymentNoticeReq request = objectFactoryNodeForPsp.createActivatePaymentNoticeReq();
        request.setIdPSP(nodoConnectionParams.getIdPSP());
        request.setIdChannel(nodoConnectionParams.getIdChannel());
        request.setIdBrokerPSP(nodoConnectionParams.getIdBrokerPSP());
        request.setPassword(nodoConnectionParams.getPassword());
        return request;
    }
}
