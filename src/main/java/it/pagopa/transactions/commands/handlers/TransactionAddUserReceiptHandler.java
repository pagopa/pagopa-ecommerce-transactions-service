package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.domain.v1.Transaction;
import it.pagopa.ecommerce.commons.domain.v1.TransactionClosed;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.notifications.templates.ko.KoTemplate;
import it.pagopa.generated.notifications.templates.success.*;
import it.pagopa.generated.notifications.v1.dto.NotificationEmailResponseDto;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestDto;
import it.pagopa.transactions.client.NotificationsServiceClient;
import it.pagopa.transactions.commands.TransactionAddUserReceiptCommand;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component
@Slf4j
public class TransactionAddUserReceiptHandler
        extends BaseHandler<TransactionAddUserReceiptCommand, Mono<TransactionUserReceiptAddedEvent>> {

    private final TransactionsEventStoreRepository<TransactionUserReceiptData> userReceiptAddedEventRepository;

    private final TransactionsEventStoreRepository<TransactionRefundedData> refundedDataTransactionsEventStoreRepository;
    private final NotificationsServiceClient notificationsServiceClient;

    private final ConfidentialMailUtils confidentialMailUtils;

    private final QueueAsyncClient transactionRefundQueueClient;

    @Autowired
    public TransactionAddUserReceiptHandler(
            TransactionsEventStoreRepository<Object> eventStoreRepository,
            TransactionsEventStoreRepository<TransactionUserReceiptData> userReceiptAddedEventRepository,
            TransactionsEventStoreRepository<TransactionRefundedData> refundedRequestedEventStoreRepository,
            NotificationsServiceClient notificationsServiceClient,
            ConfidentialMailUtils confidentialMailUtils,
            @Qualifier("transactionRefundQueueAsyncClient") QueueAsyncClient transactionRefundQueueClient
    ) {
        super(eventStoreRepository);
        this.userReceiptAddedEventRepository = userReceiptAddedEventRepository;
        this.refundedDataTransactionsEventStoreRepository = refundedRequestedEventStoreRepository;
        this.notificationsServiceClient = notificationsServiceClient;
        this.confidentialMailUtils = confidentialMailUtils;
        this.transactionRefundQueueClient = transactionRefundQueueClient;
    }

    @Override
    public Mono<TransactionUserReceiptAddedEvent> handle(TransactionAddUserReceiptCommand command) {
        Mono<Transaction> transaction = replayTransactionEvents(
                command.getData().transaction().getTransactionId().value()
        );

        Mono<TransactionClosed> alreadyProcessedError = transaction
                .cast(BaseTransaction.class)
                .doOnNext(
                        t -> log.error(
                                "Error: requesting closure status update for transaction in state {}, Nodo closure outcome {}",
                                t.getStatus(),
                                t instanceof TransactionClosed transactionClosed
                                        ? transactionClosed.getTransactionClosureData().getResponseOutcome()
                                        : "N/A"
                        )
                )
                .flatMap(t -> Mono.error(new AlreadyProcessedException(t.getTransactionId())));

        return transaction
                .cast(BaseTransaction.class)
                .filter(
                        t -> t.getStatus() == TransactionStatusDto.CLOSED &&
                                t instanceof TransactionClosed transactionClosed &&
                                TransactionClosureData.Outcome.OK
                                        .equals(
                                                transactionClosed.getTransactionClosureData().getResponseOutcome()
                                        )
                )
                .switchIfEmpty(alreadyProcessedError)
                .cast(TransactionClosed.class)
                .flatMap(tx -> {
                    AddUserReceiptRequestDto addUserReceiptRequestDto = command.getData().addUserReceiptRequest();
                    String transactionId = command.getData().transaction().getTransactionId().value().toString();
                    TransactionUserReceiptAddedEvent event = new TransactionUserReceiptAddedEvent(
                            transactionId,
                            new TransactionUserReceiptData(
                                    requestOutcomeToReceiptOutcome(
                                            command.getData().addUserReceiptRequest().getOutcome()
                                    )
                            )
                    );

                    String language = "it-IT"; // FIXME: Add language to AuthorizationRequestData
                    Mono<NotificationEmailResponseDto> emailResponse = Mono
                            .just(event.getData().getResponseOutcome())
                            .flatMap(status -> {
                                switch (status) {
                                    case OK -> {
                                        return sendSuccessEmail(tx, addUserReceiptRequestDto, language);
                                    }
                                    case KO -> {
                                        return sendKoEmail(tx, addUserReceiptRequestDto, language);
                                    }
                                    default -> {
                                        return Mono.error(
                                                new IllegalStateException(
                                                        "Invalid new status for user receipt handler: %s"
                                                                .formatted(status)
                                                )
                                        );
                                    }
                                }
                            });

                    return emailResponse
                            .flatMap(v -> userReceiptAddedEventRepository.save(event))
                            .flatMap(e -> {
                                if (command.getData().addUserReceiptRequest()
                                        .getOutcome() == AddUserReceiptRequestDto.OutcomeEnum.KO) {
                                    TransactionRefundRequestedEvent refundRequestedEvent = new TransactionRefundRequestedEvent(
                                            transactionId,
                                            new TransactionRefundedData(TransactionStatusDto.NOTIFIED_KO)
                                    );

                                    return refundedDataTransactionsEventStoreRepository.save(refundRequestedEvent)
                                            .then(
                                                    transactionRefundQueueClient
                                                            .sendMessage(BinaryData.fromObject(refundRequestedEvent))
                                            )
                                            .thenReturn(e);
                                } else {
                                    return Mono.just(e);
                                }
                            });
                });
    }

    private Mono<NotificationEmailResponseDto> sendKoEmail(
                                                           TransactionClosed tx,
                                                           AddUserReceiptRequestDto addUserReceiptRequestDto,
                                                           String language
    ) {
        return notificationsServiceClient.sendKoEmail(
                new NotificationsServiceClient.KoTemplateRequest(
                        confidentialMailUtils.toEmail(tx.getEmail()).value(),
                        "Il pagamento non è riuscito",
                        language,
                        new KoTemplate(
                                new it.pagopa.generated.notifications.templates.ko.TransactionTemplate(
                                        tx.getTransactionId().value().toString().toLowerCase(),
                                        dateTimeToHumanReadableString(
                                                addUserReceiptRequestDto.getPaymentDate(),
                                                Locale.forLanguageTag(language)
                                        ),
                                        amountToHumanReadableString(
                                                tx.getPaymentNotices().stream()
                                                        .mapToInt(
                                                                paymentNotice -> paymentNotice.transactionAmount()
                                                                        .value()
                                                        )
                                                        .sum()
                                        )
                                )
                        )
                )
        );
    }

    private Mono<NotificationEmailResponseDto> sendSuccessEmail(
                                                                TransactionClosed tx,
                                                                AddUserReceiptRequestDto addUserReceiptRequestDto,
                                                                String language
    ) {
        TransactionAuthorizationRequestData transactionAuthorizationRequestData = tx
                .getTransactionAuthorizationRequestData();

        return notificationsServiceClient.sendSuccessEmail(
                new NotificationsServiceClient.SuccessTemplateRequest(
                        confidentialMailUtils.toEmail(tx.getEmail()).value(),
                        "Il riepilogo del tuo pagamento",
                        language,
                        new SuccessTemplate(
                                new TransactionTemplate(
                                        tx.getTransactionId().value().toString().toLowerCase(),
                                        dateTimeToHumanReadableString(
                                                addUserReceiptRequestDto.getPaymentDate(),
                                                Locale.forLanguageTag(language)
                                        ),
                                        amountToHumanReadableString(
                                                tx.getPaymentNotices().stream()
                                                        .mapToInt(
                                                                paymentNotice -> paymentNotice.transactionAmount()
                                                                        .value()
                                                        )
                                                        .sum() + transactionAuthorizationRequestData.getFee()
                                        ),
                                        new PspTemplate(
                                                transactionAuthorizationRequestData.getPspBusinessName(),
                                                new FeeTemplate(
                                                        amountToHumanReadableString(
                                                                transactionAuthorizationRequestData.getFee()
                                                        )
                                                )
                                        ),
                                        transactionAuthorizationRequestData.getAuthorizationRequestId(),
                                        tx.getTransactionAuthorizationCompletedData().getAuthorizationCode(),
                                        new PaymentMethodTemplate(
                                                transactionAuthorizationRequestData.getPaymentMethodName(),
                                                "paymentMethodLogo", // TODO: Logos
                                                null,
                                                false
                                        )
                                ),
                                new UserTemplate(
                                        null,
                                        confidentialMailUtils.toEmail(tx.getEmail()).value()
                                ),
                                new CartTemplate(
                                        tx.getPaymentNotices().stream().map(
                                                paymentNotice -> new ItemTemplate(
                                                        new RefNumberTemplate(
                                                                RefNumberTemplate.Type.CODICE_AVVISO,
                                                                paymentNotice.rptId().getNoticeId()
                                                        ),
                                                        null,
                                                        new PayeeTemplate(
                                                                addUserReceiptRequestDto.getPayments().get(0)
                                                                        .getOfficeName(),
                                                                paymentNotice.rptId().getFiscalCode()
                                                        ),
                                                        addUserReceiptRequestDto.getPayments().get(0).getDescription(),
                                                        amountToHumanReadableString(
                                                                paymentNotice.transactionAmount().value()
                                                        )
                                                )
                                        ).toList(),
                                        amountToHumanReadableString(
                                                tx.getPaymentNotices().stream()
                                                        .mapToInt(
                                                                paymentNotice -> paymentNotice.transactionAmount()
                                                                        .value()
                                                        )
                                                        .sum()
                                        )
                                )
                        )
                )
        );
    }

    private String amountToHumanReadableString(int amount) {
        String repr = String.valueOf(amount);
        int centsSeparationIndex = Math.max(0, repr.length() - 2);

        String cents = repr.substring(centsSeparationIndex);
        String euros = repr.substring(0, centsSeparationIndex);

        if (euros.isEmpty()) {
            euros = "0";
        }

        if (cents.length() == 1) {
            cents = "0" + cents;
        }

        return "%s,%s".formatted(euros, cents);
    }

    private String dateTimeToHumanReadableString(
                                                 OffsetDateTime dateTime,
                                                 Locale locale
    ) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd LLLL yyyy, kk:mm:ss").withLocale(locale);
        return dateTime.format(formatter);
    }

    private static TransactionUserReceiptData.Outcome requestOutcomeToReceiptOutcome(
                                                                                     AddUserReceiptRequestDto.OutcomeEnum requestOutcome
    ) {
        return switch (requestOutcome) {
            case OK -> TransactionUserReceiptData.Outcome.OK;
            case KO -> TransactionUserReceiptData.Outcome.KO;
        };
    }
}
