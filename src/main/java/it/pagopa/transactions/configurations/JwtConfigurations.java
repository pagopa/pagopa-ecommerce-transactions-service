package it.pagopa.transactions.configurations;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;

@Configuration
public class JwtConfigurations {

    @Bean
    public SecretKey jwtSigningKey(@Value("${jwt.secret}") String jwtSecret) {
        try {
            return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
        } catch (WeakKeyException | DecodingException e) {
            throw new IllegalStateException("Invalid configured JWT secret key", e);
        }
    }
}
