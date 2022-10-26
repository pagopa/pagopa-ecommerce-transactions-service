package it.pagopa.transactions.mdcutilities;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import reactor.core.publisher.Flux;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
public class MDCCachingValuesServerHttpRequestDecorator extends ServerHttpRequestDecorator {

    private final StringBuilder cachedBody = new StringBuilder();

    public MDCCachingValuesServerHttpRequestDecorator(ServerHttpRequest delegate) {
        super(delegate);
    }

    @Override
    public Flux<DataBuffer> getBody() {
        return super.getBody().doOnNext(this::cache);
    }

    @SneakyThrows
    private void cache(DataBuffer buffer) {
        MDC.put("RTP_ID", UTF_8.decode(buffer.asByteBuffer()).toString());
    }

}
