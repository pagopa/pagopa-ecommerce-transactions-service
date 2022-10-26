package it.pagopa.transactions.mdcutilities;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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
        getValue(UTF_8.decode(buffer.asByteBuffer()).toString(),"rptId")
                .ifPresent(v ->  MDC.put("RTP_ID", v.toString()));
       ;
    }

    private Optional<Object> getValue(String data, String searchKey) throws JsonProcessingException {
        Map<String, String> result;
        ObjectMapper mapper;
        TypeFactory factory;
        MapType type;

        factory = TypeFactory.defaultInstance();
        type    = factory.constructMapType(HashMap.class, String.class, Object.class);
        mapper  = new ObjectMapper();
        return  Optional.ofNullable(((HashMap)mapper.readValue(data, type)).get(searchKey));
    }

}
