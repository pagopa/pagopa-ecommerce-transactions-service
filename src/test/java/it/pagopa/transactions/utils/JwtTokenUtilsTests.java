package it.pagopa.transactions.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import it.pagopa.ecommerce.commons.domain.v1.TransactionId;
import it.pagopa.transactions.configurations.SecretsConfigurations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class JwtTokenUtilsTests {
    private static final String STRONG_KEY = "ODMzNUZBNTZENDg3NTYyREUyNDhGNDdCRUZDNzI3NDMzMzQwNTFEREZGQ0MyQzA5Mjc1RjY2NTQ1NDk5MDMxNzU5NDc0NUVFMTdDMDhGNzk4Q0Q3RENFMEJBODE1NURDREExNEY2Mzk4QzFEMTU0NTExNjUyMEExMzMwMTdDMDk";

    private static final int TOKEN_VALIDITY_TIME_SECONDS = 900;
    private final SecretKey jwtSecretKey = new SecretsConfigurations().jwtSigningKey(STRONG_KEY);
    private final JwtTokenUtils jwtTokenUtils = new JwtTokenUtils(jwtSecretKey, TOKEN_VALIDITY_TIME_SECONDS);

    @Test
    void shouldGenerateValidJwtToken() {
        TransactionId transactionId = new TransactionId(UUID.randomUUID());
        String generatedToken = jwtTokenUtils.generateToken(transactionId).block();
        assertNotNull(generatedToken);
        Claims claims = assertDoesNotThrow(
                () -> Jwts.parserBuilder().setSigningKey(jwtSecretKey).build().parseClaimsJws(generatedToken).getBody()
        );
        assertEquals(transactionId.value(), claims.get(JwtTokenUtils.TRANSACTION_ID_CLAIM, String.class));
        assertNotNull(claims.getId());
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
        assertEquals(
                Duration.ofSeconds(TOKEN_VALIDITY_TIME_SECONDS).toMillis(),
                claims.getExpiration().getTime() - claims.getIssuedAt().getTime()
        );
    }
}
