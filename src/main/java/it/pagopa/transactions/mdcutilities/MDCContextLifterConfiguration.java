package it.pagopa.transactions.mdcutilities;

import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
@Configuration
public class MDCContextLifterConfiguration {
    private String MDC_CONTEXT_REACTOR_KEY = MDCContextLifterConfiguration.class.getName();
    @PostConstruct
    private void contextOperatorHook() {
        Hooks.onEachOperator(MDC_CONTEXT_REACTOR_KEY,
                Operators.lift((scannable, coreSubscriber) -> new MDCContextLifter<>(coreSubscriber))
        );
    }
    @PreDestroy
    private void cleanupHook() {
        Hooks.resetOnEachOperator(MDC_CONTEXT_REACTOR_KEY);
    }
}
