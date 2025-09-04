package it.pagopa.transactions.configurations;

import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.ReactiveExclusiveLockDocumentWrapper;
import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.v2.ReactivePaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.v2.ReactiveRedisTemplateWrapperBuilder;
import it.pagopa.ecommerce.commons.repositories.ExclusiveLockDocument;
import it.pagopa.transactions.repositories.ReactiveTransactionTemplateWrapper;
import it.pagopa.transactions.repositories.TransactionCacheInfo;
import it.pagopa.transactions.repositories.TransactionTemplateWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
public class RedisConfig {

//    @Bean
//    public PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoWrapper(
//                                                                            RedisConnectionFactory redisConnectionFactory,
//                                                                            @Value(
//                                                                                "${payment.token.validity}"
//                                                                            ) Integer paymentTokenTimeout
//    ) {
//        // PaymentRequestInfo entities will have the same TTL as paymentTokenTimeout
//        // value
//        return RedisTemplateWrapperBuilder.buildPaymentRequestInfoRedisTemplateWrapper(
//                redisConnectionFactory,
//                Duration.ofSeconds(paymentTokenTimeout)
//        );
//    }
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

//    @Bean
//    public TransactionTemplateWrapper transactionTemplateWrapper(
//                                                                 RedisConnectionFactory reactiveRedisConnectionFactory,
//                                                                 @Value(
//                                                                     "${transactionDocument.ttl}"
//                                                                 ) int transactionDocumentTtl
//    ) {
//        RedisTemplate<String, TransactionCacheInfo> redisTemplate = new RedisTemplate<>();
//        Jackson2JsonRedisSerializer<TransactionCacheInfo> jacksonRedisSerializer = new Jackson2JsonRedisSerializer<>(
//                TransactionCacheInfo.class
//        );
//
//        redisTemplate.setConnectionFactory(redisConnectionFactory);
//        redisTemplate.setKeySerializer(new StringRedisSerializer());
//        redisTemplate.setValueSerializer(jacksonRedisSerializer);
//        redisTemplate.afterPropertiesSet();
//
//        return new TransactionTemplateWrapper(
//                redisTemplate,
//                "transaction",
//                Duration.ofSeconds(transactionDocumentTtl)
//        );
//    }
    @Bean
    public ReactiveTransactionTemplateWrapper transactionTemplateWrapper(
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

        return new ReactiveTransactionTemplateWrapper(
                reactiveTemplate,
                "transaction",
                Duration.ofSeconds(transactionDocumentTtl)
        );
    }

//    @Bean
//    public ExclusiveLockDocumentWrapper exclusiveLockDocumentWrapper(
//                                                                     RedisConnectionFactory redisConnectionFactory,
//                                                                     @Value(
//                                                                         "${exclusiveLockDocument.ttlSeconds}"
//                                                                     ) int exclusiveLockTtlSeconds
//    ) {
//        RedisTemplate<String, ExclusiveLockDocument> redisTemplate = new RedisTemplate<>();
//        Jackson2JsonRedisSerializer<ExclusiveLockDocument> jacksonRedisSerializer = new Jackson2JsonRedisSerializer<>(
//                ExclusiveLockDocument.class
//        );
//
//        redisTemplate.setConnectionFactory(redisConnectionFactory);
//        redisTemplate.setKeySerializer(new StringRedisSerializer());
//        redisTemplate.setValueSerializer(jacksonRedisSerializer);
//        redisTemplate.afterPropertiesSet();
//
//        return new ExclusiveLockDocumentWrapper(
//                redisTemplate,
//                "exclusiveLocks",
//                Duration.ofSeconds(exclusiveLockTtlSeconds)
//        );
//    }

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
