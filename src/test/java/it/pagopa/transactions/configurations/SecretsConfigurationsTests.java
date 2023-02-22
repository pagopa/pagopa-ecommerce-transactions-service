package it.pagopa.transactions.configurations;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class SecretsConfigurationsTests {

    private SecretsConfigurations secretsConfigurations = new SecretsConfigurations();

    private static final String STRONG_KEY = "ODMzNUZBNTZENDg3NTYyREUyNDhGNDdCRUZDNzI3NDMzMzQwNTFEREZGQ0MyQzA5Mjc1RjY2NTQ1NDk5MDMxNzU5NDc0NUVFMTdDMDhGNzk4Q0Q3RENFMEJBODE1NURDREExNEY2Mzk4QzFEMTU0NTExNjUyMEExMzMwMTdDMDk";

    private static final String WEAK_KEY = "ODMzNUZBNTZENDg3";

    private static final String INVALID_KEY = ".";

    private static final String CONFIDENTIAL_DATA_MANAGER_ALGORITHM = "AES";

    private static final String CONFIDENTIAL_DATA_MANAGER_KEY;

    static {
        byte[] randomKey = new byte[16];
        new Random().nextBytes(randomKey);
        CONFIDENTIAL_DATA_MANAGER_KEY = Base64.getEncoder().encodeToString(randomKey);
    }

    @Test
    void shouldGenerateJwtSigningKey() {
        assertDoesNotThrow(() -> secretsConfigurations.jwtSigningKey(STRONG_KEY));
    }

    @Test
    void shouldThrowIllegalStateExceptionForWeakKey() {
        assertThrows(IllegalStateException.class, () -> secretsConfigurations.jwtSigningKey(WEAK_KEY));
    }

    @Test
    void shouldThrowIllegalStateExceptionForInvalidKey() {
        assertThrows(IllegalStateException.class, () -> secretsConfigurations.jwtSigningKey(INVALID_KEY));
    }

    @Test
    void shouldBuildConfidentialDataManager() {
        assertDoesNotThrow(
                () -> secretsConfigurations
                        .confidentialDataManager(CONFIDENTIAL_DATA_MANAGER_KEY, CONFIDENTIAL_DATA_MANAGER_ALGORITHM)
        );
    }

    @Test
    void shouldThrowIllegalStateExceptionForInvalidAlgorithmBuildingConfidentialDataManager() {
        assertThrows(
                IllegalStateException.class,
                () -> secretsConfigurations.confidentialDataManager(CONFIDENTIAL_DATA_MANAGER_KEY, null)
        );
    }

    @Test
    void shouldThrowIllegalStateExceptionForInvalidKeyBuildingConfidentialDataManager() {
        assertThrows(
                IllegalStateException.class,
                () -> secretsConfigurations.confidentialDataManager(INVALID_KEY, CONFIDENTIAL_DATA_MANAGER_ALGORITHM)
        );
    }
}
