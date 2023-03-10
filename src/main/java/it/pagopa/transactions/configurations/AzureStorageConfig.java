package it.pagopa.transactions.configurations;

import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.QueueClientBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AzureStorageConfig {
    @Bean
    public QueueAsyncClient queueAsyncClient(
                                             @Value("${azurestorage.connectionstring}") String storageConnectionString,
                                             @Value(
                                                 "${azurestorage.queues.transactionauthrequestedtevents.name}"
                                             ) String queueEventInitName
    ) {
        QueueAsyncClient queueAsyncClient = new QueueClientBuilder()
                .connectionString(storageConnectionString)
                .queueName(queueEventInitName)
                .buildAsyncClient();
        queueAsyncClient.createIfNotExists().block();
        return queueAsyncClient;
    }

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

    @Bean("transactionClosureSentEventQueueAsyncClient")
    public QueueAsyncClient transactionClosureSentEventQueueAsyncClient(
                                                                        @Value(
                                                                            "${azurestorage.connectionstring}"
                                                                        ) String storageConnectionString,
                                                                        @Value(
                                                                            "${azurestorage.queues.transactionclosuresentevents.name}"
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
