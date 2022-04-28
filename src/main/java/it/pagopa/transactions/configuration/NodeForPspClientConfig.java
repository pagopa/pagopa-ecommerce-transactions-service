package it.pagopa.transactions.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;

import it.pagopa.transactions.client.NodeForPspClient;

@Configuration
public class NodeForPspClientConfig {

  @Bean
  public Jaxb2Marshaller marshaller() {
    Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
    marshaller.setContextPath("it.pagopa.nodeforpsp");
    return marshaller;
  }

  @Bean
  public NodeForPspClient nodeForPspClient(Jaxb2Marshaller marshaller) {
    NodeForPspClient client = new NodeForPspClient();
    client.setMarshaller(marshaller);
    client.setUnmarshaller(marshaller);
    return client;
  }

}