package it.pagopa.transactions.configurations;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
public class SecretsConfigurations {

    @Bean
    public SecretKey jwtSigningKey(@Value("${jwt.secret}") String jwtSecret) {
        try {
            return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
        } catch (WeakKeyException | DecodingException e) {
            throw new IllegalStateException("Invalid configured JWT secret key", e);
        }
    }

    @Bean
    public ConfidentialDataManager confidentialDataManager(@Value("${confidentialDataManager.key}") String key,
                                                           @Value("${confidentialDataManager.encryptionAlgorithm}") String algorithm) {
        try {
            return new ConfidentialDataManager(new SecretKeySpec(Base64.getDecoder().decode(key), algorithm));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid configured confidential data manager key", e);
        }
    }


}
