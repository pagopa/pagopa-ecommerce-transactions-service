package it.pagopa.transactions.services;

import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.commands.TransactionAuthorizeCommand;
import it.pagopa.transactions.commands.TransactionInitializeCommand;
import it.pagopa.transactions.commands.data.AuthorizationData;
import it.pagopa.transactions.commands.handlers.TransactionAuthorizeHandler;
import it.pagopa.transactions.commands.handlers.TransactionInizializeHandler;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.TransactionsProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class TransactionsService {

    @Autowired
    private TransactionInizializeHandler transactionInizializeHandler;

    @Autowired
    private TransactionAuthorizeHandler transactionAuthorizeHandler;

    @Autowired
    private TransactionsProjectionHandler transactionsProjectionHandler;

    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    public Mono<NewTransactionResponseDto> newTransaction(NewTransactionRequestDto newTransactionRequestDto) {

        log.info("Initializing transaction for rptId: {}", newTransactionRequestDto.getRptId());

        TransactionInitializeCommand command = new TransactionInitializeCommand(new RptId(newTransactionRequestDto.getRptId()), newTransactionRequestDto);

        Mono<NewTransactionResponseDto> response = transactionInizializeHandler.handle(command)
                .doOnNext(tx -> log.info("Transaction initialized for rptId: {}", newTransactionRequestDto.getRptId()));

        return response.flatMap(data -> transactionsProjectionHandler.handle(data).thenReturn(data));
    }

    public Mono<TransactionInfoDto> getTransactionInfo(String paymentToken) {
        return transactionsViewRepository
                .findById(paymentToken)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(paymentToken)))
                .map(transaction -> new TransactionInfoDto()
                        .amount(transaction.getAmount())
                        .reason(transaction.getDescription())
                        .paymentToken(transaction.getPaymentToken())
                        .authToken(null)
                        .rptId(transaction.getRptId())
                        .status(transaction.getStatus()));
    }

    public Mono<RequestAuthorizationResponseDto> requestTransactionAuthorization(String paymentToken, RequestAuthorizationRequestDto requestAuthorizationRequestDto) {
        return transactionsViewRepository
                .findByPaymentToken(paymentToken)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(paymentToken)))
                .flatMap(transactionDocument -> {
                    log.info("Requesting authorization for rptId: {}", transactionDocument.getRptId());

                    Transaction transaction = new Transaction(
                            new TransactionId(transactionDocument.getTransactionId()),
                            new PaymentToken(transactionDocument.getPaymentToken()),
                            new RptId(transactionDocument.getRptId()),
                            new TransactionDescription(transactionDocument.getDescription()),
                            new TransactionAmount(transactionDocument.getAmount()),
                            transactionDocument.getStatus()
                    );

                    AuthorizationData authorizationData = new AuthorizationData(
                            transaction,
                            requestAuthorizationRequestDto.getFee(),
                            requestAuthorizationRequestDto.getPaymentInstrumentId(),
                            requestAuthorizationRequestDto.getPspId()
                    );

                    TransactionAuthorizeCommand command = new TransactionAuthorizeCommand(transaction.getRptId(), authorizationData);

                    // TODO: Update event store & view
                    return transactionAuthorizeHandler.handle(command)
                            .doOnNext(res -> log.info("Requested authorization for rptId: {}", transactionDocument.getRptId()));
                });
    }
}
