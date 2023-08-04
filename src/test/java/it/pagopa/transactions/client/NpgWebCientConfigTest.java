package it.pagopa.transactions.client;

import it.pagopa.ecommerce.commons.client.NpgClient;
import it.pagopa.ecommerce.commons.generated.npg.v1.ApiClient;
import it.pagopa.ecommerce.commons.generated.npg.v1.api.PaymentServicesApi;
import it.pagopa.transactions.configurations.NpgWebClientsConfig;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NpgWebClientConfigTest {

    @Test
    public void testNpgWebClientConfigApi() {
        NpgWebClientsConfig config = new NpgWebClientsConfig();
        PaymentServicesApi api = config.npgWebClient("localhost/test", 10000, 10000);
        Assert.assertNotNull(api);
        Assert.assertEquals(ApiClient.class, api.getApiClient().getClass());
    }

    @Test
    public void testNpgWebClientConfigNpgClient() {
        NpgWebClientsConfig config = new NpgWebClientsConfig();
        PaymentServicesApi api = config.npgWebClient("localhost/test", 10000, 10000);
        NpgClient npgClient = config.npgClient(api, "test-key");
        Assert.assertNotNull(npgClient);
        Assert.assertEquals(NpgClient.class, npgClient.getClass());
    }

}
