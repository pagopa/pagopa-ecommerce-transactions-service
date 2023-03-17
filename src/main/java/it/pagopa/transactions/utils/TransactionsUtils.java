package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction;
import it.pagopa.ecommerce.commons.domain.v1.Transaction;
import it.pagopa.ecommerce.commons.domain.v1.TransactionId;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.EnumMap;
import java.util.Map;

@Component
public class TransactionsUtils {

    private final TransactionsEventStoreRepository<Object> eventStoreRepository;

    private final Map<it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto, it.pagopa.generated.transactions.server.model.TransactionStatusDto> transactionStatusLookupMap = new EnumMap<>(
            it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.class
    );

    @Autowired
    public TransactionsUtils(TransactionsEventStoreRepository<Object> eventStoreRepository) {
        this.eventStoreRepository = eventStoreRepository;
        init();
    }

    private void init() {
        for (it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto enumValue : it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.class
                .getEnumConstants()) {
            /*
             * This lookup map serve two purpose: 1 handle enumerations conversion from
             * commons and transactions-service TransactionStatusDto enumerations 2 in case
             * one enumeration from commons is not present into transactions-service's ones
             * then an IllegalArgumentException is raised, preventing the module from being
             * run and avoiding,so, runtime errors correlated to specs updated into commons
             * that are not reflected into transactions specs (such as a transaction status
             * added only into commons)
             */
            transactionStatusLookupMap.put(
                    enumValue,
                    it.pagopa.generated.transactions.server.model.TransactionStatusDto.fromValue(enumValue.toString())
            );
        }
    }

    public Mono<BaseTransaction> reduceEvents(TransactionId transactionId) {
        return eventStoreRepository.findByTransactionId(transactionId.value().toString())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId.value().toString())))
                .reduce(new EmptyTransaction(), Transaction::applyEvent)
                .cast(BaseTransaction.class);
    }

    public it.pagopa.generated.transactions.server.model.TransactionStatusDto convertEnumeration(
                                                                                                 it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto status
    ) {
        return this.transactionStatusLookupMap.get(status);
    }

}
