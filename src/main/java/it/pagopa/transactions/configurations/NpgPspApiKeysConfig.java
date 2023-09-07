package it.pagopa.transactions.configurations;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.pagopa.ecommerce.commons.client.NpgClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Configuration class used to read all PSP api keys for every payment method
 */
@Configuration
@Slf4j
public class NpgPspApiKeysConfig {

    /**
     * Object mapper instance used to parse Json api keys representation
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
    public Map<String, String> npgCardsApiKeys(
                                               @Value("${npg.authorization.cards.keys}") String apiKeys,
                                               @Value("${npg.authorization.cards.pspList}") Set<String> pspToHandle
    ) {
        return readMap(apiKeys, pspToHandle, NpgClient.PaymentMethod.CARDS);
    }

    /**
     * Parse secret configuration to map with pspId - apiKey association
     *
     * @param jsonRepresentation the configuration json representation
     * @param expectedKeys       configuration expected keys to be present
     * @param npgPaymentMethod   payment method for which parsing is performed
     * @return a {@code Map<String,String>} where keys are pspId and values the
     *         associated NPG api key
     */
    private Map<String, String> readMap(
                                        String jsonRepresentation,
                                        Set<String> expectedKeys,
                                        NpgClient.PaymentMethod npgPaymentMethod
    ) {
        try {
            Map<String, String> apiKeys = objectMapper
                    .readValue(jsonRepresentation, new TypeReference<HashMap<String, String>>() {
                    });
            Set<String> configuredKeys = apiKeys.keySet();
            expectedKeys.removeAll(configuredKeys);
            if (!expectedKeys.isEmpty()) {
                throw new IllegalStateException(
                        "Misconfigured NPG %s PSP api keys. Missing keys: %s".formatted(npgPaymentMethod, expectedKeys)
                );
            }
            return apiKeys;
        } catch (JacksonException ignored) {
            // exception here is ignored on purpose in order to avoid secret configuration
            // logging in case of wrong configured json string object
            throw new IllegalStateException("Invalid NPG %s PSP json configuration map".formatted(npgPaymentMethod));
        }
    }

}