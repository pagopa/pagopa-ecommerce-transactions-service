package it.pagopa.transactions.client;

import com.azure.core.util.BinaryData;
import com.azure.core.util.serializer.JsonSerializer;
import com.azure.storage.queue.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.queues.TracingUtilsTests;
import it.pagopa.transactions.configurations.AzureStorageConfig;
import it.pagopa.transactions.utils.Queues;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.test.StepVerifier;

import java.util.UUID;

import static it.pagopa.transactions.client.WalletAsyncQueueClient.WALLET_USED_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WalletAsyncQueueClientTest {

  private WalletAsyncQueueClient walletClient;

  private final JsonSerializer jsonSerializer = new AzureStorageConfig().jsonSerializerV2();
  private final QueueAsyncClient walletUsageQueueAsyncClient = Mockito.mock(QueueAsyncClient.class);
  private final TracingUtils tracingUtils = TracingUtilsTests.getMock();

  @BeforeEach
  void setup() {
    walletClient = new WalletAsyncQueueClient(walletUsageQueueAsyncClient, jsonSerializer);
    reset(walletUsageQueueAsyncClient);
  }

  @Test
  void shouldEmitWalletUsedEventWithTracingInfo() {
    final var walletId = UUID.randomUUID().toString();
    final var argumentCaptor = ArgumentCaptor.forClass(BinaryData.class);

    when(walletUsageQueueAsyncClient.sendMessageWithResponse(any(BinaryData.class), any(), any()))
            .thenReturn(Queues.QUEUE_SUCCESSFUL_RESPONSE);

    StepVerifier.create(
            tracingUtils.traceMono("span", info -> walletClient.fireWalletLastUsageEvent(
                    walletId,
                    Transaction.ClientId.CHECKOUT,
                    info
            ))
    ).assertNext(it -> assertEquals(200, it.getStatusCode())).verifyComplete();

    verify(walletUsageQueueAsyncClient, times(1)).sendMessageWithResponse(
            argumentCaptor.capture(),
            any(),
            any()
    );

    final var queueEvent = argumentCaptor.getValue().toObject(WalletAsyncQueueClient.QueueEvent.class, jsonSerializer);

    assertEquals(queueEvent.data().getType(), WALLET_USED_TYPE);
    assertEquals(queueEvent.data().clientId(), Transaction.ClientId.CHECKOUT.name());
    assertEquals(queueEvent.data().walletId(), walletId);
    assertNotNull(queueEvent.tracingInfo());
  }

}