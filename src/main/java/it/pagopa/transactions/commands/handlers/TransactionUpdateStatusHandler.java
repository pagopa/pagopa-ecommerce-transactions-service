package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.notifications.templates.ko.KoTemplate;
import it.pagopa.generated.notifications.templates.success.*;
import it.pagopa.generated.notifications.v1.dto.NotificationEmailResponseDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.UpdateTransactionStatusRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.NotificationsServiceClient;
import it.pagopa.transactions.commands.TransactionUpdateStatusCommand;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestData;
import it.pagopa.transactions.documents.TransactionEvent;
import it.pagopa.transactions.documents.TransactionStatusUpdateData;
import it.pagopa.transactions.documents.TransactionStatusUpdatedEvent;
import it.pagopa.transactions.domain.EmptyTransaction;
import it.pagopa.transactions.domain.Transaction;
import it.pagopa.transactions.domain.TransactionClosed;
import it.pagopa.transactions.domain.pojos.BaseTransaction;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
@Slf4j
public class TransactionUpdateStatusHandler implements CommandHandler<TransactionUpdateStatusCommand, Mono<TransactionStatusUpdatedEvent>> {

    @Autowired
    NodeForPspClient nodeForPspClient;

    @Autowired
    private TransactionsEventStoreRepository<TransactionStatusUpdateData> transactionEventStoreRepository;
    @Autowired
    private TransactionsEventStoreRepository<Object> eventStoreRepository;

    @Autowired
    NotificationsServiceClient notificationsServiceClient;

    @Override
    public Mono<TransactionStatusUpdatedEvent> handle(TransactionUpdateStatusCommand command) {
        Mono<Transaction> transaction = replayTransactionEvents(command.getData().transaction().getTransactionId().value());

        Mono<? extends BaseTransaction> alreadyProcessedError = transaction
                .cast(BaseTransaction.class)
                .doOnNext(t -> log.error("Error: requesting closure status update for transaction in state {}", t.getStatus()))
                .flatMap(t -> Mono.error(new AlreadyProcessedException(t.getRptId())));

        return transaction
                .cast(BaseTransaction.class)
                .filter(t -> t.getStatus() == TransactionStatusDto.CLOSED)
                .switchIfEmpty(alreadyProcessedError)
                .cast(TransactionClosed.class)
                .flatMap(tx -> {
                    UpdateTransactionStatusRequestDto updateTransactionStatusRequestDto = command.getData().updateTransactionRequest();
                    TransactionStatusDto newStatus;

                    switch (command.getData().updateTransactionRequest().getAuthorizationResult()) {
                        case OK -> newStatus = TransactionStatusDto.NOTIFIED;
                        case KO -> newStatus = TransactionStatusDto.NOTIFIED_FAILED;
                        default -> {
                            return Mono.error(new RuntimeException("Invalid Nodo sendPaymentResultV2 outcome value"));
                        }
                    }

                    TransactionStatusUpdateData statusUpdateData = new TransactionStatusUpdateData(
                            command.getData()
                                    .updateTransactionRequest().getAuthorizationResult(),
                            newStatus);

                    TransactionStatusUpdatedEvent event = new TransactionStatusUpdatedEvent(
                            command.getData().transaction().getTransactionId().value().toString(),
                            command.getData().transaction().getRptId().value(),
                            command.getData().transaction().getTransactionActivatedData().getPaymentToken(),
                            statusUpdateData);

                    String language = "it-IT"; // FIXME: Add language to AuthorizationRequestData
                    Mono<NotificationEmailResponseDto> emailResponse = Mono.just(newStatus)
                            .flatMap(status -> {
                                switch (status) {
                                    case NOTIFIED -> {
                                        return sendSuccessEmail(tx, updateTransactionStatusRequestDto, language);
                                    }
                                    case NOTIFIED_FAILED -> {
                                        return sendKoEmail(tx, updateTransactionStatusRequestDto, language);
                                    }
                                    default -> {
                                        return Mono.error(new IllegalStateException("Invalid new status for closure handler: %s".formatted(status)));
                                    }
                                }
                            });

                    return emailResponse.flatMap(v -> transactionEventStoreRepository.save(event));
                });
    }

