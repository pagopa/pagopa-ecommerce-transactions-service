package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.domain.NoticeCode;
import it.pagopa.ecommerce.commons.domain.PaymentToken;
import it.pagopa.ecommerce.commons.domain.TransactionActivationRequested;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestsInfoRepository;
import it.pagopa.transactions.client.NodoPerPM;
import it.pagopa.transactions.commands.TransactionActivateResultCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@Slf4j
public class TransactionActivateResultHandler
    implements CommandHandler<TransactionActivateResultCommand, Mono<TransactionActivatedEvent>> {

  @Autowired
  private TransactionsEventStoreRepository<TransactionActivatedData>
      transactionEventStoreRepository;

  @Autowired private NodoPerPM nodoPerPM;

  @Autowired private PaymentRequestsInfoRepository paymentRequestsInfoRepository;

  @Autowired
  @Qualifier("transactionActivatedQueueAsyncClient")
  QueueAsyncClient transactionActivatedQueueAsyncClient;

  @Value("${payment.token.timeout}")
  Integer paymentTokenTimeout;

  @Override
  public Mono<TransactionActivatedEvent> handle(TransactionActivateResultCommand command) {

    final TransactionActivationRequested transactionActivationRequested =
        command.getData().transactionActivationRequested();

    final String transactionId =
        command.getData().transactionActivationRequested().getTransactionId().value().toString();

    return Mono.just(command)
        .filter(
            commandData ->
                commandData.getData().transactionActivationRequested().getStatus()
                    == TransactionStatusDto.ACTIVATION_REQUESTED)
        .switchIfEmpty(Mono.error(new AlreadyProcessedException(command.getRptId())))
        .flatMap(
            commandData -> {
              final String paymentToken =
                  commandData.getData().activationResultData().getPaymentToken();

              return nodoPerPM
                  .chiediInformazioniPagamento(paymentToken)
                  .doOnError(
                      throwable -> {
                        log.error(
                            "chiediInformazioniPagamento failed for paymentToken {}", paymentToken);
                        throw new TransactionNotFoundException(
                            "chiediInformazioniPagamento failed for paymentToken " + paymentToken);
                      })
                  .doOnSuccess(
                      a ->
                          log.info(
                              "chiediInformazioniPagamento succeded for paymentToken {}",
                              paymentToken));
            })
        .flatMap(
            informazioniPagamentoDto ->
                paymentRequestsInfoRepository
                    .findById(command.getRptId())
                    .map(Mono::just)
                    .orElseGet(Mono::empty))
        .switchIfEmpty(
            Mono.error(
                new TransactionNotFoundException(
                    "Transaction not found for rptID "
                        + command.getRptId().value()
                        + " with paymentToken "
                        + command.getData().activationResultData().getPaymentToken())))
        .flatMap(
            paymentRequestInfo -> {
              log.info(
                  "paymentRequestsInfoRepository findById info for rptID {} succeeded",
                  command.getRptId().value());
              // FIXME check why this repository is not a reactive repository
              return Mono.just(
                  paymentRequestsInfoRepository.save(
                      new PaymentRequestInfo(
                          paymentRequestInfo.id(),
                          paymentRequestInfo.paFiscalCode(),
                          paymentRequestInfo.paName(),
                          paymentRequestInfo.description(),
                          paymentRequestInfo.amount(),
                          paymentRequestInfo.dueDate(),
                          paymentRequestInfo.isNM3(),
                          command.getData().activationResultData().getPaymentToken(),
                          paymentRequestInfo.idempotencyKey())));
            })
        .flatMap(
            saved -> {

                Optional<NoticeCode> noticeCode = transactionActivationRequested.getNoticeCodes().stream().filter(n -> n.rptId().equals(saved.id())).findFirst();
                if(noticeCode.isEmpty()) {
                    throw new RuntimeException();
                }
                noticeCode.ifPresent(noticeCode1 -> {
                    transactionActivationRequested.getNoticeCodes().remove(noticeCode);
                    transactionActivationRequested.getNoticeCodes().add(new NoticeCode(new PaymentToken(saved.paymentToken()),noticeCode1.rptId(),noticeCode1.transactionAmount(),noticeCode1.transactionDescription()));

                });

                TransactionActivatedData data =new TransactionActivatedData(
                        transactionActivationRequested.getEmail().value(),
                        transactionActivationRequested.getNoticeCodes().stream()
                                .map(noticeCode2 -> new it.pagopa.ecommerce.commons.documents.NoticeCode(noticeCode2.paymentToken().value(), noticeCode2.rptId().value(), noticeCode2.transactionDescription().value(), noticeCode2.transactionAmount().value()))
                                .collect(Collectors.toList()),
                        null,
                        null);


              TransactionActivatedEvent transactionActivatedEvent =
                  new TransactionActivatedEvent(
                      transactionId,
                      transactionActivationRequested.getNoticeCodes().stream()
                          .map(noticeCode2 -> new it.pagopa.ecommerce.commons.documents.NoticeCode(noticeCode2.paymentToken().value(), noticeCode2.rptId().value(), noticeCode2.transactionDescription().value(), noticeCode2.transactionAmount().value()))
                          .collect(Collectors.toList()),
                      data);

              log.info("Saving TransactionActivatedEvent {}", transactionActivatedEvent);
                return transactionEventStoreRepository
                        .save(transactionActivatedEvent)
                        .then(
                                transactionActivatedQueueAsyncClient.sendMessageWithResponse(
                                        BinaryData.fromObject(transactionActivatedEvent),
                                        Duration.ofSeconds(paymentTokenTimeout),
                                        null))
                        .then(Mono.just(transactionActivatedEvent))
                        .doOnError(
                                exception ->
                                    log.error(
                                            "Error to generate event TRANSACTION_ACTIVATED_EVENT for rptIds {} and transactionId {} - error {}",
                                            String.join(",",transactionActivatedEvent.getNoticeCodes().stream().map(noticeCode1 -> noticeCode1.getRptId()).toList()),
                                            transactionActivatedEvent.getTransactionId(),
                                            exception.getMessage()))
                        .doOnNext(
                                event ->
                                        log.info(
                                                "Generated event TRANSACTION_ACTIVATED_EVENT for rptIds {} and transactionId {}",
                                                String.join(",", event.getNoticeCodes().stream().map(noticeCode1 -> noticeCode1.getRptId()).toList()),
                                                event.getTransactionId()));
            });
  }
}
