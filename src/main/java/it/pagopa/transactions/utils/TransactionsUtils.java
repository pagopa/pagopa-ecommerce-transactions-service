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
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Component
public class TransactionsUtils {

    private final TransactionsEventStoreRepository<Object> eventStoreRepository;

    private static final Map<it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto, it.pagopa.generated.transactions.server.model.TransactionStatusDto> transactionStatusLookupMap = new EnumMap<>(
            it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.class
    );

    @Autowired
    public TransactionsUtils(TransactionsEventStoreRepository<Object> eventStoreRepository) {
        this.eventStoreRepository = eventStoreRepository;
    }

    static {
        Set<String> commonsStatuses = Set
                .of(it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.values())
                .stream()
                .map(Enum::toString)
                .collect(Collectors.toSet());
        Set<String> transactionsStatuses = Set
                .of(it.pagopa.generated.transactions.server.model.TransactionStatusDto.values())
                .stream()
                .map(Enum::toString)
                .collect(Collectors.toSet());
        /*
         * @formatter:off
         *
         * In case of statuses enumeration mismatch an `IllegalArgumentException` is thrown, preventing the module from being
         * run and avoiding runtime errors correlated to specs updated into commons
         * that are not reflected into this service specs (such as a transaction status added only into commons).
         * The above exception message reports the detected statuses enumeration mismatch between commons and transactions values.
         *
         * @formatter:on
         */
        if (!commonsStatuses.equals(transactionsStatuses)) {
            Set<String> unknownTransactionsStatuses = transactionsStatuses.stream()
                    .filter(Predicate.not(commonsStatuses::contains)).collect(Collectors.toSet());
            Set<String> unknownCommonStatuses = commonsStatuses.stream()
                    .filter(Predicate.not(transactionsStatuses::contains)).collect(Collectors.toSet());
            throw new IllegalArgumentException(
                    "Mismatched transaction status enumerations%nUnhandled commons statuses: %s%nUnhandled transaction statuses: %s"
                            .formatted(unknownCommonStatuses, unknownTransactionsStatuses)
            );
        }
        for (it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto enumValue : it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
                .values()) {
            /*
             * @formatter:off
             * This lookup map handle enumeration conversion from commons and transactions-service for the `TransactionStatusDto` enumeration
             * @formatter:on
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
        return transactionStatusLookupMap.get(status);
    }
}