    private Mono<NotificationEmailResponseDto> sendKoEmail(
            TransactionClosed tx,
            UpdateTransactionStatusRequestDto updateTransactionStatusRequestDto,
            String language
    ) {
        return notificationsServiceClient.sendKoEmail(
                new NotificationsServiceClient.KoTemplateRequest(
                        tx.getEmail().value(),
                        "Il pagamento non è riuscito",
                        language,
                        new KoTemplate(
                                new it.pagopa.generated.notifications.templates.ko.TransactionTemplate(
                                        amountToHumanReadableString(tx.getAmount().value()),
                                        tx.getTransactionId().value().toString().toUpperCase(),
                                        dateTimeToHumanReadableString(updateTransactionStatusRequestDto.getTimestampOperation(), Locale.forLanguageTag(language))
                                )
                        )
                )
        );
    }

    private Mono<NotificationEmailResponseDto> sendSuccessEmail(
            TransactionClosed tx,
            UpdateTransactionStatusRequestDto updateTransactionStatusRequestDto,
            String language
    ) {
        TransactionAuthorizationRequestData transactionAuthorizationRequestData = tx.getTransactionAuthorizationRequestData();

        return notificationsServiceClient.sendSuccessEmail(
                new NotificationsServiceClient.SuccessTemplateRequest(
                        tx.getEmail().value(),
                        "Il riepilogo del tuo pagamento",
                        language,
                        new SuccessTemplate(
                                new TransactionTemplate(
                                        tx.getTransactionId().value().toString().toUpperCase(),
                                        dateTimeToHumanReadableString(updateTransactionStatusRequestDto.getTimestampOperation(), Locale.forLanguageTag(language)),
                                        amountToHumanReadableString(tx.getAmount().value() + transactionAuthorizationRequestData.getFee()),
                                        new PspTemplate(
                                                transactionAuthorizationRequestData.getPspId(),
                                                new FeeTemplate(amountToHumanReadableString(transactionAuthorizationRequestData.getFee()))
                                        ),
                                        "RRN",
                                        updateTransactionStatusRequestDto.getAuthorizationCode(),
                                        new PaymentMethodTemplate(
                                                transactionAuthorizationRequestData.getPaymentInstrumentId(),
                                                "paymentMethodLogo", // TODO: Logos
                                                null,
                                                false
                                        )
                                ),
                                new UserTemplate(
                                        null,
                                        tx.getEmail().value()
                                ),
                                new CartTemplate(
                                        List.of(
                                                new ItemTemplate(
                                                        new RefNumberTemplate(
                                                                RefNumberTemplate.Type.CODICE_AVVISO,
                                                                tx.getRptId().getNoticeId()
                                                        ),
                                                        null,
                                                        new PayeeTemplate(
                                                                "payeeName",
                                                                tx.getRptId().getFiscalCode()
                                                        ),
                                                        tx.getDescription().value(),
                                                        amountToHumanReadableString(tx.getAmount().value())
                                                )
                                        ),
                                        amountToHumanReadableString(tx.getAmount().value())
                                )
                        )
                )
        );
    }

    private Mono<Transaction> replayTransactionEvents(UUID transactionId) {
        Flux<TransactionEvent<Object>> events = eventStoreRepository.findByTransactionId(transactionId.toString());

        return events.reduce(new EmptyTransaction(), Transaction::applyEvent);
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

    private String dateTimeToHumanReadableString(OffsetDateTime dateTime, Locale locale) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd LLLL yyyy, kk:mm:ss").withLocale(locale);
        return dateTime.format(formatter);
    }
}
