package it.pagopa.transactions.configurations;

import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.QueueClientBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AzureStorageConfig {
    @Bean("transactionActivatedQueueAsyncClient")
    @Qualifier
    public QueueAsyncClient transactionActivatedQueueAsyncClient(
                                                                 @Value(
                                                                     "${azurestorage.connectionstring}"
                                                                 ) String storageConnectionString,
                                                                 @Value(
                                                                     "${azurestorage.queues.transactionexpiration.name}"
                                                                 ) String queueName
    ) {
        QueueAsyncClient queueAsyncClient = new QueueClientBuilder()
                .connectionString(storageConnectionString)
                .queueName(queueName)
                .buildAsyncClient();
        queueAsyncClient.createIfNotExists().block();
        return queueAsyncClient;
    }

    /*
     * @formatter:off
     *
     * Warning java:S4144 - Methods should not have identical implementations
     * Implementation identical to the other queue initialization methods.
     * Suppressed because the method takes a different injected `@Value` parameter, so it's not identical
     * even though SonarCloud flags it as so.
     *
     * @formatter:on
     */
    @SuppressWarnings("java:S4144")
    @Bean("transactionRefundQueueAsyncClient")
    @Qualifier
    public QueueAsyncClient transactionRefundQueueAsyncClient(
                                                              @Value(
                                                                  "${azurestorage.connectionstring}"
                                                              ) String storageConnectionString,
                                                              @Value(
                                                                  "${azurestorage.queues.transactionrefund.name}"
                                                              ) String queueName
    ) {
        QueueAsyncClient queueAsyncClient = new QueueClientBuilder()
                .connectionString(storageConnectionString)
                .queueName(queueName)
                .buildAsyncClient();
        queueAsyncClient.createIfNotExists().block();
        return queueAsyncClient;
    }

    /*
     * @formatter:off
     *
     * Warning java:S4144 - Methods should not have identical implementations
     * Implementation identical to the other queue initialization methods.
     * Suppressed because the method takes a different injected `@Value` parameter, so it's not identical
     * even though SonarCloud flags it as so.
     *
     * @formatter:on
     */
    @SuppressWarnings("java:S4144")
    @Bean("transactionClosureRetryQueueAsyncClient")
    public QueueAsyncClient transactionClosureRetryQueueAsyncClient(
                                                                    @Value(
                                                                        "${azurestorage.connectionstring}"
                                                                    ) String storageConnectionString,
                                                                    @Value(
                                                                        "${azurestorage.queues.transactionclosepaymentretry.name}"
                                                                    ) String queueName
    ) {
        QueueAsyncClient queueAsyncClient = new QueueClientBuilder()
                .connectionString(storageConnectionString)
                .queueName(queueName)
                .buildAsyncClient();
        queueAsyncClient.createIfNotExists().block();
        return queueAsyncClient;
    }
}
