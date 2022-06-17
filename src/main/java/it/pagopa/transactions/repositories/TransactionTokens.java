package it.pagopa.transactions.repositories;

import it.pagopa.transactions.domain.IdempotencyKey;
import it.pagopa.transactions.domain.RptId;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceConstructor;
import org.springframework.data.redis.core.RedisHash;

@RedisHash(value = "keys", timeToLive = 30 * 60)
public record TransactionTokens(@Id RptId id, IdempotencyKey idempotencyKey, String paymentToken) {
    @PersistenceConstructor
    public TransactionTokens {}
}
