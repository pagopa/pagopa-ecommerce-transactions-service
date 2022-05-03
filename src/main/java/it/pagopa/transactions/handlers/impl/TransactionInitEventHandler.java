package it.pagopa.transactions.handlers.impl;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import it.pagopa.transactions.documents.Transaction;
import it.pagopa.transactions.documents.TransactionEvent;
import it.pagopa.transactions.documents.TransactionInitData;
import it.pagopa.transactions.handlers.EventHandler;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.TransactionStatus;
import reactor.core.publisher.Mono;

@Component
public class TransactionInitHandler implements EventHandler<TransactionInitData, String> {

    @Autowired
    private TransactionsEventStoreRepository<TransactionInitData> transactionEventStoreRepository;

    @Autowired
    private TransactionsViewRepository viewEventStoreRepository;

    /**
     * store transactionInitializedEvent event and update view
     * 
     * @return transaction id in view
     */
    @Override
    public String handle(TransactionEvent<TransactionInitData> transactionInitializedEvent) {

        return Mono.zip(transactionEventStoreRepository.save(transactionInitializedEvent),
                viewEventStoreRepository.save(new Transaction(transactionInitializedEvent.getPaymentToken(),
                        transactionInitializedEvent.getRptId(), transactionInitializedEvent.getData().getDescription(),
                        transactionInitializedEvent.getData().getAmount(), TransactionStatus.TRANSACTION_INITIALIZED)))
                .map(tuple -> {

                    return tuple.getT2().getId();
                }).block();
    }
}