package it.pagopa.transactions.mdcutilities;

import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@Configuration
public class MDCContextLifterConfiguration {
    private String mdcContextReactorKey = MDCContextLifterConfiguration.class.getName();

    @PostConstruct
    private void contextOperatorHook() {
        Hooks.onEachOperator(
                mdcContextReactorKey,
                Operators.lift(
                        (
                         scannable,
                         coreSubscriber
                        ) -> new MDCContextLifter<>(coreSubscriber)
                )
        );
    }

    @PreDestroy
    private void cleanupHook() {
        Hooks.resetOnEachOperator(mdcContextReactorKey);
    }
}
