package it.pagopa.transactions;

import it.pagopa.generated.ecommerce.nodo.v2.ApiClient;
import it.pagopa.generated.ecommerce.nodo.v2.api.NodoApi;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-tests.properties")
@Import(
    {
            NodoApi.class
    }
)
class PagopaEcommerceTransactionsApplicationTests {

    @MockBean
    private ApiClient apiClient;

    @InjectMocks
    private NodoApi nodoApiClient;

    @Test
    void contextLoads() {
        Assert.assertTrue(true);
    }
}
