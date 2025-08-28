package it.pagopa.transactions;

import it.pagopa.generated.ecommerce.nodo.v2.ApiClient;
import it.pagopa.generated.ecommerce.nodo.v2.api.NodoApi;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-tests.properties")
@Import(
    {
            NodoApi.class
    }
)
class PagopaEcommerceTransactionsApplicationTests {

    @MockitoBean
    private ApiClient apiClient;

    @InjectMocks
    private NodoApi nodoApiClient;

    @Test
    void contextLoads() {
        Assert.assertTrue(true);
    }
}
