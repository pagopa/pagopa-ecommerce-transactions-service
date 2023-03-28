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
        return buildAsyncClient(storageConnectionString, queueName);
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
        return buildAsyncClient(storageConnectionString, queueName);
    }


    @Bean("transactionClosureRetryQueueAsyncClient")
    public QueueAsyncClient transactionClosureRetryQueueAsyncClient(
                                                                    @Value(
                                                                        "${azurestorage.connectionstring}"
                                                                    ) String storageConnectionString,
                                                                    @Value(
                                                                        "${azurestorage.queues.transactionclosepaymentretry.name}"
                                                                    ) String queueName
    ) {
        return buildAsyncClient(storageConnectionString, queueName);
    }


    @Bean("transactionClosureQueueAsyncClient")
    public QueueAsyncClient transactionClosureQueueAsyncClient(
            @Value(
                    "${azurestorage.connectionstring}"
            ) String storageConnectionString,
            @Value(
                    "${azurestorage.queues.transactionclosepayment.name}"
            ) String queueName
    ) {
        return buildAsyncClient(storageConnectionString, queueName);
    }

    @Bean("transactionNotificationsRetryQueueAsyncClient")
    public QueueAsyncClient transactionNotificationsRetryQueueAsyncClient(
            @Value(
                    "${azurestorage.connectionstring}"
            ) String storageConnectionString,
            @Value(
                    "${azurestorage.queues.transactionnotificationretry.name}"
            ) String queueName
    ) {
        return buildAsyncClient(storageConnectionString, queueName);
    }

    private QueueAsyncClient buildAsyncClient(String storageConnectionString, String queueName) {
        QueueAsyncClient queueAsyncClient = new QueueClientBuilder()
                .connectionString(storageConnectionString)
                .queueName(queueName)
                .buildAsyncClient();
        queueAsyncClient.createIfNotExists().block();
        return queueAsyncClient;
    }
}
