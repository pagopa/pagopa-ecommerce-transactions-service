package it.pagopa.transactions.repositories;

import it.pagopa.ecommerce.commons.redis.templatewrappers.RedisTemplateWrapper;
import org.springframework.data.redis.core.RedisTemplate;
import java.time.Duration;

public class TransactionTemplateWrapper extends RedisTemplateWrapper<TransactionDocument> {
    /**
     * Primary constructor
     *
     * @param redisTemplate inner redis template
     * @param keyspace      keyspace associated to this wrapper
     * @param ttl           time to live for keys
     */
    public TransactionTemplateWrapper(
            RedisTemplate<String, TransactionDocument> redisTemplate,
            String keyspace,
            Duration ttl
    ) {
        super(redisTemplate, keyspace, ttl);
    }

    @Override
    protected String getKeyFromEntity(TransactionDocument value) {
        return value.transactionId().toString();
    }
}
