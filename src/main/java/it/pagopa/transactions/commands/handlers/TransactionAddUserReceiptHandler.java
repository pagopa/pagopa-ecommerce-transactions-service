package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.notifications.templates.ko.KoTemplate;
import it.pagopa.generated.notifications.templates.success.*;
import it.pagopa.generated.notifications.v1.dto.NotificationEmailResponseDto;
import it.pagopa.generated.transactions.server.model.AddUserReceiptRequestDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.NotificationsServiceClient;
import it.pagopa.transactions.commands.TransactionAddUserReceiptCommand;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestData;
import it.pagopa.transactions.documents.TransactionEvent;
import it.pagopa.transactions.documents.TransactionAddReceiptData;
import it.pagopa.transactions.documents.TransactionUserReceiptAddedEvent;
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
public class TransactionAddUserReceiptHandler implements CommandHandler<TransactionAddUserReceiptCommand, Mono<TransactionUserReceiptAddedEvent>> {

    @Autowired
    NodeForPspClient nodeForPspClient;

    @Autowired
    private TransactionsEventStoreRepository<TransactionAddReceiptData> transactionEventStoreRepository;
    @Autowired
    private TransactionsEventStoreRepository<Object> eventStoreRepository;

    @Autowired
    NotificationsServiceClient notificationsServiceClient;

    @Override
    public Mono<TransactionUserReceiptAddedEvent> handle(TransactionAddUserReceiptCommand command) {
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
                    AddUserReceiptRequestDto addUserReceiptRequestDto = command.getData().addUserReceiptRequest();
                    TransactionStatusDto newStatus;

                    switch (command.getData().addUserReceiptRequest().getOutcome()) {
                        case OK -> newStatus = TransactionStatusDto.NOTIFIED;
                        case KO -> newStatus = TransactionStatusDto.NOTIFIED_FAILED;
                        default -> {
                            return Mono.error(new RuntimeException("Invalid Nodo sendPaymentResultV2 outcome value"));
                        }
                    }

                    TransactionAddReceiptData transactionAddReceiptData = new TransactionAddReceiptData(newStatus);

                    TransactionUserReceiptAddedEvent event = new TransactionUserReceiptAddedEvent(
                            command.getData().transaction().getTransactionId().value().toString(),
                            command.getData().transaction().getRptId().value(),
                            command.getData().transaction().getTransactionActivatedData().getPaymentToken(),
                            transactionAddReceiptData
                    );

                    String language = "it-IT"; // FIXME: Add language to AuthorizationRequestData
                    Mono<NotificationEmailResponseDto> emailResponse = Mono.just(newStatus)
                            .flatMap(status -> {
                                switch (status) {
                                    case NOTIFIED -> {
                                        return sendSuccessEmail(tx, addUserReceiptRequestDto, language);
                                    }
                                    case NOTIFIED_FAILED -> {
                                        return sendKoEmail(tx, addUserReceiptRequestDto, language);
                                    }
                                    default -> {
                                        return Mono.error(new IllegalStateException("Invalid new status for user receipt handler: %s".formatted(status)));
                                    }
                                }
                            });

                    return emailResponse.flatMap(v -> transactionEventStoreRepository.save(event));
                });
    }

    private Mono<NotificationEmailResponseDto> sendKoEmail(
            TransactionClosed tx,
            AddUserReceiptRequestDto addUserReceiptRequestDto,
            String language
    ) {
        return notificationsServiceClient.sendKoEmail(
                new NotificationsServiceClient.KoTemplateRequest(
                        tx.getEmail().value(),
                        "Il pagamento non è riuscito",
                        language,
                        new KoTemplate(
                                new it.pagopa.generated.notifications.templates.ko.TransactionTemplate(
                                        tx.getTransactionId().value().toString().toUpperCase(),
                                        dateTimeToHumanReadableString(addUserReceiptRequestDto.getPaymentDate(), Locale.forLanguageTag(language)),
                                        amountToHumanReadableString(tx.getAmount().value())
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
        TransactionAuthorizationRequestData transactionAuthorizationRequestData = tx.getTransactionAuthorizationRequestData();

        return notificationsServiceClient.sendSuccessEmail(
                new NotificationsServiceClient.SuccessTemplateRequest(
                        tx.getEmail().value(),
                        "Il riepilogo del tuo pagamento",
                        language,
                        new SuccessTemplate(
                                new TransactionTemplate(
                                        tx.getTransactionId().value().toString().toUpperCase(),
                                        dateTimeToHumanReadableString(addUserReceiptRequestDto.getPaymentDate(), Locale.forLanguageTag(language)),
                                        amountToHumanReadableString(tx.getAmount().value() + transactionAuthorizationRequestData.getFee()),
                                        new PspTemplate(
                                                transactionAuthorizationRequestData.getPspId(),
                                                new FeeTemplate(amountToHumanReadableString(transactionAuthorizationRequestData.getFee()))
                                        ),
                                        "RRN",
                                        tx.getTransactionClosureSendData().getAuthorizationCode(),
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
                                                                addUserReceiptRequestDto.getPayments().get(0).getOfficeName(),
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
