package it.pagopa.transactions.services;

import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PspDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentInstrumentsClient;
import it.pagopa.transactions.commands.TransactionClosureSendCommand;
import it.pagopa.transactions.commands.TransactionInitializeCommand;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.commands.TransactionUpdateStatusCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.commands.data.ClosureSendData;
import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;
import it.pagopa.transactions.commands.data.UpdateTransactionStatusData;
import it.pagopa.transactions.commands.handlers.TransactionSendClosureHandler;
import it.pagopa.transactions.commands.handlers.TransactionInizializeHandler;
import it.pagopa.transactions.commands.handlers.TransactionRequestAuthorizationHandler;
import it.pagopa.transactions.commands.handlers.TransactionUpdateAuthorizationHandler;
import it.pagopa.transactions.commands.handlers.TransactionUpdateStatusHandler;
import it.pagopa.transactions.documents.TransactionInitEvent;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.exceptions.UnsatisfiablePspRequestException;
import it.pagopa.transactions.projections.handlers.AuthorizationRequestProjectionHandler;
import it.pagopa.transactions.projections.handlers.AuthorizationUpdateProjectionHandler;
import it.pagopa.transactions.projections.handlers.ClosureSendProjectionHandler;
import it.pagopa.transactions.projections.handlers.TransactionUpdateProjectionHandler;
import it.pagopa.transactions.projections.handlers.TransactionsProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.UUID;

@Service
@Slf4j
public class TransactionsService {

    @Autowired
    private TransactionInizializeHandler transactionInizializeHandler;

    @Autowired
    private TransactionRequestAuthorizationHandler transactionRequestAuthorizationHandler;

    @Autowired
    private TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandler;

    @Autowired
    private TransactionUpdateStatusHandler transactionUpdateHandler;

    @Autowired
    private TransactionSendClosureHandler transactionSendClosureHandler;

    @Autowired
    private TransactionsProjectionHandler transactionsProjectionHandler;

    @Autowired
    private AuthorizationRequestProjectionHandler authorizationProjectionHandler;

    @Autowired
    private AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandler;

    @Autowired
    private TransactionUpdateProjectionHandler transactionUpdateProjectionHandler;

    @Autowired
    private ClosureSendProjectionHandler closureSendProjectionHandler;

    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Autowired
    private EcommercePaymentInstrumentsClient ecommercePaymentInstrumentsClient;

    public Mono<NewTransactionResponseDto> newTransaction(NewTransactionRequestDto newTransactionRequestDto) {

        log.info("Initializing transaction for rptId: {}", newTransactionRequestDto.getRptId());

        TransactionInitializeCommand command = new TransactionInitializeCommand(
                new RptId(newTransactionRequestDto.getRptId()), newTransactionRequestDto);

        Mono<Tuple2<NewTransactionResponseDto, TransactionInitEvent>> handlerResponse = transactionInizializeHandler.handle(command)
                .doOnNext(tx -> log.info("Transaction initialized for rptId: {}", newTransactionRequestDto.getRptId()));

        return handlerResponse.flatMap(
            data -> {
                NewTransactionResponseDto response = data.getT1();
                TransactionInitEvent initEvent = data.getT2();

                return transactionsProjectionHandler
                        .handle(initEvent)
                        .cast(TransactionInitialized.class)
                        .map(t -> {
                            response.setStatus(t.getStatus());
                            return response;
                        });
            });
    }

    public Mono<TransactionInfoDto> getTransactionInfo(String transactionId) {
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .map(transaction -> new TransactionInfoDto()
                        .transactionId(transaction.getTransactionId())
                        .amount(transaction.getAmount())
                        .reason(transaction.getDescription())
                        .paymentToken(transaction.getPaymentToken())
                        .authToken(null)
                        .rptId(transaction.getRptId())
                        .status(transaction.getStatus()));
    }

