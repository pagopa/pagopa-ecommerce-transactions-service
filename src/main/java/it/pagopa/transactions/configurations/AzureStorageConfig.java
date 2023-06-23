package it.pagopa.transactions.configurations;

import com.azure.core.http.*;
import com.azure.core.http.policy.HttpLogDetailLevel;
import com.azure.core.http.policy.HttpLogOptions;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.core.util.HttpClientOptions;
import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.QueueClientBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Set;

@Configuration
public class AzureStorageConfig {
    @Bean("transactionActivatedQueueAsyncClient")
    @Qualifier
    public QueueAsyncClient transactionActivatedQueueAsyncClient(
                                                                 @Value(
                                                                     "${azurestorage.connectionstringtransient}"
                                                                 ) String storageConnectionString,
                                                                 @Value(
                                                                     "${azurestorage.queues.transactionexpiration.name}"
                                                                 ) String queueName
    ) {
        return buildQueueAsyncClient(storageConnectionString, queueName);
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
        QueueAsyncClient queueAsyncClient = new QueueClientBuilder()
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

                            return next.process();
                        }
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
