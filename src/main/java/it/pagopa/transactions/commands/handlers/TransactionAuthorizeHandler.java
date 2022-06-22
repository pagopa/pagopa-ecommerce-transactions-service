package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.transactions.server.model.RequestAuthorizationResponseDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.commands.TransactionAuthorizeCommand;
import it.pagopa.transactions.documents.TransactionAuthorizationData;
import it.pagopa.transactions.documents.TransactionAuthorizationEvent;
import it.pagopa.transactions.domain.Transaction;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class TransactionAuthorizeHandler implements CommandHandler<TransactionAuthorizeCommand, Mono<RequestAuthorizationResponseDto>> {
    @Autowired
    private PaymentGatewayClient paymentGatewayClient;

    @Autowired
    private TransactionsEventStoreRepository<TransactionAuthorizationData> transactionEventStoreRepository;

    @Override
    public Mono<RequestAuthorizationResponseDto> handle(TransactionAuthorizeCommand command) {
        Transaction transaction = command.getData().transaction();

        if (transaction.getStatus() != TransactionStatusDto.INITIALIZED) {
            log.warn("Invalid state transition: requested authorization for transaction {} from status {}", transaction.getPaymentToken().value(), transaction.getStatus());
            return Mono.error(new AlreadyProcessedException(transaction.getRptId()));
        }

        return paymentGatewayClient.requestAuthorization(command.getData())
                .flatMap(auth -> {
                    log.info("Logging authorization event for rpt id {}", transaction.getRptId().value());

                    TransactionAuthorizationEvent authorizationEvent = new TransactionAuthorizationEvent(
                            transaction.getRptId().value(),
                            transaction.getPaymentToken().value(),
                            new TransactionAuthorizationData(
                                    command.getData().transaction().getAmount().value(),
                                    command.getData().fee(),
                                    command.getData().paymentInstrumentId(),
                                    command.getData().pspId()
                            )
                    );

                    return transactionEventStoreRepository.save(authorizationEvent).thenReturn(auth);
                });
    }
}
