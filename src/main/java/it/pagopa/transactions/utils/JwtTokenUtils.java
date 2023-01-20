package it.pagopa.transactions.utils;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.Calendar;
import java.util.Date;

@Component
@Slf4j
public class JwtTokenUtils {

    private final SecretKey jwtSecretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public static final String TRANSACTION_ID_CLAIM = "transactionId";

    private final int tokenValidityTimeMillis;

    public JwtTokenUtils(
            @Autowired SecretKey jwtSecretKey,
            @Value("${jwt.validityMillis}") int tokenValidityMillis
    ) {
        this.jwtSecretKey = jwtSecretKey;
        this.tokenValidityTimeMillis = tokenValidityMillis;
    }

    public String generateToken(String transactionId) {
        try {
            Calendar calendar = Calendar.getInstance();
            Date issuedAtDate = calendar.getTime();
            calendar.add(Calendar.MILLISECOND, tokenValidityTimeMillis);
            Date expiryDate = calendar.getTime();
            return Jwts.builder()
                    .claim(TRANSACTION_ID_CLAIM, transactionId)// transactionId (custom claim)
                    .setId(generateNonce())// jti
                    .setIssuedAt(issuedAtDate)// iat
                    .setExpiration(expiryDate)// exp
                    .signWith(jwtSecretKey)
                    .compact();
        } catch (JwtException e) {
            log.error("Error generating JWT token", e);
            return null;
        }

    }

    private String generateNonce() {
        StringBuilder nonce = new StringBuilder();
        for (int curr = 0; curr < 15; curr++) {
            nonce.append(secureRandom.nextInt(10));
        }
        return nonce.toString();
    }
}
