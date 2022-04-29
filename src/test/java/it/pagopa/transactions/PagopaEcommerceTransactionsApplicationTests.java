package it.pagopa.transactions;

import it.pagopa.transactions.model.IdempotencyKey;
import it.pagopa.transactions.model.RptId;
import it.pagopa.transactions.repositories.IdempotencyKeyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@TestPropertySource(locations="classpath:application-tests.properties")
class PagopaEcommerceTransactionsApplicationTests {
    @Autowired
    private IdempotencyKeyRepository repository;

    private static final RptId rptId = new RptId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private static final IdempotencyKey key = new IdempotencyKey(rptId,"00000000000","aaaaaaaaaa");

    @Test
    void contextLoads() {
        assertNotNull(repository);
    }

    @Test
    void canInsertIdempotencyKey() {
        repository.save(key);
        IdempotencyKey key = repository.findById(rptId).orElseThrow();
        System.out.println(key);
    }
}
