package it.pagopa.transactions.configurations;

import it.pagopa.generated.nodoperpsp.model.NodoAttivaRPT;
import it.pagopa.generated.nodoperpsp.model.NodoVerificaRPT;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeReq;
import it.pagopa.generated.transactions.model.VerifyPaymentNoticeReq;
import it.pagopa.transactions.utils.NodoConnectionString;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class NodoConfig {

  @Bean
  public NodoConnectionString nodoConnectionString(
      @Value("${nodo.connection.string}") String nodoConnectionParamsAsString)
      throws JsonProcessingException {

    return new ObjectMapper().readValue(nodoConnectionParamsAsString, NodoConnectionString.class);
  }

  @Bean
  public NodoVerificaRPT baseNodoVerificaRPTRequest(
      @Value("${nodo.connection.string}") String nodoConnectionParamsAsString,
      it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryNodoPerPsp)
      throws JsonProcessingException {

    NodoConnectionString nodoConnectionParams = nodoConnectionString(nodoConnectionParamsAsString);

    NodoVerificaRPT request = objectFactoryNodoPerPsp.createNodoVerificaRPT();
    request.setIdentificativoPSP(nodoConnectionParams.getIdPSP());
    request.setIdentificativoCanale(nodoConnectionParams.getIdChannel());
    request.setIdentificativoIntermediarioPSP(nodoConnectionParams.getIdBrokerPSP());
    request.setPassword(nodoConnectionParams.getPassword());
    request.setCodificaInfrastrutturaPSP("QR-CODE");
    return request;
  }

  @Bean
  public NodoAttivaRPT baseNodoAttivaRPTRequest(
          @Value("${nodo.connection.string}") String nodoConnectionParamsAsString,
          it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryNodoPerPsp)
          throws JsonProcessingException {

    NodoConnectionString nodoConnectionParams = nodoConnectionString(nodoConnectionParamsAsString);

    NodoAttivaRPT request = objectFactoryNodoPerPsp.createNodoAttivaRPT();
    request.setIdentificativoPSP(nodoConnectionParams.getIdPSP());
    request.setIdentificativoCanale(nodoConnectionParams.getIdChannel());
    request.setIdentificativoIntermediarioPSP(nodoConnectionParams.getIdBrokerPSP());
    request.setPassword(nodoConnectionParams.getPassword());
    return request;
  }

  @Bean
  public VerifyPaymentNoticeReq baseVerifyPaymentNoticeReq(
      @Value("${nodo.connection.string}") String nodoConnectionParamsAsString,
      it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp)
      throws JsonProcessingException {

    NodoConnectionString nodoConnectionParams = nodoConnectionString(nodoConnectionParamsAsString);

    VerifyPaymentNoticeReq request = objectFactoryNodeForPsp.createVerifyPaymentNoticeReq();
    request.setIdPSP(nodoConnectionParams.getIdPSP());
    request.setIdChannel(nodoConnectionParams.getIdChannel());
    request.setIdBrokerPSP(nodoConnectionParams.getIdBrokerPSP());
    request.setPassword(nodoConnectionParams.getPassword());
    return request;
  }

  @Bean
  public ActivatePaymentNoticeReq baseActivatePaymentNoticeReq(
      @Value("${nodo.connection.string}") String nodoConnectionParamsAsString,
      it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp)
      throws JsonProcessingException {

    NodoConnectionString nodoConnectionParams = nodoConnectionString(nodoConnectionParamsAsString);

    ActivatePaymentNoticeReq request = objectFactoryNodeForPsp.createActivatePaymentNoticeReq();
    request.setIdPSP(nodoConnectionParams.getIdPSP());
    request.setIdChannel(nodoConnectionParams.getIdChannel());
    request.setIdBrokerPSP(nodoConnectionParams.getIdBrokerPSP());
    request.setPassword(nodoConnectionParams.getPassword());

    return request;
  }
}
