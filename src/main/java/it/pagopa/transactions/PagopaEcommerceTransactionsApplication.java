package it.pagopa.transactions;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import io.github.resilience4j.retry.annotation.Retry;
import it.pagopa.ecommerce.commons.ConfigScan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
    }

    @Bean
    public RegistryEventConsumer<CircuitBreaker> myRegistryEventConsumer() {

        return new RegistryEventConsumer<CircuitBreaker>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> entryAddedEvent) {
                entryAddedEvent.getAddedEntry().getEventPublisher().onEvent(event -> log.info(event.toString()));
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<CircuitBreaker> entryRemoveEvent) {
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<CircuitBreaker> entryReplacedEvent) {
            }
        };
    }
}
