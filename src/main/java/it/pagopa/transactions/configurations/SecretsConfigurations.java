package it.pagopa.transactions.configurations;

import it.pagopa.ecommerce.commons.utils.ConfidentialDataManager;
import it.pagopa.generated.pdv.v1.ApiClient;
import it.pagopa.generated.pdv.v1.api.TokenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecretsConfigurations {
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
