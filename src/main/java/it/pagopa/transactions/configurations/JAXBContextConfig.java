package it.pagopa.transactions.configurations;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JAXBContextConfig {

    private static String PACKAGE_NODE_FOR_PSP = "it.pagopa.nodeforpsp";

    @Bean
    public Marshaller marshaller() throws JAXBException {
        return JAXBContext.newInstance(PACKAGE_NODE_FOR_PSP).createMarshaller();
    }

    @Bean
    public Unmarshaller unmarshaller() throws JAXBException {
        return JAXBContext.newInstance(PACKAGE_NODE_FOR_PSP).createUnmarshaller();
    }
}
