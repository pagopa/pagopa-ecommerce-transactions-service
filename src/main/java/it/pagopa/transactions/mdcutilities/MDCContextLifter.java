package it.pagopa.transactions.mdcutilities;

import org.reactivestreams.Subscription;
import org.slf4j.MDC;
import reactor.core.CoreSubscriber;
import reactor.util.context.Context;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Helper that copies the state of Reactor [Context] to MDC on the #onNext
 * function.
 */
class MDCContextLifter<T> implements CoreSubscriber<T> {

    CoreSubscriber<T> coreSubscriber;

    public MDCContextLifter(CoreSubscriber<T> coreSubscriber) {
        this.coreSubscriber = coreSubscriber;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        coreSubscriber.onSubscribe(subscription);
    }

    @Override
    public void onNext(T obj) {
        copyToMdc(coreSubscriber.currentContext());
        coreSubscriber.onNext(obj);
    }

    @Override
    public void onError(Throwable t) {
        coreSubscriber.onError(t);
    }

    @Override
    public void onComplete() {
        coreSubscriber.onComplete();
        MDC.clear();
    }

    @Override
    public Context currentContext() {
        return coreSubscriber.currentContext();
    }

    /**
     * Extension function for the Reactor [Context]. Copies the current context to
     * the MDC, if context is empty clears the MDC. State of the MDC after calling
     * this method should be same as Reactor [Context] state. One thread-local
     * access only.
     */
    private void copyToMdc(Context context) {
        if (!context.isEmpty()) {
            Map<String, String> mdcContextMap = Optional.ofNullable(MDC.getCopyOfContextMap()).orElseGet(HashMap::new);
            Map<String, String> reactorContextMap = context.stream()
                    .filter(
                            e -> Arrays.stream(TransactionTracingUtils.TracingEntry.values())
                                    .anyMatch(tracingEntry -> tracingEntry.getKey().equals(e.getKey()))
                    )
                    .collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue().toString()));
            mdcContextMap.putAll(reactorContextMap);
            MDC.setContextMap(mdcContextMap);
        } else {
            MDC.clear();
        }
    }

}
