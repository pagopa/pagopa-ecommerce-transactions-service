package it.pagopa.transactions.commands.handlers;

import com.azure.core.util.BinaryData;
import com.azure.storage.queue.QueueAsyncClient;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.domain.v1.TransactionClosed;
import it.pagopa.ecommerce.commons.domain.v1.TransactionEventCode;
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
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component
@Slf4j
public class TransactionAddUserReceiptHandler
        implements
        CommandHandler<TransactionAddUserReceiptCommand, Mono<Either<Mono<TransactionUserReceiptAddErrorEvent>, Mono<TransactionUserReceiptAddedEvent>>>> {

    private final TransactionsEventStoreRepository<TransactionUserReceiptData> userReceiptAddedEventRepository;

    private final TransactionsEventStoreRepository<TransactionRefundedData> refundedDataTransactionsEventStoreRepository;
    private final NotificationsServiceClient notificationsServiceClient;

    private final ConfidentialMailUtils confidentialMailUtils;

    private final QueueAsyncClient transactionRefundQueueClient;

    private final QueueAsyncClient transactionNotificationsRetryQueueClient;

    private final TransactionsUtils transactionsUtils;

    private final Integer notificationErrorRetryIntervalSeconds;

    @Autowired
    public TransactionAddUserReceiptHandler(
            TransactionsEventStoreRepository<TransactionUserReceiptData> userReceiptAddedEventRepository,
            TransactionsEventStoreRepository<TransactionRefundedData> refundedRequestedEventStoreRepository,
            NotificationsServiceClient notificationsServiceClient,
            ConfidentialMailUtils confidentialMailUtils,
            @Qualifier("transactionRefundQueueAsyncClient") QueueAsyncClient transactionRefundQueueClient,
            @Qualifier(
                "transactionNotificationsRetryQueueAsyncClient"
            ) QueueAsyncClient transactionNotificationsRetryQueueClient,
            TransactionsUtils transactionsUtils,
            @Value("${transactions.user_receipt_handler.retry_interval}") Integer notificationErrorRetryIntervalSeconds
    ) {
        this.userReceiptAddedEventRepository = userReceiptAddedEventRepository;
        this.refundedDataTransactionsEventStoreRepository = refundedRequestedEventStoreRepository;
        this.notificationsServiceClient = notificationsServiceClient;
        this.confidentialMailUtils = confidentialMailUtils;
        this.transactionRefundQueueClient = transactionRefundQueueClient;
        this.transactionNotificationsRetryQueueClient = transactionNotificationsRetryQueueClient;
        this.transactionsUtils = transactionsUtils;
        this.notificationErrorRetryIntervalSeconds = notificationErrorRetryIntervalSeconds;
    }

    @Override
    public Mono<Either<Mono<TransactionUserReceiptAddErrorEvent>, Mono<TransactionUserReceiptAddedEvent>>> handle(
                                                                                                                  TransactionAddUserReceiptCommand command
    ) {
        Mono<BaseTransaction> transaction = transactionsUtils.reduceEvents(
                command.getData().transaction().getTransactionId()
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
                    TransactionUserReceiptData.Outcome outcome = requestOutcomeToReceiptOutcome(
                            command.getData().addUserReceiptRequest().getOutcome()
                    );
                    String language = "it-IT"; // FIXME: Add language to AuthorizationRequestData
                    Mono<NotificationEmailResponseDto> emailResponse = Mono
                            .just(outcome)
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
                    return handleEmailResponse(emailResponse, command);
                });
    }

    private Mono<Either<Mono<TransactionUserReceiptAddErrorEvent>, Mono<TransactionUserReceiptAddedEvent>>> handleEmailResponse(
                                                                                                                                Mono<NotificationEmailResponseDto> emailResponse,
                                                                                                                                TransactionAddUserReceiptCommand command
    ) {
        String transactionId = command.getData().transaction().getTransactionId().value().toString();
        TransactionUserReceiptData userReceiptData = new TransactionUserReceiptData(
                requestOutcomeToReceiptOutcome(
                        command.getData().addUserReceiptRequest().getOutcome()
                )
        );
        TransactionUserReceiptAddedEvent userReceiptSuccessEvent = new TransactionUserReceiptAddedEvent(
                transactionId,
                userReceiptData
        );

        TransactionUserReceiptAddErrorEvent userReceiptErrorEvent = new TransactionUserReceiptAddErrorEvent(
                transactionId,
                userReceiptData
        );
        return emailResponse
                .<Either<TransactionUserReceiptAddErrorEvent, TransactionUserReceiptAddedEvent>>map(
                        response -> (Either.right(userReceiptSuccessEvent))
                )
                .onErrorResume(e -> Mono.just(Either.left(userReceiptErrorEvent)))
                .map(
                        either -> either.bimap(
                                errorEvent -> {
                                    log.info(
                                            "Error sending email to user, enqueuing {}",
                                            TransactionEventCode.TRANSACTION_ADD_USER_RECEIPT_ERROR_EVENT
                                    );
                                    return userReceiptAddedEventRepository.save(errorEvent)
                                            .flatMap(
                                                    e -> transactionNotificationsRetryQueueClient
                                                            .sendMessageWithResponse(
                                                                    BinaryData.fromObject(e),
                                                                    Duration.ofSeconds(
                                                                            notificationErrorRetryIntervalSeconds
                                                                    ),
                                                                    null
                                                            )
                                                            .thenReturn(e)
                                            );
                                },
                                successEvent -> userReceiptAddedEventRepository.save(successEvent)
                                        .flatMap(e -> {
                                            if (command.getData().addUserReceiptRequest()
                                                    .getOutcome() == AddUserReceiptRequestDto.OutcomeEnum.KO) {
                                                log.info(
                                                        "Received sendPaymentResult with KO outcome, enqueuing {}",
                                                        TransactionEventCode.TRANSACTION_REFUNDED_EVENT
                                                );
                                                TransactionRefundRequestedEvent refundRequestedEvent = new TransactionRefundRequestedEvent(
                                                        transactionId,
                                                        new TransactionRefundedData(TransactionStatusDto.NOTIFIED_KO)
                                                );

                                                return refundedDataTransactionsEventStoreRepository
                                                        .save(refundRequestedEvent)
                                                        .then(
                                                                transactionRefundQueueClient
                                                                        .sendMessage(
                                                                                BinaryData.fromObject(
                                                                                        refundRequestedEvent
                                                                                )
                                                                        )
                                                        )
                                                        .thenReturn(e);
                                            } else {
                                                return Mono.just(e);
                                            }
                                        }
                                        )
                        )
                );

    }

    private Mono<NotificationEmailResponseDto> sendKoEmail(
                                                           TransactionClosed tx,
                                                           AddUserReceiptRequestDto addUserReceiptRequestDto,
                                                           String language
    ) {
        return confidentialMailUtils.toEmail(tx.getEmail()).flatMap(
                emailAddress -> notificationsServiceClient.sendKoEmail(
                        new NotificationsServiceClient.KoTemplateRequest(
                                emailAddress.value(),
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
                                                                        paymentNotice -> paymentNotice
                                                                                .transactionAmount()
                                                                                .value()
                                                                )
                                                                .sum()
                                                )
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

        return confidentialMailUtils.toEmail(tx.getEmail()).flatMap(
                emailAddress -> notificationsServiceClient.sendSuccessEmail(
                        new NotificationsServiceClient.SuccessTemplateRequest(
                                emailAddress.value(),
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
                                                                        paymentNotice -> paymentNotice
                                                                                .transactionAmount()
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
                                                emailAddress.value()
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
                                                                addUserReceiptRequestDto.getPayments().get(0)
                                                                        .getDescription(),
                                                                amountToHumanReadableString(
                                                                        paymentNotice.transactionAmount().value()
                                                                )
                                                        )
                                                ).toList(),
                                                amountToHumanReadableString(
                                                        tx.getPaymentNotices().stream()
                                                                .mapToInt(
                                                                        paymentNotice -> paymentNotice
                                                                                .transactionAmount()
                                                                                .value()
                                                                )
                                                                .sum()
                                                )
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
