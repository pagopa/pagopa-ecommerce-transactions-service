package it.pagopa.transactions;

import it.pagopa.transactions.repositories.TransactionTokensRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@TestPropertySource(locations="classpath:application-tests.properties")
class PagopaEcommerceTransactionsApplicationTests {
    @Autowired
    private TransactionTokensRepository repository;

    @Test
    void contextLoads() {
        assertNotNull(repository);
    }
}
