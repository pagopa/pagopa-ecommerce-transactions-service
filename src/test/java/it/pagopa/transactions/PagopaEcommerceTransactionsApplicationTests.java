package it.pagopa.transactions;

import it.pagopa.generated.ecommerce.nodo.v1.api.NodoApi;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.services.TransactionsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@TestPropertySource(locations = "classpath:application-tests.properties")
@Import({NodoApi.class})
class PagopaEcommerceTransactionsApplicationTests {

  @MockBean
  private NodoApi nodoApi;

  @Test
  void contextLoads() {}
}
