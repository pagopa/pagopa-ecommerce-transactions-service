package it.pagopa.transactions.configurations;

import it.pagopa.ecommerce.commons.redis.RedisTemplateWrapperBuilder;
import it.pagopa.ecommerce.commons.redis.templatewrappers.PaymentRequestInfoRedisTemplateWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

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
}
