//  package it.pagopa.transactions.reporitories;

//  import it.pagopa.transactions.model.IdempotencyKey;
//  import it.pagopa.transactions.model.RptId;
//  import it.pagopa.transactions.repositories.TransactionTokens;
//  import it.pagopa.transactions.repositories.TransactionTokensRepository;

//  import org.junit.jupiter.api.BeforeAll;
//  import org.junit.jupiter.api.Test;
//  import org.springframework.beans.factory.annotation.Autowired;
//  import org.springframework.boot.test.context.SpringBootTest;
//  import org.springframework.test.context.TestPropertySource;
//  import org.testcontainers.containers.GenericContainer;
//  import org.testcontainers.junit.jupiter.Container;
//  import org.testcontainers.junit.jupiter.Testcontainers;
//  import org.testcontainers.utility.DockerImageName;

//  import static org.junit.jupiter.api.Assertions.assertNotNull;

//  @SpringBootTest
//  @Testcontainers
//  @TestPropertySource(locations = "classpath:application-tests.properties")
//  class TransactionTokensRepositoryIT {

//      @Autowired
//      private TransactionTokensRepository repository;

//      private static final RptId rptId = new RptId("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
//      private static final IdempotencyKey key = new IdempotencyKey("00000000000", "aaaaaaaaaa");

//      @Container
//      public static GenericContainer redis = new GenericContainer(DockerImageName.parse("redis:5.0.3-alpine"))
//              .withExposedPorts(6379);

//      @BeforeAll
//      static void setup() {

//          System.setProperty("REDIS_HOST",  redis.getContainerInfo().getNetworkSettings().getIpAddress());
//          System.out.println(redis.getFirstMappedPort().toString());
//          System.setProperty("REDIS_PORT", redis.getFirstMappedPort().toString());
//          System.setProperty("REDIS_PASSWORD", "");
//      }

//      @Test
//      void canInsertIdempotencyKey() {
//          repository.save(new TransactionTokens(rptId, key, null));
//          TransactionTokens tokens = repository.findById(rptId).orElseThrow();
//          assertNotNull(tokens.idempotencyKey());
//          System.out.println(tokens);
//      }
//  }
