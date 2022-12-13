package it.pagopa.transactions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync(proxyTargetClass = true)
@SpringBootApplication
public class PagopaEcommerceTransactionsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PagopaEcommerceTransactionsApplication.class, args);
    }

}
