package it.pagopa.transactions.configurations;

import com.azure.core.util.serializer.JsonSerializer;
import com.azure.storage.queue.QueueClientBuilder;
import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.queues.QueueEvent;
import it.pagopa.ecommerce.commons.queues.StrictJsonSerializerProvider;
import it.pagopa.ecommerce.commons.queues.mixin.serialization.v1.QueueEventMixInEventCodeFieldDiscriminator;
import it.pagopa.ecommerce.commons.queues.mixin.serialization.v2.QueueEventMixInClassFieldDiscriminator;
import it.pagopa.transactions.client.WalletAsyncQueueClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AzureStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(AzureStorageConfig.class);

    @Bean
    public JsonSerializer jsonSerializerV1() {
        return new StrictJsonSerializerProvider()
                .addMixIn(QueueEvent.class, QueueEventMixInEventCodeFieldDiscriminator.class)
                .createInstance();
    }

    @Bean
    public JsonSerializer jsonSerializerV2() {
        return new StrictJsonSerializerProvider()
                .addMixIn(QueueEvent.class, QueueEventMixInClassFieldDiscriminator.class)
                .createInstance();
    }

    @Bean("transactionActivatedQueueAsyncClientV1")
    @Qualifier
    public QueueAsyncClient transactionActivatedQueueAsyncClientV1(
                                                                   @Value(
                                                                       "${azurestorage.connectionstringtransient}"

                                                                   ) String storageConnectionString,
                                                                   @Value(
                                                                       "${azurestorage.queues.transactionexpiration.name}"
                                                                   ) String queueName,
                                                                   JsonSerializer jsonSerializerV1
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName, jsonSerializerV1);
    }

    @Bean("transactionActivatedQueueAsyncClientV2")
    @Qualifier
    public QueueAsyncClient transactionActivatedQueueAsyncClientV2(
                                                                   @Value(
                                                                       "${azurestorage.connectionstringtransient}"

                                                                   ) String storageConnectionString,
                                                                   @Value(
                                                                       "${azurestorage.queues.transactionexpiration.name}"
                                                                   ) String queueName,
                                                                   JsonSerializer jsonSerializerV2
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName, jsonSerializerV2);
    }

    @Bean("transactionRefundQueueAsyncClientV1")
    @Qualifier
    public QueueAsyncClient transactionRefundQueueAsyncClientV1(
                                                                @Value(
                                                                    "${azurestorage.connectionstringtransient}"
                                                                ) String storageConnectionString,
                                                                @Value(
                                                                    "${azurestorage.queues.transactionrefund.name}"
                                                                ) String queueName,
                                                                JsonSerializer jsonSerializerV1
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName, jsonSerializerV1);
    }

    @Bean("transactionClosureRetryQueueAsyncClientV1")
    public QueueAsyncClient transactionClosureRetryQueueAsyncClientV1(
                                                                      @Value(
                                                                          "${azurestorage.connectionstringtransient}"
                                                                      ) String storageConnectionString,
                                                                      @Value(
                                                                          "${azurestorage.queues.transactionclosepaymentretry.name}"
                                                                      ) String queueName,
                                                                      JsonSerializer jsonSerializerV1
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName, jsonSerializerV1);
    }

    @Bean("transactionClosureRetryQueueAsyncClientV2")
    public QueueAsyncClient transactionClosureRetryQueueAsyncClientV2(
                                                                      @Value(
                                                                          "${azurestorage.connectionstringtransient}"
                                                                      ) String storageConnectionString,
                                                                      @Value(
                                                                          "${azurestorage.queues.transactionclosepaymentretry.name}"
                                                                      ) String queueName,
                                                                      JsonSerializer jsonSerializerV2
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName, jsonSerializerV2);
    }

    @Bean("transactionClosureQueueAsyncClientV1")
    public QueueAsyncClient transactionClosureQueueAsyncClientV1(
                                                                 @Value(
                                                                     "${azurestorage.connectionstringtransient}"
                                                                 ) String storageConnectionString,
                                                                 @Value(
                                                                     "${azurestorage.queues.transactionclosepayment.name}"
                                                                 ) String queueName,
                                                                 JsonSerializer jsonSerializerV1
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName, jsonSerializerV1);
    }

    @Bean("transactionClosureQueueAsyncClientV2")
    public QueueAsyncClient transactionClosureQueueAsyncClientV2(
                                                                 @Value(
                                                                     "${azurestorage.connectionstringtransient}"
                                                                 ) String storageConnectionString,
                                                                 @Value(
                                                                     "${azurestorage.queues.transactionclosepayment.name}"
                                                                 ) String queueName,
                                                                 JsonSerializer jsonSerializerV2
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName, jsonSerializerV2);
    }

    @Bean("transactionNotificationRequestedQueueAsyncClientV1")
    public QueueAsyncClient transactionNotificationRequestedQueueAsyncClientV1(
                                                                               @Value(
                                                                                   "${azurestorage.connectionstringtransient}"
                                                                               ) String storageConnectionString,
                                                                               @Value(
                                                                                   "${azurestorage.queues.transactionnotificationrequested.name}"
                                                                               ) String queueName,
                                                                               JsonSerializer jsonSerializerV1
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName, jsonSerializerV1);
    }

    @Bean("transactionNotificationRequestedQueueAsyncClientV2")
    public QueueAsyncClient transactionNotificationRequestedQueueAsyncClientV2(
                                                                               @Value(
                                                                                   "${azurestorage.connectionstringtransient}"
                                                                               ) String storageConnectionString,
                                                                               @Value(
                                                                                   "${azurestorage.queues.transactionnotificationrequested.name}"
                                                                               ) String queueName,
                                                                               JsonSerializer jsonSerializerV2
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName, jsonSerializerV2);
    }

    @Bean("transactionAuthorizationRequestedQueueAsyncClientV2")
    public QueueAsyncClient transactionAuthorizationRequestedQueueAsyncClientV2(
                                                                                @Value(
                                                                                    "${azurestorage.connectionstringtransient}"
                                                                                ) String storageConnectionString,
                                                                                @Value(
                                                                                    "${azurestorage.queues.transactionauthorizationrequested.name}"
                                                                                ) String queueName,
                                                                                JsonSerializer jsonSerializerV2
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName, jsonSerializerV2);
    }

    @Bean("walletAsyncQueueClient")
    @ConditionalOnExpression("${feature.walletusage.enabled:false}")
    public WalletAsyncQueueClient walletUsageQueueAsyncClient(
                                                              @Value(
                                                                  "${azurestorage.wallet.connectionstring}"
                                                              ) String storageConnectionString,
                                                              @Value(
                                                                  "${azurestorage.queues.walletusage.ttlSeconds}"
                                                              ) int secondsTtl,
                                                              @Value(
                                                                  "${azurestorage.queues.walletusage.name}"
                                                              ) String queueName
    ) {
        final var serializer = new StrictJsonSerializerProvider().createInstance();
        final var queueAsyncClient = new QueueClientBuilder()
                .connectionString(storageConnectionString)
                .queueName(queueName)
                .buildAsyncClient();
        queueAsyncClient.createIfNotExists().block();
        return new WalletAsyncQueueClient(queueAsyncClient, secondsTtl, serializer);
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
