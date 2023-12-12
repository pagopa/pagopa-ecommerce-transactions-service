package it.pagopa.transactions;

import it.pagopa.ecommerce.commons.ConfigScan;
import it.pagopa.transactions.configurations.NpgSessionUrlConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync(proxyTargetClass = true)
@SpringBootApplication
@EnableRedisRepositories(basePackages = "it.pagopa.ecommerce.commons.repositories")
@EnableConfigurationProperties(NpgSessionUrlConfig.class)
@Import(ConfigScan.class)
@Slf4j
public class PagopaEcommerceTransactionsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PagopaEcommerceTransactionsApplication.class, args);
    }
}
