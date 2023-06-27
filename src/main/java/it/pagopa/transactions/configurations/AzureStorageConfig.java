package it.pagopa.transactions.configurations;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.implementation.serializer.DefaultJsonSerializer;
import com.azure.core.util.serializer.JsonSerializer;
import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.QueueClientBuilder;
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
        return new DefaultJsonSerializer();
    }

    @Bean("transactionActivatedQueueAsyncClient")
    @Qualifier
    public it.pagopa.ecommerce.commons.client.QueueAsyncClient transactionActivatedQueueAsyncClient(
                                                                                                    @Value(
                                                                                                        "${azurestorage.connectionstringtransient}"
                                                                                                    ) String storageConnectionString,
                                                                                                    @Value(
                                                                                                        "${azurestorage.queues.transactionexpiration.name}"
                                                                                                    ) String queueName,
                                                                                                    JsonSerializer jsonSerializer
    ) {
        return new it.pagopa.ecommerce.commons.client.QueueAsyncClient(
                buildQueueAsyncClient(storageConnectionString, queueName),
                jsonSerializer
        );
    }

    @Bean("transactionRefundQueueAsyncClient")
    @Qualifier
    public QueueAsyncClient transactionRefundQueueAsyncClient(
                                                              @Value(
                                                                  "${azurestorage.connectionstringtransient}"
                                                              ) String storageConnectionString,
                                                              @Value(
                                                                  "${azurestorage.queues.transactionrefund.name}"
                                                              ) String queueName
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName);
    }

    @Bean("transactionClosureRetryQueueAsyncClient")
    public QueueAsyncClient transactionClosureRetryQueueAsyncClient(
                                                                    @Value(
                                                                        "${azurestorage.connectionstringtransient}"
                                                                    ) String storageConnectionString,
                                                                    @Value(
                                                                        "${azurestorage.queues.transactionclosepaymentretry.name}"
                                                                    ) String queueName
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName);
    }

    @Bean("transactionClosureQueueAsyncClient")
    public QueueAsyncClient transactionClosureQueueAsyncClient(
                                                               @Value(
                                                                   "${azurestorage.connectionstringtransient}"
                                                               ) String storageConnectionString,
                                                               @Value(
                                                                   "${azurestorage.queues.transactionclosepayment.name}"
                                                               ) String queueName
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName);
    }

    @Bean("transactionNotificationRequestedQueueAsyncClient")
    public QueueAsyncClient transactionNotificationRequestedQueueAsyncClient(
                                                                             @Value(
                                                                                 "${azurestorage.connectionstringtransient}"
                                                                             ) String storageConnectionString,
                                                                             @Value(
                                                                                 "${azurestorage.queues.transactionnotificationrequested.name}"
                                                                             ) String queueName
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName);
    }

    private QueueAsyncClient buildQueueAsyncClient(
                                                   String storageConnectionString,
                                                   String queueName
    ) {
        com.azure.storage.queue.QueueAsyncClient queueAsyncClient = new QueueClientBuilder()
                .connectionString(storageConnectionString)
                .queueName(queueName)
                .addPolicy(
                        (
                         context,
                         next
                        ) -> {
                            HttpHeaders requestHeaders = context.getHttpRequest().getHeaders();

                            Set<String> rebrandedHeaders = Set
                                    .of("traceparent", "tracestate", "baggage");

                            for (String header : rebrandedHeaders) {
                                context.getHttpRequest().setHeader("pagopa-" + header, requestHeaders.getValue(header));
                            }

                            context.getHttpRequest().setHeader("pagopa-foo", "bar");

                            return next.process();
                        }
                )
                .addPolicy(
                        ((
                          context,
                          next
                        ) -> {
                            log.info("Request headers: {}", context.getHttpRequest().getHeaders().toMap());
                            return next.process();
                        })
                )
                .httpLogOptions(
                        QueueClientBuilder.getDefaultHttpLogOptions()
                                .setLogLevel(HttpLogDetailLevel.HEADERS)
                                .addAllowedHeaderName("traceparent")
                                .addAllowedHeaderName("pagopa-traceparent")
                                .addAllowedHeaderName("pagopa-tracestate")
                                .addAllowedHeaderName("pagopa-baggage")
                )
                .buildAsyncClient();
        queueAsyncClient.createIfNotExists().block();
        return queueAsyncClient;
    }
}
