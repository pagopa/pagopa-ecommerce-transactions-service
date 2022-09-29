package it.pagopa.transactions.services;

import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PspDto;
import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentInstrumentsClient;
import it.pagopa.transactions.commands.*;
import it.pagopa.transactions.commands.data.*;
import it.pagopa.transactions.commands.handlers.*;
import it.pagopa.transactions.documents.TransactionActivatedEvent;
import it.pagopa.transactions.documents.TransactionActivationRequestedEvent;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.exceptions.UnsatisfiablePspRequestException;
import it.pagopa.transactions.projections.handlers.*;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

import java.util.UUID;

@Service
@Slf4j
public class TransactionsService {

  @Autowired private TransactionActivateHandler transactionActivateHandler;

  @Autowired private TransactionRequestAuthorizationHandler transactionRequestAuthorizationHandler;

  @Autowired private TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandler;

  @Autowired private TransactionUpdateStatusHandler transactionUpdateHandler;

  @Autowired private TransactionSendClosureHandler transactionSendClosureHandler;

  @Autowired
  private TransactionsActivationRequestedProjectionHandler
      transactionsActivationRequestedProjectionHandler;

  @Autowired private AuthorizationRequestProjectionHandler authorizationProjectionHandler;

  @Autowired private AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandler;

  @Autowired private TransactionUpdateProjectionHandler transactionUpdateProjectionHandler;

  @Autowired private ClosureSendProjectionHandler closureSendProjectionHandler;

  @Autowired private TransactionsViewRepository transactionsViewRepository;

  @Autowired private EcommercePaymentInstrumentsClient ecommercePaymentInstrumentsClient;

  @Autowired private TransactionActivateResultHandler transactionActivateResultHandler;

  @Autowired
  private TransactionsActivationProjectionHandler transactionsActivationProjectionHandler;

  public Mono<NewTransactionResponseDto> newTransaction(
      NewTransactionRequestDto newTransactionRequestDto) {

    log.info("Initializing transaction for rptId: {}", newTransactionRequestDto.getRptId());

    TransactionActivateCommand command =
        new TransactionActivateCommand(
            new RptId(newTransactionRequestDto.getRptId()), newTransactionRequestDto);

    return transactionActivateHandler
        .handle(command)
        .doOnNext(
            args ->
                log.info(
                    "Transaction initialized for rptId: {}", newTransactionRequestDto.getRptId()))
        .flatMap(
            es -> {
              final Mono<TransactionActivatedEvent> transactionActivatedEvent = es.getT1();
              final Mono<TransactionActivationRequestedEvent> transactionActivationRequestedEvent =
                  es.getT2();
              final SessionDataDto sessionDataDto = es.getT3();

              return transactionActivatedEvent
                  .flatMap(t -> projectActivatedEvent(t, sessionDataDto))
                  .switchIfEmpty(
                      Mono.defer(
                          () ->
                              transactionActivationRequestedEvent.flatMap(
                                  t -> projectActivationEvent(t, sessionDataDto))));
            });
  }

  public Mono<TransactionInfoDto> getTransactionInfo(String transactionId) {
    return transactionsViewRepository
        .findById(transactionId)
        .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
        .map(
            transaction ->
                new TransactionInfoDto()
                    .transactionId(transaction.getTransactionId())
                    .amount(transaction.getAmount())
                    .reason(transaction.getDescription())
                    .paymentToken(transaction.getPaymentToken())
                    .authToken(null)
                    .rptId(transaction.getRptId())
                    .status(transaction.getStatus()));
  }

