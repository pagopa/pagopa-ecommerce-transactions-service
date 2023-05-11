package it.pagopa.transactions.configurations;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerConfigTest {

    @InjectMocks
    CircuitBreakerConfig circuitBreakerConfig = new CircuitBreakerConfig();


    @Test
    void shouldLogRemovedEvent() {
        // preconditions
        EntryRemovedEvent<CircuitBreaker> removedEvent = mock(EntryRemovedEvent.class);
        CircuitBreaker mockCircuitBreaker = mock(CircuitBreaker.class);
        CircuitBreaker.EventPublisher mockEventPublisher = mock(CircuitBreaker.EventPublisher.class);

        Mockito.when(removedEvent.getRemovedEntry()).thenReturn(mockCircuitBreaker);
        Mockito.when(mockCircuitBreaker.getEventPublisher()).thenReturn(mockEventPublisher);

        // test
        circuitBreakerConfig.circuitBreakerEventConsumer().onEntryRemovedEvent(removedEvent);
    }


      @Test void shouldLogReplacedEvent(){
          // preconditions
          EntryReplacedEvent<CircuitBreaker> replacedEvent = mock(EntryReplacedEvent.class);
          CircuitBreaker mockCircuitBreaker = mock(CircuitBreaker.class);
          CircuitBreaker.EventPublisher mockEventPublisher = mock(CircuitBreaker.EventPublisher.class);

          Mockito.when(replacedEvent.getNewEntry()).thenReturn(mockCircuitBreaker);
          Mockito.when(mockCircuitBreaker.getEventPublisher()).thenReturn(mockEventPublisher);

          // test
          circuitBreakerConfig.circuitBreakerEventConsumer().onEntryReplacedEvent(replacedEvent);

          // assertions
      }

}
