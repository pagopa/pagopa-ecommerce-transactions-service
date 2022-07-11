package it.pagopa.transactions.configurations;

import com.azure.storage.queue.QueueClient;
import com.azure.storage.queue.QueueClientBuilder;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StorageConfig {

    @Bean
    public QueueClient initEventsQueueClient(
            @Value("${storage.connectionstring}") String storageConnectionString,
            @Value("${storage.queues.initevents.name}") String queueEventInitName) {
        QueueClient queueClient = new QueueClientBuilder()
                .connectionString(storageConnectionString)
                .queueName(queueEventInitName)
                .buildClient();
        queueClient.createIfNotExists();
        return queueClient;
    }
}
