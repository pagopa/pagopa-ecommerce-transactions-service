package it.pagopa.transactions.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import it.pagopa.transactions.configurations.JwtConfigurations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.SecretKey;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class JwtTokenUtilsTests {
    private static final String STRONG_KEY = "ODMzNUZBNTZENDg3NTYyREUyNDhGNDdCRUZDNzI3NDMzMzQwNTFEREZGQ0MyQzA5Mjc1RjY2NTQ1NDk5MDMxNzU5NDc0NUVFMTdDMDhGNzk4Q0Q3RENFMEJBODE1NURDREExNEY2Mzk4QzFEMTU0NTExNjUyMEExMzMwMTdDMDk";

    private static final int TOKEN_VALIDITY_TIME = 10000;
    private final SecretKey jwtSecretKey = new JwtConfigurations().jwtSigningKey(STRONG_KEY);
    private final JwtTokenUtils jwtTokenUtils = new JwtTokenUtils(jwtSecretKey, TOKEN_VALIDITY_TIME);

    @Test
    void shouldGenerateValidJwtToken() {
        String transactionId = UUID.randomUUID().toString();
        String generatedToken = jwtTokenUtils.generateTokenHeader(transactionId);
        assertNotNull(generatedToken);
        Claims claims = assertDoesNotThrow(
                () -> Jwts.parserBuilder().setSigningKey(jwtSecretKey).build().parseClaimsJws(generatedToken).getBody()
        );
        assertEquals(transactionId, claims.get(JwtTokenUtils.TRANSACTION_ID_CLAIM, String.class));
        assertNotNull(claims.getId());
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
        assertEquals(TOKEN_VALIDITY_TIME, claims.getExpiration().getTime() - claims.getIssuedAt().getTime());
    }
}
