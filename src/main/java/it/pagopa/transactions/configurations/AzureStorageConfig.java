package it.pagopa.transactions.configurations;

import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.QueueClientBuilder;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AzureStorageConfig {

    @Bean
    public QueueAsyncClient initEventsQueueClient(
            @Value("${azurestorage.connectionstring}") String storageConnectionString,
            @Value("${azurestorage.queues.transactioninitevents.name}") String queueEventInitName) {
        QueueAsyncClient queueClient = new QueueClientBuilder()
                .connectionString(storageConnectionString)
                .queueName(queueEventInitName)
                .buildAsyncClient();
        queueClient.createIfNotExists();
        return queueClient;
    }
}
