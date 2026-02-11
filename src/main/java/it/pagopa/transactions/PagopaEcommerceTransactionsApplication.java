package it.pagopa.transactions;

import it.pagopa.ecommerce.commons.ConfigScan;
import it.pagopa.transactions.configurations.NpgSessionUrlConfig;
import it.pagopa.transactions.configurations.WalletConfig;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync(proxyTargetClass = true)
@SpringBootApplication
@EnableRedisRepositories(basePackages = "it.pagopa.ecommerce.commons.repositories")
@EnableConfigurationProperties(
    {
            NpgSessionUrlConfig.class,
            WalletConfig.class
    }
)
@Import(ConfigScan.class)
@Slf4j
public class PagopaEcommerceTransactionsApplication {
    private static final Logger auditLogger = LoggerFactory.getLogger("auditLogs");

    public static void main(String[] args) {
        SpringApplication.run(PagopaEcommerceTransactionsApplication.class, args);
        auditLogger.info("TEST");
    }
}
