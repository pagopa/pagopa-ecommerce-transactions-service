package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.BaseTransactionView;
import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.Email;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.PaymentNoticeInfoDto;
import it.pagopa.transactions.exceptions.NotImplementedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Component
public class TransactionsUtils {

    private final TransactionsEventStoreRepository<Object> eventStoreRepository;

    private final String warmUpNoticeCodePrefix;

    private static final Map<it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto, it.pagopa.generated.transactions.server.model.TransactionStatusDto> transactionStatusLookupMapV1 = new EnumMap<>(
            it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.class
    );

    private static final Map<it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto, it.pagopa.generated.transactions.v2.server.model.TransactionStatusDto> transactionStatusLookupMapV2 = new EnumMap<>(
            it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.class
    );

    @Autowired
    public TransactionsUtils(
            TransactionsEventStoreRepository<Object> eventStoreRepository,
            @Value("${warmup.request.newTransaction.noticeCodePrefix}") String warmUpNoticeCodePrefix
    ) {
        this.eventStoreRepository = eventStoreRepository;
        this.warmUpNoticeCodePrefix = warmUpNoticeCodePrefix;
    }

    static {
        Set<String> commonsStatusesV1 = Set
                .of(it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.values())
                .stream()
                .map(Enum::toString)
                .collect(Collectors.toSet());
        Set<String> transactionsStatusesV1 = Set
                .of(it.pagopa.generated.transactions.server.model.TransactionStatusDto.values())
                .stream()
                .map(Enum::toString)
                .collect(Collectors.toSet());

        Set<String> commonsStatusesV2 = Set
                .of(it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.values())
                .stream()
                .map(Enum::toString)
                .collect(Collectors.toSet());
        Set<String> transactionsStatusesV2 = Set
                .of(it.pagopa.generated.transactions.v2.server.model.TransactionStatusDto.values())
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
        if (!commonsStatusesV1.equals(transactionsStatusesV1)) {
            Set<String> unknownTransactionsStatuses = transactionsStatusesV1.stream()
                    .filter(Predicate.not(commonsStatusesV1::contains)).collect(Collectors.toSet());
            Set<String> unknownCommonStatuses = commonsStatusesV1.stream()
                    .filter(Predicate.not(transactionsStatusesV1::contains)).collect(Collectors.toSet());
            throw new IllegalArgumentException(
                    "Mismatched transaction status enumerations%nUnhandled commons statuses: %s%nUnhandled transaction statuses: %s"
                            .formatted(unknownCommonStatuses, unknownTransactionsStatuses)
            );
        }
        if (!commonsStatusesV2.equals(transactionsStatusesV2)) {
            Set<String> unknownTransactionsStatuses = transactionsStatusesV2.stream()
                    .filter(Predicate.not(commonsStatusesV2::contains)).collect(Collectors.toSet());
            Set<String> unknownCommonStatuses = commonsStatusesV2.stream()
                    .filter(Predicate.not(transactionsStatusesV2::contains)).collect(Collectors.toSet());
            throw new IllegalArgumentException(
                    "Mismatched transaction status enumerations%nUnhandled commons statuses: %s%nUnhandled transaction statuses: %s"
                            .formatted(unknownCommonStatuses, unknownTransactionsStatuses)
            );
        }
        for (it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto enumValue : it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
                .values()) {
            /*
             * @formatter:off
             *
             * This lookup map handles enumeration conversion from commons and transactions-service for the `TransactionStatusDto` enumeration
             *
             * @formatter:on
             */
            transactionStatusLookupMapV1.put(
                    enumValue,
                    it.pagopa.generated.transactions.server.model.TransactionStatusDto.fromValue(enumValue.toString())
            );
        }

        for (it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto enumValue : it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
                .values()) {
            /*
             * @formatter:off
             *
             * This lookup map handles enumeration conversion from commons and transactions-service for the `TransactionStatusDto` enumeration
             *
             * @formatter:on
             */
            transactionStatusLookupMapV2.put(
                    enumValue,
                    it.pagopa.generated.transactions.v2.server.model.TransactionStatusDto
                            .fromValue(enumValue.toString())
            );
        }
    }

    public Mono<BaseTransaction> reduceEventsV1(TransactionId transactionId) {
        return reduceEvent(
                transactionId,
                new it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction(),
                it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent,
                it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction.class
        );
    }

    public Mono<it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction> reduceEventsV2(
                                                                                            TransactionId transactionId
    ) {
        return reduceEvent(
                transactionId,
                new it.pagopa.ecommerce.commons.domain.v2.EmptyTransaction(),
                it.pagopa.ecommerce.commons.domain.v2.Transaction::applyEvent,
                it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction.class
        );
    }

