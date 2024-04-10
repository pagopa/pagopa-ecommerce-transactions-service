package it.pagopa.transactions.client;

import com.azure.core.http.rest.Response;
import com.azure.core.util.BinaryData;
import com.azure.core.util.serializer.JsonSerializer;
import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.models.SendMessageResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.queues.TracingInfo;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class WalletAsyncQueueClient {

    public static final String WALLET_USED_TYPE = "WalletUsed";

    private final QueueAsyncClient walletUsageQueueAsyncClient;
    private final int secondsTtl;
    private final JsonSerializer jsonSerializer;

    public WalletAsyncQueueClient(
            QueueAsyncClient walletUsageQueueAsyncClient,
            int secondsTtl,
            JsonSerializer jsonSerializer
    ) {
        this.walletUsageQueueAsyncClient = walletUsageQueueAsyncClient;
        this.secondsTtl = secondsTtl;
        this.jsonSerializer = jsonSerializer;
    }

    public Mono<Response<SendMessageResult>> fireWalletLastUsageEvent(
                                                                      String walletId,
                                                                      Transaction.ClientId clientId,
                                                                      @Nullable TracingInfo tracingInfo
    ) {
        final var event = new WalletUsedEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                walletId,
                clientId.name()
        );

        return BinaryData.fromObjectAsync(new QueueEvent(event, tracingInfo), jsonSerializer)
                .flatMap(
                        it -> walletUsageQueueAsyncClient
                                .sendMessageWithResponse(it, Duration.ZERO, Duration.ofSeconds(secondsTtl))
                );
    }

    public record QueueEvent(
            WalletUsedEvent data,
            TracingInfo tracingInfo
    ) {
    }

    public record WalletUsedEvent(
            String eventId,
            String creationDate,
            String walletId,
            String clientId
    ) {
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public String getType() {
            return WALLET_USED_TYPE;
        }
    }
}
