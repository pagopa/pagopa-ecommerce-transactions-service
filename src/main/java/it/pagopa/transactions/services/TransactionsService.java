package it.pagopa.transactions.services;

import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentInstrumentsClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.commands.TransactionInitializeCommand;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;
import it.pagopa.transactions.commands.handlers.TransactionRequestAuthorizationHandler;
import it.pagopa.transactions.commands.handlers.TransactionInizializeHandler;
import it.pagopa.transactions.commands.handlers.TransactionUpdateAuthorizationHandler;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.exceptions.UnsatisfiablePspRequestException;
import it.pagopa.transactions.projections.handlers.AuthorizationProjectionHandler;
import it.pagopa.transactions.projections.handlers.TransactionsProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

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

    public Mono<TransactionInfoDto> getTransactionInfo(String paymentToken) {
        return transactionsViewRepository
                .findByPaymentToken(paymentToken)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(paymentToken)))
                .map(transaction -> new TransactionInfoDto()
                        .amount(transaction.getAmount())
                        .reason(transaction.getDescription())
                        .paymentToken(transaction.getPaymentToken())
                        .authToken(null)
                        .rptId(transaction.getRptId())
                        .status(transaction.getStatus()));
    }

    public Mono<RequestAuthorizationResponseDto> requestTransactionAuthorization(String paymentToken,
                                                                                 RequestAuthorizationRequestDto requestAuthorizationRequestDto) {
        return transactionsViewRepository
                .findByPaymentToken(paymentToken)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(paymentToken)))
                .flatMap(transaction -> {
                    log.info("Authorization request amount validation for paymentToken: {}", paymentToken);
                    return transaction.getAmount() != requestAuthorizationRequestDto.getAmount() ? Mono.empty()
                            : Mono.just(transaction);
                })
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(paymentToken)))
                .flatMap(transaction -> {
                    log.info("Authorization psp validation for paymentToken: {}", paymentToken);
                    return ecommercePaymentInstrumentsClient
                            .getPSPs(transaction.getAmount(),
                                    requestAuthorizationRequestDto.getLanguage().getValue())
                            .map(pspResponse -> pspResponse.getPsp()
                                    .stream()
                                    .anyMatch(psp -> psp.getCode()
                                            .equals(requestAuthorizationRequestDto.getPspId())
                                            &&
                                            psp.getFixedCost()
                                                    .equals((double) requestAuthorizationRequestDto.getFee() / 100)))
                            .flatMap(isValid -> isValid ? Mono.just(transaction) : Mono.empty());
                })
                .switchIfEmpty(Mono.error(new UnsatisfiablePspRequestException(new PaymentToken(paymentToken), requestAuthorizationRequestDto.getLanguage(), requestAuthorizationRequestDto.getFee())))
                .flatMap(transactionDocument -> {

                    log.info("Requesting authorization for rptId: {}", transactionDocument.getRptId());

                    Transaction transaction = new Transaction(
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
                            UUID.randomUUID()
                    );

                    TransactionRequestAuthorizationCommand command = new TransactionRequestAuthorizationCommand(transaction.getRptId(), authorizationData);

                    return transactionRequestAuthorizationHandler.handle(command)
                            .doOnNext(res -> log.info("Requested authorization for rptId: {}", transactionDocument.getRptId()))
                            .flatMap(res -> authorizationProjectionHandler.handle(authorizationData).thenReturn(res));
                });
    }

    public Mono<TransactionInfoDto> updateTransactionAuthorization(String paymentToken, UpdateAuthorizationRequestDto updateAuthorizationRequestDto) {
        // TODO: Add logging

        return transactionsViewRepository
                .findByPaymentToken(paymentToken)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(paymentToken)))
                .flatMap(transactionDocument -> {
                    Transaction transaction = new Transaction(
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

                    return transactionUpdateAuthorizationHandler.handle(transactionUpdateAuthorizationCommand);
                });
    }
}
