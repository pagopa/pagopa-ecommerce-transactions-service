package it.pagopa.transactions.configurations;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Objects;

@Configuration
@Slf4j
public class CircuitBreakerConfig {
    @Bean
    public RegistryEventConsumer<CircuitBreaker> circuitBreakerEventConsumer() {

        return new RegistryEventConsumer<CircuitBreaker>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> entryAddedEvent) {
                entryAddedEvent.getAddedEntry().getEventPublisher().onEvent(
                        event -> LogTracingUtils.loggerTracingUtils()
                                .details(
                                        Map.of(
                                                "name",
                                                event.getCircuitBreakerName(),
                                                "creation_time",
                                                Objects.toString(event.getCreationTime()),
                                                "event_type",
                                                Objects.toString(event.getEventType())
                                        )
                                )
                                .logInfo(log, "CircuitBreaker event added")
                );
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<CircuitBreaker> entryRemoveEvent) {
                entryRemoveEvent.getRemovedEntry().getEventPublisher().onEvent(event -> {
                    if (log.isDebugEnabled()) {
                        LogTracingUtils.loggerTracingUtils()
                                .details(
                                        Map.of(
                                                "name",
                                                event.getCircuitBreakerName(),
                                                "creation_time",
                                                Objects.toString(event.getCreationTime()),
                                                "event_type",
                                                Objects.toString(event.getEventType())
                                        )
                                )
                                .logDebug(log, "CircuitBreaker event removed");
                    }
                }
                );
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<CircuitBreaker> entryReplacedEvent) {
                entryReplacedEvent.getNewEntry().getEventPublisher().onEvent(event -> {
                    if (log.isDebugEnabled()) {
                        LogTracingUtils.loggerTracingUtils()
                                .details(
                                        Map.of(
                                                "name",
                                                event.getCircuitBreakerName(),
                                                "creation_time",
                                                Objects.toString(event.getCreationTime()),
                                                "event_type",
                                                Objects.toString(event.getEventType())
                                        )
                                )
                                .logDebug(log, "CircuitBreaker event replaced");
                    }
                });
            }
        };
    }
}