  public Mono<RequestAuthorizationResponseDto> requestTransactionAuthorization(
      String transactionId, RequestAuthorizationRequestDto requestAuthorizationRequestDto) {
    return transactionsViewRepository
        .findById(transactionId)
        .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
        .flatMap(
            transaction -> {
              log.info(
                  "Authorization request amount validation for transactionId: {}", transactionId);
              return transaction.getAmount() != requestAuthorizationRequestDto.getAmount()
                  ? Mono.empty()
                  : Mono.just(transaction);
            })
        .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
        .flatMap(
            transaction -> {
              log.info("Authorization psp validation for transactionId: {}", transactionId);
              return ecommercePaymentInstrumentsClient
                  .getPSPs(
                      transaction.getAmount(),
                      requestAuthorizationRequestDto.getLanguage().getValue())
                  .mapNotNull(
                      pspResponse ->
                          pspResponse.getPsp().stream()
                              .filter(
                                  psp ->
                                      psp.getCode()
                                              .equals(requestAuthorizationRequestDto.getPspId())
                                          && psp.getFixedCost()
                                              .equals(
                                                  (double) requestAuthorizationRequestDto.getFee()
                                                      / 100))
                              .findFirst()
                              .orElse(null))
                  .map(psp -> Tuples.of(transaction, psp));
            })
        .switchIfEmpty(
            Mono.error(
                new UnsatisfiablePspRequestException(
                    new PaymentToken(transactionId),
                    requestAuthorizationRequestDto.getLanguage(),
                    requestAuthorizationRequestDto.getFee())))
        .flatMap(
            args -> {
              it.pagopa.transactions.documents.Transaction transactionDocument = args.getT1();
              PspDto psp = args.getT2();

              log.info("Requesting authorization for rptId: {}", transactionDocument.getRptId());

              TransactionActivated transaction =
                  new TransactionActivated(
                      new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
                      new PaymentToken(transactionDocument.getPaymentToken()),
                      new RptId(transactionDocument.getRptId()),
                      new TransactionDescription(transactionDocument.getDescription()),
                      new TransactionAmount(transactionDocument.getAmount()),
                      transactionDocument.getStatus());

              AuthorizationRequestData authorizationData =
                  new AuthorizationRequestData(
                      transaction,
                      requestAuthorizationRequestDto.getFee(),
                      requestAuthorizationRequestDto.getPaymentInstrumentId(),
                      requestAuthorizationRequestDto.getPspId(),
                      psp.getPaymentTypeCode(),
                      psp.getBrokerName(),
                      psp.getChannelCode());

              TransactionRequestAuthorizationCommand command =
                  new TransactionRequestAuthorizationCommand(
                      transaction.getRptId(), authorizationData);

              return transactionRequestAuthorizationHandler
                  .handle(command)
                  .doOnNext(
                      res ->
                          log.info(
                              "Requested authorization for rptId: {}",
                              transactionDocument.getRptId()))
                  .flatMap(
                      res ->
                          authorizationProjectionHandler.handle(authorizationData).thenReturn(res));
            });
  }

  public Mono<TransactionInfoDto> updateTransactionAuthorization(
      String transactionId, UpdateAuthorizationRequestDto updateAuthorizationRequestDto) {
    return transactionsViewRepository
        .findById(transactionId)
        .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
        .flatMap(
            transactionDocument -> {
              TransactionActivated transaction =
                  new TransactionActivated(
                      new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
                      new PaymentToken(transactionDocument.getPaymentToken()),
                      new RptId(transactionDocument.getRptId()),
                      new TransactionDescription(transactionDocument.getDescription()),
                      new TransactionAmount(transactionDocument.getAmount()),
                      transactionDocument.getStatus());

              UpdateAuthorizationStatusData updateAuthorizationStatusData =
                  new UpdateAuthorizationStatusData(transaction, updateAuthorizationRequestDto);

              TransactionUpdateAuthorizationCommand transactionUpdateAuthorizationCommand =
                  new TransactionUpdateAuthorizationCommand(
                      transaction.getRptId(), updateAuthorizationStatusData);

              return transactionUpdateAuthorizationHandler
                  .handle(transactionUpdateAuthorizationCommand)
                  .doOnNext(
                      authorizationStatusUpdatedEvent ->
                          log.info(
                              "Requested authorization update for rptId: {}",
                              authorizationStatusUpdatedEvent.getRptId()))
                  .flatMap(
                      authorizationStatusUpdatedEvent ->
                          authorizationUpdateProjectionHandler.handle(
                              authorizationStatusUpdatedEvent));
            })
        .cast(TransactionActivated.class)
        .flatMap(
            transaction -> {
              ClosureSendData closureSendData =
                  new ClosureSendData(transaction, updateAuthorizationRequestDto);

              TransactionClosureSendCommand transactionClosureSendCommand =
                  new TransactionClosureSendCommand(transaction.getRptId(), closureSendData);

              return transactionSendClosureHandler
                  .handle(transactionClosureSendCommand)
                  .doOnNext(
                      closureSentEvent ->
                          log.info(
                              "Requested transaction closure for rptId: {}",
                              closureSentEvent.getRptId()))
                  .flatMap(
                      closureSentEvent -> closureSendProjectionHandler.handle(closureSentEvent))
                  .map(
                      transactionDocument ->
                          new TransactionInfoDto()
                              .transactionId(transactionDocument.getTransactionId())
                              .amount(transactionDocument.getAmount())
                              .reason(transactionDocument.getDescription())
                              .paymentToken(transactionDocument.getPaymentToken())
                              .rptId(transactionDocument.getRptId())
                              .status(transactionDocument.getStatus())
                              .authToken(null));
            });
  }

