package it.pagopa.transactions.configurations;

import it.pagopa.ecommerce.commons.redis.templatewrappers.UniqueIdTemplateWrapper;
import it.pagopa.ecommerce.commons.repositories.UniqueIdDocument;
import it.pagopa.ecommerce.commons.utils.UniqueIdUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class UniqueIdConfiguration {

    @Bean
    public UniqueIdTemplateWrapper uniqueIdTemplateWrapper(
                                                           RedisConnectionFactory redisConnectionFactory
    ) {
        RedisTemplate<String, UniqueIdDocument> redisTemplate = new RedisTemplate<>();
        Jackson2JsonRedisSerializer<UniqueIdDocument> jacksonRedisSerializer = new Jackson2JsonRedisSerializer<>(
                UniqueIdDocument.class
        );

        redisTemplate.setConnectionFactory(redisConnectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(jacksonRedisSerializer);
        redisTemplate.afterPropertiesSet();

        return new UniqueIdTemplateWrapper(
                redisTemplate,
                "uniqueId",
                Duration.ofSeconds(60)
        );
    }

    @Bean
    public UniqueIdUtils uniqueIdUtils(UniqueIdTemplateWrapper uniqueIdTemplateWrapper) {
        return new UniqueIdUtils(uniqueIdTemplateWrapper);
    }
}
