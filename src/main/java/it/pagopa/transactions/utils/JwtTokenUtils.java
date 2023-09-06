package it.pagopa.transactions.utils;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.transactions.exceptions.JWTTokenGenerationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

@Component
@Slf4j
public class JwtTokenUtils {

    private final SecretKey jwtSecretKey;

    public static final String TRANSACTION_ID_CLAIM = "transactionId";

    private final int tokenValidityTimeSecond;

    public JwtTokenUtils(
            @Autowired SecretKey jwtSecretKey,
            @Value("${payment.token.validity}") int tokenValiditySecond
    ) {
        this.jwtSecretKey = jwtSecretKey;
        this.tokenValidityTimeSecond = tokenValiditySecond;
    }

    public Mono<String> generateToken(TransactionId transactionId) {
        try {
            Calendar calendar = Calendar.getInstance();
            Date issuedAtDate = calendar.getTime();
            calendar.add(Calendar.MILLISECOND, (int) Duration.ofSeconds(tokenValidityTimeSecond).toMillis());
            Date expiryDate = calendar.getTime();
            return Mono.just(
                    Jwts.builder()
                            .claim(TRANSACTION_ID_CLAIM, transactionId.value())// transactionId (custom
                                                                               // claim)
                            .setId(UUID.randomUUID().toString())// jti
                            .setIssuedAt(issuedAtDate)// iat
                            .setExpiration(expiryDate)// exp
                            .signWith(jwtSecretKey)
                            .compact()
            );
        } catch (JwtException e) {
            log.error("Error generating JWT token", e);
            return Mono.error(new JWTTokenGenerationException());
        }

    }

}