    public Mono<RequestAuthorizationResponseDto> requestTransactionAuthorization(String transactionId,
            RequestAuthorizationRequestDto requestAuthorizationRequestDto) {
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .flatMap(transaction -> {
                    log.info("Authorization request amount validation for transactionId: {}", transactionId);
                    return transaction.getAmount() != requestAuthorizationRequestDto.getAmount() ? Mono.empty()
                            : Mono.just(transaction);
                })
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .flatMap(transaction -> {
                    log.info("Authorization psp validation for transactionId: {}", transactionId);
                    return ecommercePaymentInstrumentsClient
                            .getPSPs(transaction.getAmount(),
                                    requestAuthorizationRequestDto.getLanguage().getValue())
                            .mapNotNull(pspResponse -> pspResponse.getPsp()
                                    .stream()
                                    .filter(psp -> psp.getCode()
                                            .equals(requestAuthorizationRequestDto.getPspId())
                                            &&
                                            psp.getFixedCost()
                                                    .equals((double) requestAuthorizationRequestDto.getFee() / 100))
                                    .findFirst().orElse(null))
                            .map(psp -> Tuples.of(transaction, psp));
                })
                .switchIfEmpty(Mono.error(new UnsatisfiablePspRequestException(new PaymentToken(transactionId),
                        requestAuthorizationRequestDto.getLanguage(), requestAuthorizationRequestDto.getFee())))
                .flatMap(args -> {
                    it.pagopa.transactions.documents.Transaction transactionDocument = args.getT1();
                    PspDto psp = args.getT2();

                    log.info("Requesting authorization for rptId: {}", transactionDocument.getRptId());

                    TransactionInitialized transaction = new TransactionInitialized(
                            new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
                            new PaymentToken(transactionDocument.getPaymentToken()),
                            new RptId(transactionDocument.getRptId()),
                            new TransactionDescription(transactionDocument.getDescription()),
                            new TransactionAmount(transactionDocument.getAmount()),
                            new Email(transactionDocument.getEmail()),
                            transactionDocument.getStatus());

                    AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                            transaction,
                            requestAuthorizationRequestDto.getFee(),
                            requestAuthorizationRequestDto.getPaymentInstrumentId(),
                            requestAuthorizationRequestDto.getPspId(),
                            psp.getPaymentTypeCode(),
                            psp.getBrokerName(),
                            psp.getChannelCode());

                    TransactionRequestAuthorizationCommand command = new TransactionRequestAuthorizationCommand(
                            transaction.getRptId(), authorizationData);

                    return transactionRequestAuthorizationHandler.handle(command)
                            .doOnNext(res -> log.info("Requested authorization for rptId: {}",
                                    transactionDocument.getRptId()))
                            .flatMap(res -> authorizationProjectionHandler.handle(authorizationData).thenReturn(res));
                });
    }

    public Mono<TransactionInfoDto> updateTransactionAuthorization(String transactionId,
            UpdateAuthorizationRequestDto updateAuthorizationRequestDto) {
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .flatMap(transactionDocument -> {
                    TransactionInitialized transaction = new TransactionInitialized(
                            new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
                            new PaymentToken(transactionDocument.getPaymentToken()),
                            new RptId(transactionDocument.getRptId()),
                            new TransactionDescription(transactionDocument.getDescription()),
                            new TransactionAmount(transactionDocument.getAmount()),
                            new Email(transactionDocument.getEmail()),
                            transactionDocument.getStatus());

                    UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                            transaction,
                            updateAuthorizationRequestDto);

                    TransactionUpdateAuthorizationCommand transactionUpdateAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                            transaction.getRptId(), updateAuthorizationStatusData);

                    return transactionUpdateAuthorizationHandler
                            .handle(transactionUpdateAuthorizationCommand)
                            .doOnNext(authorizationStatusUpdatedEvent -> log.info(
                                    "Requested authorization update for rptId: {}",
                                    authorizationStatusUpdatedEvent.getRptId()))
                            .flatMap(authorizationStatusUpdatedEvent -> authorizationUpdateProjectionHandler
                                    .handle(authorizationStatusUpdatedEvent));
                })
                .flatMap(transaction -> {
                    ClosureSendData closureSendData = new ClosureSendData(transaction, updateAuthorizationRequestDto);

                    TransactionClosureSendCommand transactionClosureSendCommand = new TransactionClosureSendCommand(
                            transaction.getRptId(), closureSendData);

                    return transactionSendClosureHandler
                            .handle(transactionClosureSendCommand)
                            .doOnNext(closureSentEvent -> log.info("Requested transaction closure for rptId: {}",
                                    closureSentEvent.getRptId()))
                            .flatMap(closureSentEvent -> closureSendProjectionHandler.handle(closureSentEvent))
                            .map(transactionDocument -> new TransactionInfoDto()
                                    .transactionId(transactionDocument.getTransactionId())
                                    .amount(transactionDocument.getAmount())
                                    .reason(transactionDocument.getDescription())
                                    .paymentToken(transactionDocument.getPaymentToken())
                                    .rptId(transactionDocument.getRptId())
                                    .status(transactionDocument.getStatus())
                                    .authToken(null));
                });
    }

    public Mono<TransactionInfoDto> updateTransactionStatus(String transactionId,
            UpdateTransactionStatusRequestDto updateTransactionRequestDto) {
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .map(transactionDocument -> {
                    TransactionInitialized transaction = new TransactionInitialized(
                            new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
                            new PaymentToken(transactionDocument.getPaymentToken()),
                            new RptId(transactionDocument.getRptId()),
                            new TransactionDescription(transactionDocument.getDescription()),
                            new TransactionAmount(transactionDocument.getAmount()),
                            new Email(transactionDocument.getEmail()),
                            transactionDocument.getStatus());
                    UpdateTransactionStatusData updateTransactionStatusData = new UpdateTransactionStatusData(
                            transaction,
                            updateTransactionRequestDto);
                    return new TransactionUpdateStatusCommand(
                            transaction.getRptId(), updateTransactionStatusData);
                }).flatMap(transactionUpdateCommand -> transactionUpdateHandler
                        .handle(transactionUpdateCommand))
                .doOnNext(transactionStatusUpdatedEvent -> log.info(
                        "TRANSACTION_STATUS_UPDATED_EVENT for transactionId: {}",
                        transactionStatusUpdatedEvent.getTransactionId()))
                .flatMap(transactionStatusUpdatedEvent -> transactionUpdateProjectionHandler
                        .handle(transactionStatusUpdatedEvent))
                .cast(TransactionInitialized.class)
                .map(transaction -> new TransactionInfoDto()
                        .transactionId(transaction.getTransactionId().value().toString())
                        .amount(transaction.getAmount().value())
                        .reason(transaction.getDescription().value())
                        .paymentToken(transaction.getPaymentToken().value())
                        .rptId(transaction.getRptId().value())
                        .status(transaction.getStatus())
                        .authToken(null))
                .doOnNext(transaction -> log.info(
                        "Transaction status updated NOTIFIED for transactionId: {}", transaction.getStatus(),
                        transaction.getTransactionId()));
    }
}
