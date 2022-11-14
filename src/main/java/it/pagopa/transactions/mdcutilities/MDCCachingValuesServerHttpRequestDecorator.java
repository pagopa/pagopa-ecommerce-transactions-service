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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
public class MDCCachingValuesServerHttpRequestDecorator extends ServerHttpRequestDecorator {

    public static final String RPT_ID = "rptId";
    public static final String PAYMENT_CONTEXT_CODE = "paymentContextCode";
    public final  Map<String, Object>  objectAsMap;

    public Map<String, Object> getObjectAsMap() {
        return objectAsMap;
    }

    public MDCCachingValuesServerHttpRequestDecorator(ServerHttpRequest delegate) {
        super(delegate);
        objectAsMap = new HashMap<>();
    }

    @Override
    public Flux<DataBuffer> getBody() {
        return super.getBody().doOnNext(this::cache);
    }


    /** Cache body values and search for json object with listed properties.
     * Enrich this list of properties as needed to cache more value as possible if needed */
    @SneakyThrows
    private void cache(DataBuffer buffer) {
        objectAsMap.putAll(getValue(UTF_8.decode(buffer.asByteBuffer()).toString()));
        MDC.put(RPT_ID, objectAsMap.getOrDefault(RPT_ID,"").toString());
        MDC.put(PAYMENT_CONTEXT_CODE, objectAsMap.getOrDefault(PAYMENT_CONTEXT_CODE,"").toString());
    }

    public String getInfoFromValuesMap() {
        StringBuilder builder = new StringBuilder();
        objectAsMap.keySet().stream().filter(k -> k.equals(RPT_ID) || k.equals(PAYMENT_CONTEXT_CODE)).forEach(filteredKey -> builder.append(String.format("[%s]: [%s] ", filteredKey, objectAsMap.get(filteredKey))));
        return builder.toString().trim();
    }

    private Map<String, Object> getValue(String data) throws JsonProcessingException {
        TypeFactory factory = TypeFactory.defaultInstance();
        MapType type = factory.constructMapType(HashMap.class, String.class, Object.class);
        ObjectMapper mapper  = new ObjectMapper();
        return  mapper.readValue(data, type);
    }

}
