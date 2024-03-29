package it.pagopa.transactions.configurations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeReq;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeV2Request;
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
    private it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp;

    @Bean
    public NodoConnectionString nodoConnectionString() {
        try {
            return new ObjectMapper()
                    .readValue(nodoConnectionParamsAsString, NodoConnectionString.class);
        } catch (JsonProcessingException e) {
            // exception not logged here since it can expose sensitive information contained
            // into nodo connection string
            throw new IllegalStateException("Exception parsing JSON nodo connection string");
        }
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

    public ActivatePaymentNoticeV2Request baseActivatePaymentNoticeV2Request() {
        NodoConnectionString nodoConnectionParams = nodoConnectionString();
        ActivatePaymentNoticeV2Request request = objectFactoryNodeForPsp.createActivatePaymentNoticeV2Request();
        request.setIdPSP(nodoConnectionParams.getIdPSP());
        request.setIdChannel(nodoConnectionParams.getIdChannel());
        request.setIdBrokerPSP(nodoConnectionParams.getIdBrokerPSP());
        request.setPassword(nodoConnectionParams.getPassword());
        return request;
    }
}
