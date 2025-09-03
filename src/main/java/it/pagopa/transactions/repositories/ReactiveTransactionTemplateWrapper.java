package it.pagopa.transactions.repositories;

import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.ReactiveRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.redis.templatewrappers.RedisTemplateWrapper;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;

public class ReactiveTransactionTemplateWrapper extends ReactiveRedisTemplateWrapper<TransactionCacheInfo> {
    /**
     * Primary constructor
     *
     * @param reactiveRedisTemplate inner reactive redis template
     * @param keyspace              keyspace associated to this wrapper
     * @param ttl                   time to live for keys
     */
    public ReactiveTransactionTemplateWrapper(
            ReactiveRedisTemplate<String, TransactionCacheInfo> reactiveRedisTemplate,
            String keyspace,
            Duration ttl
    ) {
        super(reactiveRedisTemplate, keyspace, ttl);
    }

    @Override
    protected String getKeyFromEntity(TransactionCacheInfo value) {
        return value.transactionId().toString();
    }
}
