package it.pagopa.transactions.configurations;

import it.pagopa.ecommerce.commons.redis.RedisTemplateWrapperBuilder;
import it.pagopa.ecommerce.commons.redis.templatewrappers.PaymentRequestInfoRedisTemplateWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration
public class RedisConfig {

    @Bean
    public PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoWrapper(
                                                                            RedisConnectionFactory redisConnectionFactory
    ) {
        return RedisTemplateWrapperBuilder.buildPaymentRequestInfoRedisTemplateWrapper(redisConnectionFactory);
    }
}
