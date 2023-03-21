package it.pagopa.transactions.configurations;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManager;
import it.pagopa.generated.pdv.v1.ApiClient;
import it.pagopa.generated.pdv.v1.api.TokenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Configuration
public class SecretsConfigurations {

    private static final String CONFIDENTIAL_DATA_MANAGER_KEY_TYPE = "AES";

    @Bean
    public SecretKey jwtSigningKey(@Value("${jwt.secret}") String jwtSecret) {
        try {
            return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
        } catch (WeakKeyException | DecodingException e) {
            throw new IllegalStateException("Invalid configured JWT secret key", e);
        }
    }

    @Bean
    public TokenApi personalDataVaultApiClient(
                                               @Value(
                                                   "${confidentialDataManager.personalDataVaultApiKey}"
                                               ) String personalDataVaultApiKey
    ) {
        ApiClient pdvApiClient = new ApiClient();
        pdvApiClient.setApiKey(personalDataVaultApiKey);

        return new TokenApi(pdvApiClient);
    }

    @Bean
    public ConfidentialDataManager emailConfidentialDataManager(
                                                                @Value(
                                                                    "${confidentialDataManager.emailEncryptionKey}"
                                                                ) String key,
                                                                TokenApi personalDataVaultApi
    ) {
        try {
            return new ConfidentialDataManager(
                    new SecretKeySpec(Base64.getDecoder().decode(key), CONFIDENTIAL_DATA_MANAGER_KEY_TYPE),
                    personalDataVaultApi
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid configured confidential data manager key", e);
        }
    }

}
