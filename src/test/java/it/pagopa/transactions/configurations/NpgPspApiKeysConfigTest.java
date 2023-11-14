package it.pagopa.transactions.configurations;

import it.pagopa.ecommerce.commons.exceptions.NpgApiKeyConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NpgPspApiKeysConfigTest {

    private final NpgPspApiKeysConfig npgPspApiKeysConfig = new NpgPspApiKeysConfig();

    private final String pspConfigurationJson = """
            {
                "psp1" : "key-psp1",
                "psp2" : "key-psp2",
                "psp3" : "key-psp3"
            }
            """;

    private final Set<String> pspToHandle = Set.of("psp1", "psp2", "psp3");

    @ParameterizedTest
    @ValueSource(
            strings = {
                    "psp1",
                    "psp2",
                    "psp3"
            }
    )
    void shouldParsePspConfigurationSuccessfully(String pspId) {
        Map<String, String> pspConfiguration = npgPspApiKeysConfig
                .npgCardsApiKeys(pspConfigurationJson, new HashSet<>(pspToHandle));
        assertEquals("key-%s".formatted(pspId), pspConfiguration.get(pspId));
    }

    @Test
    void shouldThrowExceptionForInvalidJsonStructure() {
        Set<String> psps = new HashSet<>(pspToHandle);
        NpgApiKeyConfigurationException exception = assertThrows(
                NpgApiKeyConfigurationException.class,
                () -> npgPspApiKeysConfig.npgCardsApiKeys("{", psps)
        );
        assertEquals(
                "Error parsing NPG PSP api keys configuration for payment method: [CARDS], cause: Invalid json configuration map",
                exception.getMessage()
        );
    }

    @Test
    void shouldThrowExceptionForMissingPspId() {
        Set<String> psps = new HashSet<>(pspToHandle);
        psps.add("psp4");
        NpgApiKeyConfigurationException exception = assertThrows(
                NpgApiKeyConfigurationException.class,
                () -> npgPspApiKeysConfig.npgCardsApiKeys(pspConfigurationJson, psps)
        );
        assertEquals(
                "Error parsing NPG PSP api keys configuration for payment method: [CARDS], cause: Misconfigured api keys. Missing keys: [psp4]",
                exception.getMessage()
        );
    }
}
