package it.pagopa.transactions.configurations;

import it.pagopa.transactions.utils.NodoConnectionString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class NodoConfig {

    @Bean
    public NodoConnectionString nodoConnectionParams(
            @Value("${nodo.connection.string}") String nodoConnectionParamsAsString)
            throws JsonMappingException, JsonProcessingException {

        return new ObjectMapper().readValue(nodoConnectionParamsAsString, NodoConnectionString.class);
    }
}