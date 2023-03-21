package it.pagopa.transactions.configurations;

import it.pagopa.generated.pdv.v1.api.TokenApi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class SecretsConfigurationsTests {

    private final SecretsConfigurations secretsConfigurations = new SecretsConfigurations();

    private static final String STRONG_KEY = "ODMzNUZBNTZENDg3NTYyREUyNDhGNDdCRUZDNzI3NDMzMzQwNTFEREZGQ0MyQzA5Mjc1RjY2NTQ1NDk5MDMxNzU5NDc0NUVFMTdDMDhGNzk4Q0Q3RENFMEJBODE1NURDREExNEY2Mzk4QzFEMTU0NTExNjUyMEExMzMwMTdDMDk";

    private static final String WEAK_KEY = "ODMzNUZBNTZENDg3";

    private static final String INVALID_KEY = ".";

    private static final String EMAIL_ENCRYPTION_KEY;

    private static final TokenApi pdvApi = new TokenApi();

    static {
        byte[] randomKey = new byte[16];
        new Random().nextBytes(randomKey);
        EMAIL_ENCRYPTION_KEY = Base64.getEncoder().encodeToString(randomKey);
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
                        .emailConfidentialDataManager(EMAIL_ENCRYPTION_KEY, pdvApi)
        );
    }

    @Test
    void shouldThrowIllegalStateExceptionForInvalidKeyBuildingConfidentialDataManager() {
        assertThrows(
                IllegalStateException.class,
                () -> secretsConfigurations.emailConfidentialDataManager(INVALID_KEY, pdvApi)
        );
    }
}
