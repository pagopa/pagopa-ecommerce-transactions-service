package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.transactions.client.EcommerceSessionsClient;
import it.pagopa.transactions.commands.TransactionInitializeCommand;
import it.pagopa.transactions.documents.TransactionEvent;
import it.pagopa.transactions.documents.TransactionInitData;
import it.pagopa.transactions.documents.TransactionInitEvent;
import it.pagopa.transactions.domain.IdempotencyKey;
import it.pagopa.transactions.domain.RptId;
import it.pagopa.transactions.repositories.*;
import it.pagopa.transactions.utils.NodoOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class TransactionInizializeHandler
    implements CommandHandler<TransactionInitializeCommand, Mono<NewTransactionResponseDto>> {

  private static final String ALPHANUMERICS =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String PSP_PAGOPA_ECOMMERCE_FISCAL_CODE = "00000000000";

  @Autowired PaymentRequestsInfoRepository paymentRequestsInfoRepository;

  @Autowired TransactionsEventStoreRepository<TransactionInitData> transactionEventStoreRepository;

  @Autowired EcommerceSessionsClient ecommerceSessionsClient;

  @Autowired NodoOperations nodoOperations;

  public Mono<NewTransactionResponseDto> handle(TransactionInitializeCommand command) {
    final RptId rptId = command.getRptId();
    final NewTransactionRequestDto newTransactionRequestDto = command.getData();

    return getPaymentRequestInfoFromCache(rptId)
        .doOnNext(
            paymentRequestInfoFromCache ->
                log.info(
                    "PaymentRequestInfo cache hit for {}: {}",
                    rptId,
                    paymentRequestInfoFromCache != null))
        .switchIfEmpty(
            Mono.defer(
                () ->
                    Mono.just(
                            new PaymentRequestInfo(
                                rptId, null, null, null, null, null, false, null, null))
                        .doOnSuccess(x -> log.info("PaymentRequestInfo cache miss for {}", rptId))))
        .flatMap(
            partialPaymentRequestInfo -> {
              final Boolean isValidPaymentToken =
                  partialPaymentRequestInfo.paymentToken() != null
                      && !partialPaymentRequestInfo.paymentToken().trim().isEmpty();

              return isValidPaymentToken
                  ? Mono.just(partialPaymentRequestInfo)
                      .doOnSuccess(
                          p ->
                              log.info(
                                  "PaymentRequestInfo cache hit for {} with valid paymentToken {}",
                                  rptId,
                                  p.paymentToken()))
                  : nodoOperations
                      .activatePaymentRequest(
                          partialPaymentRequestInfo.id(),
                          newTransactionRequestDto.getPaymentContextCode(),
                          partialPaymentRequestInfo.isNM3(),
                          newTransactionRequestDto.getAmount(),
                          partialPaymentRequestInfo.paTaxCode(),
                          partialPaymentRequestInfo.paName(),
                          Optional.ofNullable(partialPaymentRequestInfo.idempotencyKey())
                              .orElseGet(
                                  () ->
                                      new IdempotencyKey(
                                          PSP_PAGOPA_ECOMMERCE_FISCAL_CODE, randomString(10))),
                          partialPaymentRequestInfo.dueDate(),
                          partialPaymentRequestInfo.description())
                      .doOnSuccess(
                          p ->
                              log.info(
                                  "Nodo activation for {} with paymentToken {}",
                                  rptId,
                                  p.paymentToken()));
            })
        .doOnNext(
            paymentRequestInfo -> {
              log.info(
                  "Cache Nodo activation info for {} with paymentToken {}",
                  rptId,
                  paymentRequestInfo.paymentToken());
              paymentRequestsInfoRepository.save(paymentRequestInfo);
            })
        .flatMap(
            paymentRequestInfo -> {
              final String transactionId = UUID.randomUUID().toString();
              TransactionInitData data = new TransactionInitData();
              data.setAmount(paymentRequestInfo.amount().intValue());
              data.setDescription(paymentRequestInfo.description());
              data.setEmail(newTransactionRequestDto.getEmail());

              TransactionEvent<TransactionInitData> transactionInitializedEvent =
                  new TransactionInitEvent(
                      transactionId,
                      newTransactionRequestDto.getRptId(),
                      paymentRequestInfo.paymentToken(),
                      data);

              log.info(
                  "Generated event TRANSACTION_INITIALIZED_EVENT for payment token {}",
                  paymentRequestInfo.paymentToken());
              return transactionEventStoreRepository
                  .save(transactionInitializedEvent)
                  .thenReturn(transactionInitializedEvent);
            })
        .flatMap(
            transactionInitializedEvent -> {
              SessionRequestDto sessionRequest =
                  new SessionRequestDto()
                      .email(transactionInitializedEvent.getData().getEmail())
                      .paymentToken(transactionInitializedEvent.getPaymentToken())
                      .rptId(transactionInitializedEvent.getRptId())
                      .transactionId(transactionInitializedEvent.getTransactionId());

              return ecommerceSessionsClient
                  .createSessionToken(sessionRequest)
                  .map(sessionData -> Tuples.of(sessionData, transactionInitializedEvent));
            })
        .map(
            args -> {
              final SessionDataDto sessionData = args.getT1();
              final TransactionEvent<TransactionInitData> transactionInitializedEvent =
                  args.getT2();

              return new NewTransactionResponseDto()
                  .amount(transactionInitializedEvent.getData().getAmount().intValue())
                  .reason(transactionInitializedEvent.getData().getDescription())
                  .authToken(sessionData.getSessionToken())
                  .transactionId(transactionInitializedEvent.getTransactionId())
                  .paymentToken(
                      transactionInitializedEvent.getPaymentToken() != null
                              && !transactionInitializedEvent.getPaymentToken().isEmpty()
                          ? transactionInitializedEvent.getPaymentToken()
                          : null)
                  .rptId(rptId.value());
            });
  }

  private Mono<PaymentRequestInfo> getPaymentRequestInfoFromCache(RptId rptId) {

    return paymentRequestsInfoRepository.findById(rptId).map(Mono::just).orElseGet(Mono::empty);
  }

  private String randomString(int len) {
    StringBuilder sb = new StringBuilder(len);
    for (int i = 0; i < len; i++) {
      sb.append(ALPHANUMERICS.charAt(RANDOM.nextInt(ALPHANUMERICS.length())));
    }
    return sb.toString();
  }
}
