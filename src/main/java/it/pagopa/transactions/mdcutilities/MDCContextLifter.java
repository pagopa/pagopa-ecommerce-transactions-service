package it.pagopa.transactions.mdcutilities;

import org.reactivestreams.Subscription;
import org.slf4j.MDC;
import reactor.core.CoreSubscriber;
import reactor.util.context.Context;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Helper that copies the state of Reactor [Context] to MDC on the #onNext function.
 */
class MDCContextLifter<T> implements CoreSubscriber<T> {

    CoreSubscriber<T> coreSubscriber;

    public MDCContextLifter(CoreSubscriber<T> coreSubscriber) {
        this.coreSubscriber = coreSubscriber;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        //copyToMdc(coreSubscriber.currentContext());
        coreSubscriber.onSubscribe(subscription);
    }

    @Override
    public void onNext(T obj) {
        //copyToMdc(coreSubscriber.currentContext());
        coreSubscriber.onNext(obj);
    }

    @Override
    public void onError(Throwable t) {
        //copyToMdc(coreSubscriber.currentContext());
        coreSubscriber.onError(t);
    }

    @Override
    public void onComplete() {
        //copyToMdc(coreSubscriber.currentContext());
        coreSubscriber.onComplete();
    }

    @Override
    public Context currentContext() {
        return coreSubscriber.currentContext();
    }

    /**
     * Extension function for the Reactor [Context]. Copies the current context to the MDC, if context is empty clears the MDC.
     * State of the MDC after calling this method should be same as Reactor [Context] state.
     * One thread-local access only.
     */
    private void copyToMdc(Context context) {

        if (!context.isEmpty()) {
            Map<String, String> map = context.stream()
                    .collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue().toString()));
            MDC.setContextMap(map);
        } else {
            MDC.clear();
        }
    }

}