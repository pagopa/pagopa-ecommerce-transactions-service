package it.pagopa.transactions.configurations;

import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.ReactiveUniqueIdTemplateWrapper;
import it.pagopa.ecommerce.commons.redis.templatewrappers.UniqueIdTemplateWrapper;
import it.pagopa.ecommerce.commons.repositories.UniqueIdDocument;
import it.pagopa.ecommerce.commons.utils.UniqueIdUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class UniqueIdConfiguration {

    @Bean
    public ReactiveUniqueIdTemplateWrapper reactiveUniqueIdTemplateWrapper(
                                                                           ReactiveRedisConnectionFactory reactiveRedisConnectionFactory
    ) {
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        Jackson2JsonRedisSerializer<UniqueIdDocument> valueSerializer = new Jackson2JsonRedisSerializer<>(
                UniqueIdDocument.class
        );

        RedisSerializationContext<String, UniqueIdDocument> serializationContext = RedisSerializationContext
                .<String, UniqueIdDocument>newSerializationContext(keySerializer)
                .value(valueSerializer)
                .build();

        ReactiveRedisTemplate<String, UniqueIdDocument> reactiveRedisTemplate = new ReactiveRedisTemplate<>(
                reactiveRedisConnectionFactory,
                serializationContext
        );
        return new ReactiveUniqueIdTemplateWrapper(
                reactiveRedisTemplate,
                "uniqueId",
                Duration.ofSeconds(60)
        );

    }

    @Bean
    public UniqueIdUtils uniqueIdUtils(ReactiveUniqueIdTemplateWrapper uniqueIdTemplateWrapper) {
        return new ReactiveUniqueIdUtils(uniqueIdTemplateWrapper);
    }
}
