package it.pagopa.transactions.configurations;

import it.pagopa.ecommerce.commons.redis.templatewrappers.PaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.redis.templatewrappers.RedisTemplateWrapperBuilder;
import it.pagopa.ecommerce.commons.redis.templatewrappers.UniqueIdTemplateWrapper;
import it.pagopa.ecommerce.commons.repositories.UniqueIdDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class RedisConfig {

    @Bean
    public PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoWrapper(
                                                                            RedisConnectionFactory redisConnectionFactory,
                                                                            @Value(
                                                                                "${payment.token.validity}"
                                                                            ) Integer paymentTokenTimeout
    ) {
        // PaymentRequestInfo entities will have the same TTL as paymentTokenTimeout
        // value
        return RedisTemplateWrapperBuilder.buildPaymentRequestInfoRedisTemplateWrapper(
                redisConnectionFactory,
                Duration.ofSeconds(paymentTokenTimeout)
        );
    }

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
}
