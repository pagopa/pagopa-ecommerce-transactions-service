package it.pagopa.transactions.configurations;

import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManager;
import it.pagopa.generated.pdv.v1.ApiClient;
import it.pagopa.generated.pdv.v1.api.TokenApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.crypto.SecretKey;

@Configuration
public class SecretsConfigurations {

    @Bean("ecommerceSigningKey")
    public SecretKey ecommerceSigningKey(@Value("${jwt.ecommerce.secretKey}") String jwtSecret) {
        return jwtSigningKey(jwtSecret);
    }

    @Bean("ecommerceWebViewSigningKey")
    public SecretKey ecommerceWebViewSigningKey(@Value("${jwt.ecommerceWebView.secretKey}") String jwtSecret) {
        return jwtSigningKey(jwtSecret);
    }

    @Bean("npgNotificationSigningKey")
    public SecretKey npgNotificationSigningKey(@Value("${npg.notification.jwt.secret}") String jwtSecret) {
        return jwtSigningKey(jwtSecret);
    }

    private SecretKey jwtSigningKey(String jwtSecret) {
        try {
            return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
        } catch (WeakKeyException | DecodingException e) {
            throw new IllegalStateException("Invalid configured JWT secret key", e);
        }
    }

    @Bean
    public TokenApi personalDataVaultApiClient(
                                               @Value(
                                                   "${confidentialDataManager.personalDataVault.apiKey}"
                                               ) String personalDataVaultApiKey,
                                               @Value(
                                                   "${confidentialDataManager.personalDataVault.apiBasePath}"
                                               ) String apiBasePath
    ) {
        ApiClient pdvApiClient = new ApiClient();
        pdvApiClient.setApiKey(personalDataVaultApiKey);
        pdvApiClient.setBasePath(apiBasePath);

        return new TokenApi(pdvApiClient);
    }

    @Bean
    public ConfidentialDataManager emailConfidentialDataManager(TokenApi personalDataVaultApi) {
        return new ConfidentialDataManager(personalDataVaultApi);
    }
}
