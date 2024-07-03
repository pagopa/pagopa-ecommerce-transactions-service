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
import it.pagopa.generated.transactions.v2.server.model.*;
import it.pagopa.generated.transactions.v3.server.model.NewTransactionRequestNpgDetailsDto;
import it.pagopa.transactions.exceptions.NotImplementedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    private static final Map<it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto, it.pagopa.generated.transactions.v3.server.model.TransactionStatusDto> transactionStatusLookupMapV3 = new EnumMap<>(
            it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.class
    );

    public static Map<String, ResponseEntity<?>> nodeErrorToV2TransactionsResponseEntityMapping = new HashMap<>();

    public static Map<String, ResponseEntity<?>> nodeErrorToV3TransactionsResponseEntityMapping = new HashMap<>();

    @Autowired
    public TransactionsUtils(
            TransactionsEventStoreRepository<Object> eventStoreRepository,
            @Value("${warmup.request.newTransaction.noticeCodePrefix}") String warmUpNoticeCodePrefix
    ) {
        this.eventStoreRepository = eventStoreRepository;
        this.warmUpNoticeCodePrefix = warmUpNoticeCodePrefix;
    }

    static {
        Set<String> commonsStatuses = Set
                .of(it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.values())
                .stream()
                .map(Enum::toString)
                .collect(Collectors.toSet());
        Set<String> transactionsStatusesV1 = Set
                .of(it.pagopa.generated.transactions.server.model.TransactionStatusDto.values())
                .stream()
                .map(Enum::toString)
                .collect(Collectors.toSet());
        Set<String> transactionsStatusesV2 = Set
                .of(it.pagopa.generated.transactions.v2.server.model.TransactionStatusDto.values())
                .stream()
                .map(Enum::toString)
                .collect(Collectors.toSet());
        Set<String> transactionsStatusesV3 = Set
                .of(it.pagopa.generated.transactions.v3.server.model.TransactionStatusDto.values())
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
        if (!commonsStatuses.equals(transactionsStatusesV1)) {
            Set<String> unknownTransactionsStatuses = transactionsStatusesV1.stream()
                    .filter(Predicate.not(commonsStatuses::contains)).collect(Collectors.toSet());
            Set<String> unknownCommonStatuses = commonsStatuses.stream()
                    .filter(Predicate.not(transactionsStatusesV1::contains)).collect(Collectors.toSet());
            throw new IllegalArgumentException(
                    "Mismatched transaction status enumerations%nUnhandled commons statuses: %s%nUnhandled transaction statuses: %s"
                            .formatted(unknownCommonStatuses, unknownTransactionsStatuses)
            );
        }
        if (!commonsStatuses.equals(transactionsStatusesV2)) {
            Set<String> unknownTransactionsStatuses = transactionsStatusesV2.stream()
                    .filter(Predicate.not(commonsStatuses::contains)).collect(Collectors.toSet());
            Set<String> unknownCommonStatuses = commonsStatuses.stream()
                    .filter(Predicate.not(transactionsStatusesV2::contains)).collect(Collectors.toSet());
            throw new IllegalArgumentException(
                    "Mismatched transaction status enumerations%nUnhandled commons statuses: %s%nUnhandled transaction statuses: %s"
                            .formatted(unknownCommonStatuses, unknownTransactionsStatuses)
            );
        }
        if (!commonsStatuses.equals(transactionsStatusesV3)) {
            Set<String> unknownTransactionsStatuses = transactionsStatusesV3.stream()
                    .filter(Predicate.not(commonsStatuses::contains)).collect(Collectors.toSet());
            Set<String> unknownCommonStatuses = commonsStatuses.stream()
                    .filter(Predicate.not(transactionsStatusesV3::contains)).collect(Collectors.toSet());
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

        for (it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto enumValue : it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto
                .values()) {
            /*
             * @formatter:off
             *
             * This lookup map handles enumeration conversion from commons and transactions-service for the `TransactionStatusDto` enumeration
             *
             * @formatter:on
             */
            transactionStatusLookupMapV3.put(
                    enumValue,
                    it.pagopa.generated.transactions.v3.server.model.TransactionStatusDto
                            .fromValue(enumValue.toString())
            );
        }

        /*
         * @formatter:off
         *
         * Node error code to 404 for v2 transactions response mapping
         *
         * @formatter:on
         */
        for (ValidationFaultPaymentUnknownDto faultCode : ValidationFaultPaymentUnknownDto.values()) {
            nodeErrorToV2TransactionsResponseEntityMapping.put(
                    faultCode.getValue(),
                    new ResponseEntity<>(
                            new ValidationFaultPaymentUnknownProblemJsonDto().title("Payment Status Fault")
                                    .faultCodeCategory(
                                            ValidationFaultPaymentUnknownProblemJsonDto.FaultCodeCategoryEnum.PAYMENT_UNKNOWN
                                    )
                                    .faultCodeDetail(faultCode),
                            HttpStatus.NOT_FOUND
                    )
            );
        }

        for (ValidationFaultPaymentDataErrorDto faultCode : ValidationFaultPaymentDataErrorDto.values()) {
            nodeErrorToV2TransactionsResponseEntityMapping.put(
                    faultCode.getValue(),
                    new ResponseEntity<>(
                            new ValidationFaultPaymentDataErrorProblemJsonDto()
                                    .title("Payment Status Fault")
                                    .faultCodeCategory(
                                            ValidationFaultPaymentDataErrorProblemJsonDto.FaultCodeCategoryEnum.PAYMENT_DATA_ERROR
                                    )
                                    .faultCodeDetail(faultCode),
                            HttpStatus.NOT_FOUND
                    )
            );
        }

        /*
         * @formatter:off
         *
         * Node error code to 409 for v2 transactions response mapping
         *
         * @formatter:on
         */
        for (PaymentOngoingStatusFaultDto faultCode : PaymentOngoingStatusFaultDto.values()) {
            nodeErrorToV2TransactionsResponseEntityMapping.put(
                    faultCode.getValue(),
                    new ResponseEntity<>(
                            new PaymentOngoingStatusFaultPaymentProblemJsonDto()
                                    .title("Payment Status Fault")
                                    .faultCodeCategory(
                                            PaymentOngoingStatusFaultPaymentProblemJsonDto.FaultCodeCategoryEnum.PAYMENT_ONGOING
                                    )
                                    .faultCodeDetail(faultCode),
                            HttpStatus.CONFLICT
                    )
            );
        }

        for (PaymentExpiredStatusFaultDto faultCode : PaymentExpiredStatusFaultDto.values()) {
            nodeErrorToV2TransactionsResponseEntityMapping.put(
                    faultCode.getValue(),
                    new ResponseEntity<>(
                            new PaymentExpiredStatusFaultPaymentProblemJsonDto()
                                    .title("Payment Status Fault")
                                    .faultCodeCategory(
                                            PaymentExpiredStatusFaultPaymentProblemJsonDto.FaultCodeCategoryEnum.PAYMENT_EXPIRED
                                    )
                                    .faultCodeDetail(faultCode),
                            HttpStatus.CONFLICT
                    )
            );
        }

        for (PaymentCanceledStatusFaultDto faultCode : PaymentCanceledStatusFaultDto.values()) {
            nodeErrorToV2TransactionsResponseEntityMapping.put(
                    faultCode.getValue(),
                    new ResponseEntity<>(
                            new PaymentCanceledStatusFaultPaymentProblemJsonDto()
                                    .title("Payment Status Fault")
                                    .faultCodeCategory(
                                            PaymentCanceledStatusFaultPaymentProblemJsonDto.FaultCodeCategoryEnum.PAYMENT_CANCELED
                                    )
                                    .faultCodeDetail(faultCode),
                            HttpStatus.CONFLICT
                    )
            );
        }

        for (PaymentDuplicatedStatusFaultDto faultCode : PaymentDuplicatedStatusFaultDto.values()) {
            nodeErrorToV2TransactionsResponseEntityMapping.put(
                    faultCode.getValue(),
                    new ResponseEntity<>(
                            new PaymentDuplicatedStatusFaultPaymentProblemJsonDto().title("Payment Status Fault")
                                    .faultCodeCategory(
                                            PaymentDuplicatedStatusFaultPaymentProblemJsonDto.FaultCodeCategoryEnum.PAYMENT_DUPLICATED
                                    )
                                    .faultCodeDetail(faultCode),
                            HttpStatus.CONFLICT
                    )
            );
        }

        /*
         * @formatter:off
         *
         * Node error code to 502 for v2 transactions response mapping
         *
         * @formatter:on
         */
        for (ValidationFaultPaymentUnavailableDto faultCode : ValidationFaultPaymentUnavailableDto.values()) {
            nodeErrorToV2TransactionsResponseEntityMapping.put(
                    faultCode.getValue(),
                    new ResponseEntity<>(
                            new ValidationFaultPaymentUnavailableProblemJsonDto()
                                    .title("Payment unavailable")
                                    .faultCodeCategory(
                                            ValidationFaultPaymentUnavailableProblemJsonDto.FaultCodeCategoryEnum.PAYMENT_UNAVAILABLE
                                    )
                                    .faultCodeDetail(faultCode),
                            HttpStatus.BAD_GATEWAY
                    )
            );
        }

        /*
         * @formatter:off
         *
         * Node error code to 503 for v2 transactions response mapping
         *
         * @formatter:on
         */
        for (PartyConfigurationFaultDto faultCode : PartyConfigurationFaultDto.values()) {
            nodeErrorToV2TransactionsResponseEntityMapping.put(
                    faultCode.getValue(),
                    new ResponseEntity<>(
                            new PartyConfigurationFaultPaymentProblemJsonDto()
                                    .title("EC error")
                                    .faultCodeCategory(
                                            PartyConfigurationFaultPaymentProblemJsonDto.FaultCodeCategoryEnum.DOMAIN_UNKNOWN
                                    )
                                    .faultCodeDetail(faultCode),
                            HttpStatus.SERVICE_UNAVAILABLE
                    )
            );
        }

        // v3 uses the same mapping as v2
        nodeErrorToV3TransactionsResponseEntityMapping = new HashMap<>(nodeErrorToV2TransactionsResponseEntityMapping);
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

    public it.pagopa.generated.transactions.v3.server.model.TransactionStatusDto convertEnumerationV3(
                                                                                                      it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto status
    ) {
        return transactionStatusLookupMapV3.get(status);
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

    public it.pagopa.generated.transactions.v2.server.model.NewTransactionRequestDto buildWarmupRequestV2() {
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

    public it.pagopa.generated.transactions.v3.server.model.NewTransactionRequestDto buildWarmupRequestV3() {
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
        return new it.pagopa.generated.transactions.v3.server.model.NewTransactionRequestDto()
                .emailToken("b397aebf-f61c-4845-9483-67f702aebe36")
                .details(new NewTransactionRequestNpgDetailsDto().orderId("orderId"))
                .paymentNotices(
                        Collections.singletonList(
                                new it.pagopa.generated.transactions.v3.server.model.PaymentNoticeInfoDto()
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

    public List<String> getRptIds(BaseTransactionView baseTransactionView) {
        List<PaymentNotice> paymentNotices = getPaymentNotices(baseTransactionView);
        return paymentNotices.stream().map(PaymentNotice::getRptId).toList();
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