  public Mono<TransactionInfoDto> updateTransactionStatus(
      String transactionId, UpdateTransactionStatusRequestDto updateTransactionRequestDto) {
    return transactionsViewRepository
        .findById(transactionId)
        .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
        .map(
            transactionDocument -> {
              TransactionActivated transaction =
                  new TransactionActivated(
                      new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
                      new PaymentToken(transactionDocument.getPaymentToken()),
                      new RptId(transactionDocument.getRptId()),
                      new TransactionDescription(transactionDocument.getDescription()),
                      new TransactionAmount(transactionDocument.getAmount()),
                      transactionDocument.getStatus());
              UpdateTransactionStatusData updateTransactionStatusData =
                  new UpdateTransactionStatusData(transaction, updateTransactionRequestDto);
              return new TransactionUpdateStatusCommand(
                  transaction.getRptId(), updateTransactionStatusData);
            })
        .flatMap(
            transactionUpdateCommand -> transactionUpdateHandler.handle(transactionUpdateCommand))
        .doOnNext(
            transactionStatusUpdatedEvent ->
                log.info(
                    "TRANSACTION_STATUS_UPDATED_EVENT for transactionId: {}",
                    transactionStatusUpdatedEvent.getTransactionId()))
        .flatMap(
            transactionStatusUpdatedEvent ->
                transactionUpdateProjectionHandler.handle(transactionStatusUpdatedEvent))
        .cast(TransactionActivated.class)
        .map(
            transaction ->
                new TransactionInfoDto()
                    .transactionId(transaction.getTransactionId().value().toString())
                    .amount(transaction.getAmount().value())
                    .reason(transaction.getDescription().value())
                    .paymentToken(transaction.getPaymentToken().value())
                    .rptId(transaction.getRptId().value())
                    .status(transaction.getStatus())
                    .authToken(null))
        .doOnNext(
            transaction ->
                log.info(
                    "Transaction status updated NOTIFIED for transactionId: {}",
                    transaction.getStatus(),
                    transaction.getTransactionId()));
  }

  public Mono<ActivationResultResponseDto> activateTransaction(
      String transactionId, ActivationResultRequestDto activationResultRequestDto) {
    return transactionsViewRepository
        .findById(transactionId)
        .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
        .map(
            transactionDocument -> {
              TransactionActivationRequested transaction =
                  new TransactionActivationRequested(
                      new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
                      new PaymentToken(transactionDocument.getPaymentToken()),
                      new RptId(transactionDocument.getRptId()),
                      new TransactionDescription(transactionDocument.getDescription()),
                      new TransactionAmount(transactionDocument.getAmount()),
                      transactionDocument.getStatus());
              ActivationResultData activationResultData =
                  new ActivationResultData(transaction, activationResultRequestDto);
              return new TransactionActivateResultCommand(
                  transaction.getRptId(), activationResultData);
            })
        .flatMap(
            transactionActivateResultCommand ->
                transactionActivateResultHandler.handle(transactionActivateResultCommand))
        .doOnNext(
            transactionActivatedEvent ->
                log.info(
                    "TRANSACTION_ACTIVATED_EVENT for transactionId: {}",
                    transactionActivatedEvent.getTransactionId()))
        .flatMap(
            transactionActivatedEvent ->
                transactionsActivationProjectionHandler.handle(transactionActivatedEvent))
        .map(
            transactionInitializedEvent ->
                new ActivationResultResponseDto()
                    .outcome(ActivationResultResponseDto.OutcomeEnum.OK))
        .doOnNext(
            activationResultResponseDto ->
                log.info(
                    "Transaction status updated INITIALIZED after nodoAttivaRPT for transactionId: {}",
                    transactionId));
  }

  private Mono<NewTransactionResponseDto> projectActivationEvent(
      TransactionActivationRequestedEvent transactionActivateRequestedEvent,
      SessionDataDto sessionDataDto) {
    return
            transactionsActivationRequestedProjectionHandler
                .handle(transactionActivateRequestedEvent)
                .map(
                    transaction ->
                        new NewTransactionResponseDto()
                            .amount(transaction.getAmount().value())
                            .reason(transaction.getDescription().value())
                            .transactionId(transaction.getTransactionId().value().toString())
                            .rptId(transaction.getRptId().value())
                            .status(transaction.getStatus())
                            .authToken(sessionDataDto.getSessionToken()));
  }

  private Mono<NewTransactionResponseDto> projectActivatedEvent(
      TransactionActivatedEvent transactionActivatedEvent, SessionDataDto sessionDataDto) {
    return
            transactionsActivationProjectionHandler
                .handle(transactionActivatedEvent)
                .map(
                    transaction ->
                        new NewTransactionResponseDto()
                            .amount(transaction.getAmount().value())
                            .reason(transaction.getDescription().value())
                            .transactionId(transaction.getTransactionId().value().toString())
                            .rptId(transaction.getRptId().value())
                            .status(transaction.getStatus())
                            .authToken(sessionDataDto.getSessionToken()));
  }
}