    public <A, T> Mono<T> reduceEvent(
                                      TransactionId transactionId,
                                      A initialValue,
                                      BiFunction<A, ? super BaseTransactionEvent<?>, A> accumulator,
                                      Class<T> clazz
    ) {
        return eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId.value())))
                .reduce(initialValue, accumulator)
                .cast(clazz);
    }

    public <A, T> Mono<T> reduceEvents(
                                       Flux<BaseTransactionEvent<Object>> events,
                                       A initialValue,
                                       BiFunction<A, ? super BaseTransactionEvent<?>, A> accumulator,
                                       Class<T> clazz
    ) {
        return events
                .reduce(initialValue, accumulator)
                .cast(clazz);
    }

    public it.pagopa.generated.transactions.server.model.TransactionStatusDto convertEnumerationV1(
                                                                                                   it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto status
    ) {
        return transactionStatusLookupMapV1.get(status);
    }

    public it.pagopa.generated.transactions.v2.server.model.TransactionStatusDto convertEnumerationV2(
                                                                                                      it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto status
    ) {
        return transactionStatusLookupMapV2.get(status);
    }

    public NewTransactionRequestDto buildWarmupRequestV1() {
        String noticeCode = warmUpNoticeCodePrefix.concat(String.valueOf(System.currentTimeMillis()));
        int neededPadLength = 18 - noticeCode.length();
        if (neededPadLength < 0) {
            noticeCode = noticeCode.substring(0, noticeCode.length() + neededPadLength);
        } else {
            StringBuilder padBuilder = new StringBuilder();
            noticeCode = padBuilder
                    .append(noticeCode)
                    .append("0".repeat(neededPadLength))
                    .toString();
        }
        return new NewTransactionRequestDto()
                .email("test@test.it")
                .paymentNotices(
                        Collections.singletonList(
                                new PaymentNoticeInfoDto()
                                        .rptId("77777777777%s".formatted(noticeCode))
                                        .amount(100)
                        )
                );
    }

    public  it.pagopa.generated.transactions.v2.server.model.NewTransactionRequestDto buildWarmupRequestV2() {
        String noticeCode = warmUpNoticeCodePrefix.concat(String.valueOf(System.currentTimeMillis()));
        int neededPadLength = 18 - noticeCode.length();
        if (neededPadLength < 0) {
            noticeCode = noticeCode.substring(0, noticeCode.length() + neededPadLength);
        } else {
            StringBuilder padBuilder = new StringBuilder();
            noticeCode = padBuilder
                    .append(noticeCode)
                    .append("0".repeat(neededPadLength))
                    .toString();
        }
        return new it.pagopa.generated.transactions.v2.server.model.NewTransactionRequestDto()
                .email("test@test.it")
                .orderId("orderId")
                .paymentNotices(
                        Collections.singletonList(
                                new it.pagopa.generated.transactions.v2.server.model.PaymentNoticeInfoDto()
                                        .rptId("77777777777%s".formatted(noticeCode))
                                        .amount(100)
                        )
                );
    }

    public Integer getTransactionTotalAmount(BaseTransactionView baseTransactionView) {
        List<PaymentNotice> paymentNotices = getPaymentNotices(baseTransactionView);
        return paymentNotices.stream()
                .mapToInt(
                        it.pagopa.ecommerce.commons.documents.PaymentNotice::getAmount
                ).sum();
    }

    public Boolean isAllCcp(
                            BaseTransactionView baseTransactionView,
                            int idx
    ) {
        List<PaymentNotice> paymentNotices = getPaymentNotices(baseTransactionView);
        return paymentNotices.get(idx).isAllCCP();
    }

    public String getRptId(
                           BaseTransactionView baseTransactionView,
                           int idx
    ) {
        List<PaymentNotice> paymentNotices = getPaymentNotices(baseTransactionView);
        return paymentNotices.get(idx).getRptId();
    }

    public List<it.pagopa.ecommerce.commons.documents.PaymentNotice> getPaymentNotices(BaseTransactionView baseTransactionView) {
        return switch (baseTransactionView) {
            case it.pagopa.ecommerce.commons.documents.v1.Transaction t -> t.getPaymentNotices();
            case it.pagopa.ecommerce.commons.documents.v2.Transaction t -> t.getPaymentNotices();
            default ->
                    throw new NotImplementedException("Handling for transaction document: [%s] not implemented yet".formatted(baseTransactionView.getClass()));
        };
    }

    public String getClientId(BaseTransactionView baseTransactionView) {
        return switch (baseTransactionView) {
            case it.pagopa.ecommerce.commons.documents.v1.Transaction t -> t.getClientId().toString();
            case it.pagopa.ecommerce.commons.documents.v2.Transaction t -> t.getClientId().toString();
            default ->
                    throw new NotImplementedException("Handling for transaction document: [%s] not implemented yet".formatted(baseTransactionView.getClass()));
        };
    }

    public Confidential<Email> getEmail(BaseTransactionView baseTransactionView) {
        return switch (baseTransactionView) {
            case it.pagopa.ecommerce.commons.documents.v1.Transaction t -> t.getEmail();
            case it.pagopa.ecommerce.commons.documents.v2.Transaction t -> t.getEmail();
            default ->
                    throw new NotImplementedException("Handling for transaction document: [%s] not implemented yet".formatted(baseTransactionView.getClass()));
        };
    }

}
