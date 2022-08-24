package it.pagopa.transactions.repositories;

import it.pagopa.transactions.domain.RptId;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceConstructor;
import org.springframework.data.redis.core.RedisHash;

import javax.validation.constraints.Pattern;
import java.math.BigDecimal;

@RedisHash(value = "keys", timeToLive = 10 * 60)
public record PaymentRequestInfo(@Id RptId id, String paTaxCode, String paName,
                                 String description, BigDecimal amount,
                                 @Pattern(regexp = "([a-zA-Z\\d]{1,35})|(RF\\d{2}[a-zA-Z\\d]{1,21})")
                                 String dueDate,
                                 Boolean isNM3) {
    @PersistenceConstructor
    public PaymentRequestInfo {
    }
}
