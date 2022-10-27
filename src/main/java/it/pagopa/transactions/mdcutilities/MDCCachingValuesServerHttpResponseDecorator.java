package it.pagopa.transactions.mdcutilities;

import org.reactivestreams.Publisher;
import org.slf4j.MDC;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import reactor.core.publisher.Mono;

public class MDCCachingValuesServerHttpResponseDecorator extends ServerHttpResponseDecorator {

    public MDCCachingValuesServerHttpResponseDecorator(ServerHttpResponse delegate) {
        super(delegate);
    }

    @Override
    public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
        MDC.clear();
        return super.writeAndFlushWith(body);
    }

}
