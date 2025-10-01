package it.pagopa.transactions.repositories;

import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.ReactiveRedisTemplateWrapper;
import org.springframework.data.redis.core.ReactiveRedisTemplate;

import java.time.Duration;

public class TransactionTemplateWrapper extends ReactiveRedisTemplateWrapper<TransactionCacheInfo> {
    /**
     * Primary constructor
     *
     * @param reactiveRedisTemplate inner reactive redis template
     * @param keyspace              keyspace associated to this wrapper
     * @param ttl                   time to live for keys
     */
    public TransactionTemplateWrapper(
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
