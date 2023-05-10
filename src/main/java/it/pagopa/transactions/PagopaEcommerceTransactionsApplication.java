package it.pagopa.transactions;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnCallNotPermittedEvent;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnErrorEvent;
import it.pagopa.ecommerce.commons.ConfigScan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync(proxyTargetClass = true)
@SpringBootApplication
@EnableRedisRepositories(basePackages = "it.pagopa.ecommerce.commons.repositories")
@Import(ConfigScan.class)
@Slf4j
public class PagopaEcommerceTransactionsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PagopaEcommerceTransactionsApplication.class, args);
        registerCircuitBreakerListener();
    }

    public static void registerCircuitBreakerListener() {
        log.info("Attaching listener to circuit-breakers");
        // Create a CircuitBreakerRegistry default configuration
        CircuitBreakerRegistry circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreaker circuitBreakerTransaction = circuitBreakerRegistry.circuitBreaker("transactions-backend");
        CircuitBreaker circuiteBreakerNode = circuitBreakerRegistry.circuitBreaker("node-backend");
        CircuitBreaker circuitBreakerEcommerce = circuitBreakerRegistry.circuitBreaker("ecommerce-db");

        // Add logging on circuit breakers error
        circuitBreakerTransaction.getEventPublisher()
                .onError(e -> logCircuitBreakerError("transactions-backend", e));
        circuiteBreakerNode.getEventPublisher()
                .onError(e -> logCircuitBreakerError("node-backend", e));
        circuitBreakerEcommerce.getEventPublisher()
                .onError(e -> logCircuitBreakerError("ecommerce-db", e));

        circuitBreakerTransaction.getEventPublisher()
                .onCallNotPermitted(e -> logCircuitBreakerCallNotPermitted("transactions-backend", e));
        circuiteBreakerNode.getEventPublisher()
                .onCallNotPermitted(e -> logCircuitBreakerCallNotPermitted("node-backend", e));
        circuitBreakerEcommerce.getEventPublisher()
                .onCallNotPermitted(e -> logCircuitBreakerCallNotPermitted("ecommerce-db", e));
    }

    private static void logCircuitBreakerError(
                                               String name,
                                               CircuitBreakerOnErrorEvent e
    ) {
        log.error(String.format("Circuit breaker: %s - error: %s", name, e));
    }

    private static void logCircuitBreakerCallNotPermitted(
                                                          String name,
                                                          CircuitBreakerOnCallNotPermittedEvent e
    ) {
        log.error(String.format("Circuit breaker: %s - call not permitted: %s", name, e));
    }

}
