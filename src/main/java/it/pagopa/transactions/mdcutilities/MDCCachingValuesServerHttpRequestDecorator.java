package it.pagopa.transactions.mdcutilities;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
public class MDCCachingValuesServerHttpRequestDecorator extends ServerHttpRequestDecorator {

    public MDCCachingValuesServerHttpRequestDecorator(ServerHttpRequest delegate) {
        super(delegate);
    }

    @Override
    public Flux<DataBuffer> getBody() {
        return super.getBody().doOnNext(this::cache);
    }

    @SneakyThrows
    private void cache(DataBuffer buffer) {
        //TODO Enumerate dto values of interest to cache more value as possible if needed
        Map objectAsMap = getValue(UTF_8.decode(buffer.asByteBuffer()).toString());
        MDC.put("rptId", objectAsMap.getOrDefault("rptId","").toString());
        MDC.put("paymentContextCode", objectAsMap.getOrDefault("paymentContextCode","").toString());
    }

    private Map<String, Object> getValue(String data) throws JsonProcessingException {
        TypeFactory factory = TypeFactory.defaultInstance();
        MapType type = factory.constructMapType(HashMap.class, String.class, Object.class);
        ObjectMapper mapper  = new ObjectMapper();
        return  mapper.readValue(data, type);
    }

}
