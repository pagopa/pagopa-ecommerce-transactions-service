package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.generated.transactions.model.ObjectFactory;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.transactions.client.EcommerceSessionsClient;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.documents.TransactionActivatedData;
import it.pagopa.transactions.domain.IdempotencyKey;
import it.pagopa.transactions.domain.RptId;
import it.pagopa.transactions.projections.TransactionsProjection;
import it.pagopa.transactions.repositories.PaymentRequestInfo;
import it.pagopa.transactions.repositories.PaymentRequestsInfoRepository;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.NodoConnectionString;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
class TransactionInitializerHandlerTest {

  @InjectMocks private TransactionActivateHandler handler;

  @Mock private PaymentRequestsInfoRepository paymentRequestInfoRepository;
  @Mock private ObjectFactory objectFactory;
  @Mock private NodeForPspClient nodeForPspClient;
  @Mock private EcommerceSessionsClient ecommerceSessionsClient;

  @Mock
  private TransactionsEventStoreRepository<TransactionActivatedData> transactionEventStoreRepository;

  @Mock private NodoConnectionString nodoConnectionParams;

  @Test
  void shouldHandleCommandForNM3CachedPaymentRequest() {
    RptId rptId = new RptId("77777777777302016723749670035");
    IdempotencyKey idempotencyKey = new IdempotencyKey("32009090901", "aabbccddee");
    String transactionId = UUID.randomUUID().toString();
    String paymentToken = UUID.randomUUID().toString();
    String sessionToken = UUID.randomUUID().toString();
    String paName = "paName";
    String paTaxcode = "77777777777";
    String description = "Description";
    Integer amount = Integer.valueOf(1000);

    NewTransactionRequestDto requestDto = new NewTransactionRequestDto();
    requestDto.setRptId(rptId.value());
    requestDto.setEmail("jhon.doe@email.com");
    requestDto.setAmount(1200);

    TransactionActivateCommand command = new TransactionActivateCommand(rptId, requestDto);

    PaymentRequestInfo paymentRequestInfoCached =
        new PaymentRequestInfo(
            rptId,
            paTaxcode,
            paName,
            description,
            amount,
            null,
            true,
            paymentToken,
            idempotencyKey);

    SessionDataDto sessionDataDto =
        new SessionDataDto()
            .email(requestDto.getEmail())
            .sessionToken(sessionToken)
            .paymentToken(paymentToken)
            .rptId(rptId.value())
            .transactionId(transactionId);

    /** preconditions */
    Mockito.when(paymentRequestInfoRepository.findById(rptId))
        .thenReturn(Optional.of(paymentRequestInfoCached));
    Mockito.when(transactionEventStoreRepository.save(Mockito.any())).thenReturn(Mono.empty());
    Mockito.when(paymentRequestInfoRepository.save(Mockito.any(PaymentRequestInfo.class)))
        .thenReturn(paymentRequestInfoCached);
    Mockito.when(ecommerceSessionsClient.createSessionToken(Mockito.any()))
        .thenReturn(Mono.just(sessionDataDto));

    /** preconditions */
    NewTransactionResponseDto response = handler.handle(command).block();

    /** asserts */
    Mockito.verify(paymentRequestInfoRepository, Mockito.times(1)).findById(rptId);
    Mockito.verify(ecommerceSessionsClient, Mockito.times(1)).createSessionToken(Mockito.any());

    assertEquals(sessionDataDto.getRptId(), response.getRptId());
    assertEquals(sessionDataDto.getPaymentToken(), response.getPaymentToken());
    assertEquals(paymentRequestInfoCached.paymentToken(), response.getPaymentToken());
    assertNotNull(paymentRequestInfoCached.id());
  }

  @Test
  void transactionsProjectionTests() {
    String TEST_RPTID = "77777777777302016723749670035";
    String TEST_TOKEN = "token";

    TransactionsProjection<NewTransactionResponseDto> transactionsProjection =
        new TransactionsProjection<>();
    transactionsProjection.setData(
        new NewTransactionResponseDto()
            .amount(1)
            .rptId(TEST_RPTID)
            .paymentToken(TEST_TOKEN)
            .authToken(TEST_TOKEN)
            .reason(""));

    TransactionsProjection<NewTransactionResponseDto> differentTransactionsProjection =
        new TransactionsProjection<>();
    differentTransactionsProjection.setData(
        new NewTransactionResponseDto()
            .amount(1)
            .rptId(TEST_RPTID)
            .paymentToken(TEST_TOKEN)
            .authToken(TEST_TOKEN)
            .reason(""));

    differentTransactionsProjection.setRptId(new RptId(TEST_RPTID));

    assertFalse(transactionsProjection.equals(differentTransactionsProjection));
    assertEquals(
        Boolean.TRUE,
        transactionsProjection.getData().equals(differentTransactionsProjection.getData()));
  }
}
