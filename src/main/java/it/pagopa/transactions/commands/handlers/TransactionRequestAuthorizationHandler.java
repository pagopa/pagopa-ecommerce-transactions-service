package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestData;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestedEvent;
import it.pagopa.transactions.domain.Transaction;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class TransactionRequestAuthorizationHandler implements CommandHandler<TransactionRequestAuthorizationCommand, Mono<RequestAuthorizationResponseDto>> {
    @Autowired
    private PaymentGatewayClient paymentGatewayClient;

    @Autowired
    private TransactionsEventStoreRepository<TransactionAuthorizationRequestData> transactionEventStoreRepository;

    @Override
    public Mono<RequestAuthorizationResponseDto> handle(TransactionRequestAuthorizationCommand command) {
        Transaction transaction = command.getData().transaction();

        if (transaction.getStatus() != TransactionStatusDto.INITIALIZED) {
            log.warn("Invalid state transition: requested authorization for transaction {} from status {}", transaction.getPaymentToken().value(), transaction.getStatus());
            return Mono.error(new AlreadyProcessedException(transaction.getRptId()));
        }

        return paymentGatewayClient.requestAuthorization(command.getData())
                .flatMap(auth -> {
                    log.info("Logging authorization event for rpt id {}", transaction.getRptId().value());

                    TransactionAuthorizationRequestedEvent authorizationEvent = new TransactionAuthorizationRequestedEvent(
                            transaction.getRptId().value(),
                            transaction.getPaymentToken().value(),
                            new TransactionAuthorizationRequestData(
                                    command.getData().transaction().getAmount().value(),
                                    command.getData().fee(),
                                    command.getData().paymentInstrumentId(),
                                    command.getData().pspId(),
                                    command.getData().transactionId()
                            )
                    );

                    return transactionEventStoreRepository.save(authorizationEvent).thenReturn(auth);
                });
    }
}
