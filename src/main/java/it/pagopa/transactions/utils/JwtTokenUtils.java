package it.pagopa.transactions.utils;

import io.jsonwebtoken.JwtBuilder;
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
import java.util.Calendar;
import java.util.Date;
import java.util.UUID;

@Component
@Slf4j
public class JwtTokenUtils {

    private final SecretKey jwtSecretKey;

    public static final String TRANSACTION_ID_CLAIM = "transactionId";

    public static final String ORDER_ID_CLAIM = "orderId";

    private final int tokenValidityTimeSeconds;

    public JwtTokenUtils(
            @Autowired SecretKey jwtSecretKey,
            @Value("${payment.token.validity}") int tokenValiditySeconds
    ) {
        this.jwtSecretKey = jwtSecretKey;
        this.tokenValidityTimeSeconds = tokenValiditySeconds;
    }

    public Mono<String> generateToken(
                                      TransactionId transactionId,
                                      String orderId
    ) {
        try {
            Calendar calendar = Calendar.getInstance();
            Date issuedAtDate = calendar.getTime();
            calendar.add(Calendar.SECOND, tokenValidityTimeSeconds);
            Date expiryDate = calendar.getTime();

            JwtBuilder jwtBuilder = Jwts.builder()
                    .claim(TRANSACTION_ID_CLAIM, transactionId.value())// claim TransactionId
                    .setId(UUID.randomUUID().toString())// jti
                    .setIssuedAt(issuedAtDate)// iat
                    .setExpiration(expiryDate)// exp
                    .signWith(jwtSecretKey);

            if (orderId != null) {
                jwtBuilder.claim(ORDER_ID_CLAIM, orderId); // claim orderId
            }
            return Mono.just(jwtBuilder.compact());
        } catch (JwtException e) {
            log.error("Error generating JWT token", e);
            return Mono.error(new JWTTokenGenerationException());
        }

    }

}
