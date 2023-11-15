package it.pagopa.transactions.configurations;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.pagopa.ecommerce.commons.client.NpgClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;
import java.util.function.Function;

/**
 * Configuration class used to read all PSP api keys for every payment method
 */
@Configuration
@Slf4j
public class NpgPspApiKeysConfigBuilder {

    /**
     * ObjectMapper instance used to decode JSON string configuration
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Return a map where valued with each psp id - api keys entries
     *
     * @param apiKeys - the secret api keys configuration json
     * @return the parsed map
     */
    @Qualifier("npgCardsApiKeys")
    @Bean
    public it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig npgCardsApiKeys(
                                                                                 @Value(
                                                                                     "${npg.authorization.cards.keys}"
                                                                                 ) String apiKeys,
                                                                                 @Value(
                                                                                     "${npg.authorization.cards.pspList}"
                                                                                 ) Set<String> pspToHandle
    ) {
        return it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig.parseApiKeyConfiguration(
                apiKeys,
                pspToHandle,
                NpgClient.PaymentMethod.CARDS,
                objectMapper
        )
                .fold(exception -> {
                    throw exception;
                },
                        Function.identity()
                );
    }

}
