package it.pagopa.transactions.configurations;

import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.ReactiveExclusiveLockDocumentWrapper;
import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.v2.ReactivePaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.v2.ReactiveRedisTemplateWrapperBuilder;
import it.pagopa.ecommerce.commons.repositories.ExclusiveLockDocument;
import it.pagopa.transactions.repositories.TransactionCacheInfo;
import it.pagopa.transactions.repositories.TransactionTemplateWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class RedisConfig {

    @Bean
    public ReactivePaymentRequestInfoRedisTemplateWrapper paymentRequestInfoWrapper(
                                                                                    ReactiveRedisConnectionFactory reactiveRedisConnectionFactory,
                                                                                    @Value(
                                                                                        "${payment.token.validity}"
                                                                                    ) Integer paymentTokenTimeout
    ) {
        return ReactiveRedisTemplateWrapperBuilder.buildPaymentRequestInfoRedisTemplateWrapper(
                reactiveRedisConnectionFactory,
                Duration.ofSeconds(paymentTokenTimeout)
        );
    }

    @Bean
    public TransactionTemplateWrapper transactionTemplateWrapper(
                                                                 ReactiveRedisConnectionFactory reactiveRedisConnectionFactory,
                                                                 @Value(
                                                                     "${transactionDocument.ttl}"
                                                                 ) int transactionDocumentTtl
    ) {
        // serializer
        StringRedisSerializer keySer = new StringRedisSerializer();
        Jackson2JsonRedisSerializer<TransactionCacheInfo> valueSer = new Jackson2JsonRedisSerializer<>(
                TransactionCacheInfo.class
        );

        // serialization context
        RedisSerializationContext<String, TransactionCacheInfo> ctx = RedisSerializationContext
                .<String, TransactionCacheInfo>newSerializationContext(keySer)
                .key(keySer)
                .value(valueSer)
                .hashKey(keySer)
                .hashValue(valueSer)
                .build();

        // reactive template
        ReactiveRedisTemplate<String, TransactionCacheInfo> reactiveTemplate = new ReactiveRedisTemplate<>(
                reactiveRedisConnectionFactory,
                ctx
        );

        return new TransactionTemplateWrapper(
                reactiveTemplate,
                "transaction",
                Duration.ofSeconds(transactionDocumentTtl)
        );
    }

    @Bean
    public ReactiveExclusiveLockDocumentWrapper exclusiveLockDocumentWrapper(
                                                                             ReactiveRedisConnectionFactory reactiveRedisConnectionFactory,
                                                                             @Value(
                                                                                 "${exclusiveLockDocument.ttlSeconds}"
                                                                             ) int exclusiveLockTtlSeconds
    ) {
        // serializer
        StringRedisSerializer keySer = new StringRedisSerializer();
        Jackson2JsonRedisSerializer<ExclusiveLockDocument> valueSer = new Jackson2JsonRedisSerializer<>(
                ExclusiveLockDocument.class
        );

        // serialization context
        RedisSerializationContext<String, ExclusiveLockDocument> ctx = RedisSerializationContext
                .<String, ExclusiveLockDocument>newSerializationContext(keySer)
                .key(keySer)
                .value(valueSer)
                .hashKey(keySer)
                .hashValue(valueSer)
                .build();

        // reactive template
        ReactiveRedisTemplate<String, ExclusiveLockDocument> reactiveTemplate = new ReactiveRedisTemplate<>(
                reactiveRedisConnectionFactory,
                ctx
        );

        return new ReactiveExclusiveLockDocumentWrapper(
                reactiveTemplate,
                "exclusiveLocks",
                Duration.ofSeconds(exclusiveLockTtlSeconds)
        );
    }

}
