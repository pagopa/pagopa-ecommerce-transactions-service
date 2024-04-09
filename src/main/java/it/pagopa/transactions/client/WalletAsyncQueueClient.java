package it.pagopa.transactions.client;

import com.azure.core.http.rest.Response;
import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import com.azure.storage.queue.models.SendMessageResult;
import com.fasterxml.jackson.annotation.JsonGetter;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.queues.TracingInfo;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class WalletAsyncQueueClient {

  private static final String WALLET_USED_KIND = "WalletUsed";

  private final QueueAsyncClient walletUsageQueueAsyncClient;

  public WalletAsyncQueueClient(QueueAsyncClient walletUsageQueueAsyncClient) {
    this.walletUsageQueueAsyncClient = walletUsageQueueAsyncClient;
  }

  public Mono<Response<SendMessageResult>> fireWalletLastUsageEvent(
          String walletId,
          Transaction.ClientId clientId,
          @Nullable TracingInfo tracingInfo
  ) {
    final var event = new WalletUsedEvent(
            UUID.randomUUID().toString(),
            Instant.now(),
            walletId,
            clientId.name()
    );

    return BinaryData.fromObjectAsync(new QueueEvent(event, tracingInfo))
            .flatMap(it -> walletUsageQueueAsyncClient.sendMessageWithResponse(it, Duration.ZERO, Duration.ZERO));
  }

  public record QueueEvent(
          WalletUsedEvent data,
          TracingInfo tracingInfo
  ) {}

  public record WalletUsedEvent(
          String eventId, Instant createdAt, String walletId, String clientId
  ) {
    @JsonGetter
    public String getType() {
      return WALLET_USED_KIND;
    }
  }
}
