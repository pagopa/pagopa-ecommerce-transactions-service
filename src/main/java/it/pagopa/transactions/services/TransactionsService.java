package it.pagopa.transactions.services;

import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PspDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentInstrumentsClient;
import it.pagopa.transactions.commands.TransactionClosureRequestCommand;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.TransactionInitializeCommand;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.commands.data.ClosureRequestData;
import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;
import it.pagopa.transactions.commands.handlers.TransactionClosureRequestHandler;
import it.pagopa.transactions.commands.handlers.TransactionRequestAuthorizationHandler;
import it.pagopa.transactions.commands.handlers.TransactionInizializeHandler;
import it.pagopa.transactions.commands.handlers.TransactionUpdateAuthorizationHandler;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.exceptions.UnsatisfiablePspRequestException;
import it.pagopa.transactions.projections.handlers.AuthorizationProjectionHandler;
import it.pagopa.transactions.projections.handlers.TransactionsProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

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
    private TransactionClosureRequestHandler transactionClosureRequestHandler;

    @Autowired
    private TransactionsProjectionHandler transactionsProjectionHandler;

    @Autowired
    private AuthorizationProjectionHandler authorizationProjectionHandler;

    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Autowired
    private EcommercePaymentInstrumentsClient ecommercePaymentInstrumentsClient;

    public Mono<NewTransactionResponseDto> newTransaction(NewTransactionRequestDto newTransactionRequestDto) {

        log.info("Initializing transaction for rptId: {}", newTransactionRequestDto.getRptId());

        TransactionInitializeCommand command = new TransactionInitializeCommand(
                new RptId(newTransactionRequestDto.getRptId()), newTransactionRequestDto);

        Mono<NewTransactionResponseDto> response = transactionInizializeHandler.handle(command)
                .doOnNext(tx -> log.info("Transaction initialized for rptId: {}", newTransactionRequestDto.getRptId()));

        return response.flatMap(data -> transactionsProjectionHandler.handle(data).thenReturn(data));
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

    public Mono<RequestAuthorizationResponseDto> requestTransactionAuthorization(String transaciontId,
                                                                                 RequestAuthorizationRequestDto requestAuthorizationRequestDto) {
        return transactionsViewRepository
                .findById(transaciontId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transaciontId)))
                .flatMap(transaction -> {
                    log.info("Authorization request amount validation for transaciontId: {}", transaciontId);
                    return transaction.getAmount() != requestAuthorizationRequestDto.getAmount() ? Mono.empty()
                            : Mono.just(transaction);
                })
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transaciontId)))
                .flatMap(transaction -> {
                    log.info("Authorization psp validation for transaciontId: {}", transaciontId);
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
                .switchIfEmpty(Mono.error(new UnsatisfiablePspRequestException(new PaymentToken(transaciontId), requestAuthorizationRequestDto.getLanguage(), requestAuthorizationRequestDto.getFee())))
                .flatMap(args -> {
                    it.pagopa.transactions.documents.Transaction transactionDocument = args.getT1();
                    PspDto psp = args.getT2();

                    log.info("Requesting authorization for rptId: {}", transactionDocument.getRptId());

                    Transaction transaction = new Transaction(
                            new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
                            new PaymentToken(transactionDocument.getPaymentToken()),
                            new RptId(transactionDocument.getRptId()),
                            new TransactionDescription(transactionDocument.getDescription()),
                            new TransactionAmount(transactionDocument.getAmount()),
                            transactionDocument.getStatus()
                    );

                    AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                            transaction,
                            requestAuthorizationRequestDto.getFee(),
                            requestAuthorizationRequestDto.getPaymentInstrumentId(),
                            requestAuthorizationRequestDto.getPspId(),
                            psp.getPaymentTypeCode(),
                            psp.getBrokerName(),
                            psp.getChannelCode(),
                            UUID.randomUUID()
                    );

                    TransactionRequestAuthorizationCommand command = new TransactionRequestAuthorizationCommand(transaction.getRptId(), authorizationData);

                    return transactionRequestAuthorizationHandler.handle(command)
                            .doOnNext(res -> log.info("Requested authorization for rptId: {}", transactionDocument.getRptId()))
                            .flatMap(res -> authorizationProjectionHandler.handle(authorizationData).thenReturn(res));
                });
    }

    public Mono<TransactionInfoDto> updateTransactionAuthorization(String transactionId, UpdateAuthorizationRequestDto updateAuthorizationRequestDto) {
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .flatMap(transactionDocument -> {
                    Transaction transaction = new Transaction(
                            new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
                            new PaymentToken(transactionDocument.getPaymentToken()),
                            new RptId(transactionDocument.getRptId()),
                            new TransactionDescription(transactionDocument.getDescription()),
                            new TransactionAmount(transactionDocument.getAmount()),
                            transactionDocument.getStatus()
                    );

                    UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                            transaction,
                            updateAuthorizationRequestDto
                    );

                    TransactionUpdateAuthorizationCommand transactionUpdateAuthorizationCommand = new TransactionUpdateAuthorizationCommand(transaction.getRptId(), updateAuthorizationStatusData);

                    return transactionUpdateAuthorizationHandler
                            .handle(transactionUpdateAuthorizationCommand)
                            .doOnNext(transactionInfo -> log.info("Requested authorization update for rptId: {}", transactionInfo.getRptId()))
                            .thenReturn(transaction);
                }).flatMap(transaction -> {
                    transaction.setStatus(TransactionStatusDto.AUTHORIZED);
                    ClosureRequestData closureRequestData = new ClosureRequestData(transaction, updateAuthorizationRequestDto);

                    TransactionClosureRequestCommand transactionClosureRequestCommand = new TransactionClosureRequestCommand(transaction.getRptId(), closureRequestData);

                    return transactionClosureRequestHandler
                            .handle(transactionClosureRequestCommand)
                            .doOnNext(transactionInfo -> log.info("Requested transaction closure for rptId: {}", transactionInfo.getRptId()));
                });
    }
}
