package it.pagopa.transactions.configuration;

import it.pagopa.transactions.client.NodeForPspClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.transport.http.HttpComponentsMessageSender;

import it.pagopa.nodeforpsp.ObjectFactory;

@Configuration
public class NodeForPspClientConfig {

  @Value("${nodo.uri}")
  private String uri;

  @Value("${nodo.readTimeout}")
  private int readTimeout;

  @Value("${nodo.connectionTimeout}")
  private int connectionTimeout;

  @Bean
  public Jaxb2Marshaller marshaller() {
    Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
    marshaller.setContextPath("it.pagopa.nodeforpsp");
    return marshaller;
  }

  @Bean
  public ObjectFactory objectFactory() {
    return new ObjectFactory();
  }

  @Bean
  public WebServiceTemplate webServiceTemplate(Jaxb2Marshaller marshaller) {
    HttpComponentsMessageSender sender = new HttpComponentsMessageSender();
    sender.setReadTimeout(readTimeout);
    sender.setConnectionTimeout(connectionTimeout);
    WebServiceTemplate webServiceTemplate = new WebServiceTemplate();
    webServiceTemplate.setMarshaller(marshaller);
    webServiceTemplate.setUnmarshaller(marshaller);
    webServiceTemplate.setDefaultUri(uri);
    webServiceTemplate.setMessageSender(sender);
    return webServiceTemplate;
  }

}