package it.pagopa.transactions.configurations;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.util.serializer.JsonSerializer;
import com.azure.storage.queue.QueueClientBuilder;
import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.queues.StrictJsonSerializerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
public class AzureStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(AzureStorageConfig.class);

    @Bean
    public JsonSerializer jsonSerializer() {
        return new StrictJsonSerializerProvider().createInstance();
    }

    @Bean("transactionActivatedQueueAsyncClient")
    @Qualifier
    public QueueAsyncClient transactionActivatedQueueAsyncClient(
                                                                 @Value(
                                                                     "${azurestorage.connectionstringtransient}"

                                                                 ) String storageConnectionString,
                                                                 @Value(
                                                                     "${azurestorage.queues.transactionexpiration.name}"
                                                                 ) String queueName,
                                                                 JsonSerializer jsonSerializer
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName, jsonSerializer);
    }

    @Bean("transactionRefundQueueAsyncClient")
    @Qualifier
    public QueueAsyncClient transactionRefundQueueAsyncClient(
                                                              @Value(
                                                                  "${azurestorage.connectionstringtransient}"
                                                              ) String storageConnectionString,
                                                              @Value(
                                                                  "${azurestorage.queues.transactionrefund.name}"
                                                              ) String queueName,
                                                              JsonSerializer jsonSerializer
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName, jsonSerializer);
    }

    @Bean("transactionClosureRetryQueueAsyncClient")
    public QueueAsyncClient transactionClosureRetryQueueAsyncClient(
                                                                    @Value(
                                                                        "${azurestorage.connectionstringtransient}"
                                                                    ) String storageConnectionString,
                                                                    @Value(
                                                                        "${azurestorage.queues.transactionclosepaymentretry.name}"
                                                                    ) String queueName,
                                                                    JsonSerializer jsonSerializer
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName, jsonSerializer);
    }

    @Bean("transactionClosureQueueAsyncClient")
    public QueueAsyncClient transactionClosureQueueAsyncClient(
                                                               @Value(
                                                                   "${azurestorage.connectionstringtransient}"
                                                               ) String storageConnectionString,
                                                               @Value(
                                                                   "${azurestorage.queues.transactionclosepayment.name}"
                                                               ) String queueName,
                                                               JsonSerializer jsonSerializer
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName, jsonSerializer);
    }

    @Bean("transactionNotificationRequestedQueueAsyncClient")
    public QueueAsyncClient transactionNotificationRequestedQueueAsyncClient(
                                                                             @Value(
                                                                                 "${azurestorage.connectionstringtransient}"
                                                                             ) String storageConnectionString,
                                                                             @Value(
                                                                                 "${azurestorage.queues.transactionnotificationrequested.name}"
                                                                             ) String queueName,
                                                                             JsonSerializer jsonSerializer
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName, jsonSerializer);
    }

    private QueueAsyncClient buildQueueAsyncClient(
                                                   String storageConnectionString,
                                                   String queueName,
                                                   JsonSerializer jsonSerializer
    ) {
        com.azure.storage.queue.QueueAsyncClient queueAsyncClient = new QueueClientBuilder()
                .connectionString(storageConnectionString)
                .queueName(queueName)
                .buildAsyncClient();
        queueAsyncClient.createIfNotExists().block();

        return new QueueAsyncClient(queueAsyncClient, jsonSerializer);
    }
}
