package it.pagopa.transactions.services;

import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.commands.TransactionInitializeCommand;
import it.pagopa.transactions.commands.handlers.TransactionInizializeHandler;
import it.pagopa.transactions.domain.RptId;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.TransactionsProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URISyntaxException;

@Service
@Slf4j
public class TransactionsService {

    @Autowired
    private TransactionInizializeHandler transactionInizializeHandler;

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
                .flatMap(t -> {
                    try {
                        return Mono.just(
                                new RequestAuthorizationResponseDto()
                                        .authorizationUrl(new URI("https://example.com").toString())
                        );
                    } catch (URISyntaxException e) {
                        return Mono.error(e);
                    }
                });
    }
}
