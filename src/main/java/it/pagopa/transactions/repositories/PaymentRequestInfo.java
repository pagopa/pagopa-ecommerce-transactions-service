package it.pagopa.transactions.repositories;

import it.pagopa.transactions.domain.IdempotencyKey;
import it.pagopa.transactions.domain.RptId;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceConstructor;
import org.springframework.data.redis.core.RedisHash;

import javax.validation.constraints.Pattern;

@RedisHash(value = "keys", timeToLive = 10 * 60)
public record PaymentRequestInfo(@Id RptId id, String paFiscalCode, String paName,
                                 String description, Integer amount,
                                 @Pattern(regexp = "([a-zA-Z\\d]{1,35})|(RF\\d{2}[a-zA-Z\\d]{1,21})")
                                 String dueDate,
                                 Boolean isNM3,
                                 String paymentToken,
                                 IdempotencyKey idempotencyKey) {
    @PersistenceConstructor
    public PaymentRequestInfo {
    }
}
