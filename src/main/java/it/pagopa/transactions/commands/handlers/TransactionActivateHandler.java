package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.transactions.client.EcommerceSessionsClient;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.documents.TransactionEvent;
import it.pagopa.transactions.documents.TransactionActivatedData;
import it.pagopa.transactions.documents.TransactionActivatedEvent;
import it.pagopa.transactions.domain.RptId;
import it.pagopa.transactions.repositories.*;
import it.pagopa.transactions.utils.NodoOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.util.UUID;

@Slf4j
@Component
public class TransactionActivateHandler
    implements CommandHandler<TransactionActivateCommand, Mono<NewTransactionResponseDto>> {

  @Autowired PaymentRequestsInfoRepository paymentRequestsInfoRepository;

  @Autowired TransactionsEventStoreRepository<TransactionActivatedData> transactionEventStoreRepository;

  @Autowired EcommerceSessionsClient ecommerceSessionsClient;

  @Autowired NodoOperations nodoOperations;

  public Mono<NewTransactionResponseDto> handle(TransactionActivateCommand command) {
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

              return Boolean.TRUE.equals(isValidPaymentToken)
                  ? Mono.just(partialPaymentRequestInfo)
                      .doOnSuccess(
                          p ->
                              log.info(
                                  "PaymentRequestInfo cache hit for {} with valid paymentToken {}",
                                  rptId,
                                  p.paymentToken()))
                  : nodoOperations
                      .activatePaymentRequest(partialPaymentRequestInfo, newTransactionRequestDto)
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
              TransactionActivatedData data = new TransactionActivatedData();
              data.setAmount(paymentRequestInfo.amount());
              data.setDescription(paymentRequestInfo.description());
              data.setEmail(newTransactionRequestDto.getEmail());

              TransactionEvent<TransactionActivatedData> transactionInitializedEvent =
                  new TransactionActivatedEvent(
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
              final TransactionEvent<TransactionActivatedData> transactionInitializedEvent =
                  args.getT2();

              return new NewTransactionResponseDto()
                  .amount(transactionInitializedEvent.getData().getAmount())
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

}
