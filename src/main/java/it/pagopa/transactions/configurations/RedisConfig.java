package it.pagopa.transactions.configurations;

import it.pagopa.ecommerce.commons.redis.templatewrappers.PaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.redis.templatewrappers.RedisTemplateWrapperBuilder;
import it.pagopa.transactions.repositories.TransactionCacheInfo;
import it.pagopa.transactions.repositories.TransactionTemplateWrapper;
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
    public TransactionTemplateWrapper transactionTemplateWrapper(
                                                                 RedisConnectionFactory redisConnectionFactory,
                                                                 @Value(
                                                                     "${transactionDocument.ttl}"
                                                                 ) int transactionDocumentTtl
    ) {
        RedisTemplate<String, TransactionCacheInfo> redisTemplate = new RedisTemplate<>();
        Jackson2JsonRedisSerializer<TransactionCacheInfo> jacksonRedisSerializer = new Jackson2JsonRedisSerializer<>(
                TransactionCacheInfo.class
        );

        redisTemplate.setConnectionFactory(redisConnectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(jacksonRedisSerializer);
        redisTemplate.afterPropertiesSet();

        return new TransactionTemplateWrapper(
                redisTemplate,
                "transaction",
                Duration.ofSeconds(transactionDocumentTtl)
        );
    }

}
