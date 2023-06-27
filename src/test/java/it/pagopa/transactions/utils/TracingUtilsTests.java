package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.queues.TracingInfo;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;

public class TracingUtilsTests {
    public static TracingUtils getMock() {
        TracingUtils mockedTracingUtils = Mockito.mock(TracingUtils.class);
        Mockito.when(mockedTracingUtils.traceMono(any(), any())).thenAnswer(invocation -> {
            Function<TracingInfo, Mono<?>> arg = invocation.getArgument(1);
            TracingInfo tracingInfo = new TracingInfo("traceparent", "tracestate", "baggage");

            return arg.apply(tracingInfo);
        });

        return mockedTracingUtils;
    }
}
