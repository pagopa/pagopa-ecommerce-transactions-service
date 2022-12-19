package it.pagopa.transactions;

import it.pagopa.generated.ecommerce.nodo.v1.ApiClient;
import it.pagopa.generated.ecommerce.nodo.v1.api.NodoApi;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.services.TransactionsService;
import it.pagopa.transactions.utils.NodoUtilities;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import javax.validation.constraints.AssertTrue;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-tests.properties")
@Import(
    {
            NodoApi.class
    }
)
class PagopaEcommerceTransactionsApplicationTests {

    @MockBean
    private NodoUtilities nodoUtilities;

    @MockBean
    private ApiClient apiClient;

    @InjectMocks
    private NodoApi nodoApiClient;

    @Test
    void contextLoads() {
        Assert.assertTrue(true);
    }
}
