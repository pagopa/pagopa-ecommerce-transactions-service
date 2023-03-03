package it.pagopa.transactions.services;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PaymentMethodResponseDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PaymentOptionDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.TransferDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.TransferListItemDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentInstrumentsClient;
import it.pagopa.transactions.commands.*;
import it.pagopa.transactions.commands.data.AddUserReceiptData;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.commands.data.ClosureSendData;
import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;
import it.pagopa.transactions.commands.handlers.*;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.projections.handlers.*;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.UUIDUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.util.*;

@Service
@Slf4j
public class TransactionsService {

    @Autowired
    private TransactionActivateHandler transactionActivateHandler;

    @Autowired
    private TransactionRequestAuthorizationHandler transactionRequestAuthorizationHandler;

    @Autowired
    private TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandler;

    @Autowired
    private TransactionAddUserReceiptHandler transactionAddUserReceiptHandler;

    @Autowired
    private TransactionSendClosureHandler transactionSendClosureHandler;

    @Autowired
    private AuthorizationRequestProjectionHandler authorizationProjectionHandler;

    @Autowired
    private AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandler;

    @Autowired
    private TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandler;

    @Autowired
    private ClosureSendProjectionHandler closureSendProjectionHandler;

    @Autowired
    private ClosureErrorProjectionHandler closureErrorProjectionHandler;

    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Autowired
    private EcommercePaymentInstrumentsClient ecommercePaymentInstrumentsClient;

    @Autowired
    private TransactionsActivationProjectionHandler transactionsActivationProjectionHandler;
    @Autowired
    private UUIDUtils uuidUtils;

    @CircuitBreaker(name = "node-backend")
    @Retry(name = "newTransaction")
    public Mono<NewTransactionResponseDto> newTransaction(
                                                          NewTransactionRequestDto newTransactionRequestDto,
                                                          ClientIdDto clientIdDto
    ) {
        ClientId clientId = ClientId.fromString(
                Optional.ofNullable(clientIdDto)
                        .map(ClientIdDto::toString)
                        .orElse(null)
        );
        log.info(
                "Initializing transaction for rptId: {}. ClientId: {}",
                newTransactionRequestDto.getPaymentNotices().get(0).getRptId(),
                clientId
        );
        TransactionActivateCommand transactionActivateCommand = new TransactionActivateCommand(
                new RptId(newTransactionRequestDto.getPaymentNotices().get(0).getRptId()),
                newTransactionRequestDto,
                clientId
        );

        return transactionActivateHandler
                .handle(transactionActivateCommand)
                .doOnNext(
                        args -> log.info(
                                "Transaction initialized for rptId: {}",
                                newTransactionRequestDto.getPaymentNotices().get(0).getRptId()
                        )
                )
                .flatMap(
                        es -> {
                            final Mono<TransactionActivatedEvent> transactionActivatedEvent = es.getT1();
                            final String authToken = es.getT2();
                            return transactionActivatedEvent
                                    .flatMap(t -> projectActivatedEvent(t, authToken));
                        }
                );
    }

    @CircuitBreaker(name = "node-backend")
    @Retry(name = "getTransactionInfo")
    public Mono<TransactionInfoDto> getTransactionInfo(String transactionId) {
        log.info("Get Transaction Invoked with id {} ", transactionId);
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .map(
                        transaction -> new TransactionInfoDto()
                                .transactionId(transaction.getTransactionId())
                                .payments(
                                        transaction.getPaymentNotices().stream().map(
                                                paymentNotice -> new PaymentInfoDto()
                                                        .amount(paymentNotice.getAmount())
                                                        .reason(paymentNotice.getDescription())
                                                        .paymentToken(paymentNotice.getPaymentToken())
                                                        .rptId(paymentNotice.getRptId())
                                        ).toList()
                                )
                                .feeTotal(transaction.getFeeTotal())
                                .clientId(
                                        TransactionInfoDto.ClientIdEnum.valueOf(
                                                transaction.getClientId().toString()
                                        )
                                )
                                .status(TransactionStatusDto.fromValue(transaction.getStatus().toString()))
                );
    }

    @CircuitBreaker(name = "transactions-backend")
    @Retry(name = "requestTransactionAuthorization")
    public Mono<RequestAuthorizationResponseDto> requestTransactionAuthorization(
                                                                                 String transactionId,
                                                                                 String paymentGatewayId,
                                                                                 RequestAuthorizationRequestDto requestAuthorizationRequestDto
    ) {
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .flatMap(
                        transaction -> {
                            Integer amountTotal = transaction.getPaymentNotices().stream()
                                    .mapToInt(
                                            it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount
                                    ).sum();
                            log.info(
                                    "Authorization request amount validation for transactionId: {}",
                                    transactionId
                            );
                            return !amountTotal.equals(requestAuthorizationRequestDto.getAmount())
                                    ? Mono.error(
                                            new TransactionAmountMismatchException(
                                                    requestAuthorizationRequestDto.getAmount(),
                                                    amountTotal
                                            )
                                    )
                                    : Mono.just(transaction);
                        }
                )
                .flatMap(
                        transaction -> {
                            log.info(
                                    "Authorization psp validation for transactionId: {}",
                                    transactionId
                            );
                            Integer amountTotal = transaction.getPaymentNotices().stream()
                                    .mapToInt(
                                            it.pagopa.ecommerce.commons.documents.v1.PaymentNotice::getAmount
                                    ).sum();
                            return ecommercePaymentInstrumentsClient // TODO gestione del carrello
                                    .calculateFee(
                                            new PaymentOptionDto()
                                                    .paymentMethodId(
                                                            requestAuthorizationRequestDto.getPaymentInstrumentId()
                                                    )
                                                    .touchpoint(transaction.getClientId().toString())
                                                    .bin(
                                                            requestAuthorizationRequestDto
                                                                    .getDetails()instanceof CardAuthRequestDetailsDto cardData
                                                                            ? cardData.getPan().substring(0, 8)
                                                                            : null
                                                    )
                                                    .idPspList(List.of(requestAuthorizationRequestDto.getPspId()))
                                                    .paymentAmount(amountTotal.longValue())
                                                    .primaryCreditorInstitution(
                                                            transaction.getPaymentNotices().get(0).getRptId()
                                                                    .substring(0, 11)
                                                    )
                                                    .transferList(
                                                            List.of(
                                                                    new TransferListItemDto().creditorInstitution(
                                                                            transaction.getPaymentNotices().get(0)
                                                                                    .getRptId().substring(0, 11)
                                                                    ).digitalStamp(false)
                                                            )
                                                    ),
                                            null
                                    )
                                    .mapNotNull(el -> el.getBundleOptions() != null ? (el.getBundleOptions()) : null)
                                    .mapNotNull(
                                            fee -> fee.stream()
                                                    .filter(
                                                            psp -> psp.getIdPsp()
                                                                    .equals(
                                                                            requestAuthorizationRequestDto
                                                                                    .getPspId()
                                                                    )
                                                                    && psp.getTaxPayerFee()
                                                                            .equals(
                                                                                    Long.valueOf(
                                                                                            requestAuthorizationRequestDto
                                                                                                    .getFee()
                                                                                    )
                                                                            )
                                                    )
                                                    .findFirst()
                                                    .orElse(null)
                                    )
                                    .map(bundle -> Tuples.of(transaction, bundle));
                        }
                )
                .flatMap(transactionAndPsp -> {
                    log.info(
                            "Requesting payment instrument data for id {}",
                            requestAuthorizationRequestDto.getPaymentInstrumentId()
                    );
                    return ecommercePaymentInstrumentsClient
                            .getPaymentMethod(requestAuthorizationRequestDto.getPaymentInstrumentId())
                            .map(
                                    paymentMethod -> Tuples
                                            .of(
                                                    transactionAndPsp.getT1(),
                                                    transactionAndPsp.getT2(),
                                                    paymentMethod
                                            )
                            );
                })
                .switchIfEmpty(
                        Mono.error(
                                new UnsatisfiablePspRequestException(
                                        new PaymentToken(transactionId),
                                        requestAuthorizationRequestDto.getLanguage(),
                                        requestAuthorizationRequestDto.getFee()
                                )
                        )
                )
                .flatMap(
                        args -> {
                            it.pagopa.ecommerce.commons.documents.v1.Transaction transactionDocument = args
                                    .getT1();
                            TransferDto bundle = args.getT2();
                            PaymentMethodResponseDto paymentMethod = args.getT3();

                            log.info(
                                    "Requesting authorization for transactionId: {}",
                                    transactionDocument.getTransactionId()
                            );

                            TransactionActivated transaction = new TransactionActivated(
                                    new TransactionId(
                                            UUID.fromString(transactionDocument.getTransactionId())
                                    ),
                                    transactionDocument.getPaymentNotices().stream()
                                            .map(
                                                    paymentNotice -> new PaymentNotice(
                                                            new PaymentToken(
                                                                    paymentNotice.getPaymentToken()
                                                            ),
                                                            new RptId(paymentNotice.getRptId()),
                                                            new TransactionAmount(
                                                                    paymentNotice.getAmount()
                                                            ),
                                                            new TransactionDescription(
                                                                    paymentNotice.getDescription()
                                                            ),
                                                            new PaymentContextCode(
                                                                    paymentNotice
                                                                            .getPaymentContextCode()
                                                            )
                                                    )
                                            ).toList(),
                                    transactionDocument.getEmail(),
                                    null,
                                    null,
                                    transactionDocument.getClientId()
                            );

                            AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                                    transaction,
                                    requestAuthorizationRequestDto.getFee(),
                                    requestAuthorizationRequestDto.getPaymentInstrumentId(),
                                    requestAuthorizationRequestDto.getPspId(),
                                    bundle.getPaymentMethod(),
                                    bundle.getIdBrokerPsp(),
                                    bundle.getIdChannel(),
                                    paymentMethod.getName(),
                                    bundle.getBundleName(),
                                    paymentGatewayId,
                                    requestAuthorizationRequestDto.getDetails()
                            );

                            // FIXME Handle multiple rtpId
                            TransactionRequestAuthorizationCommand transactionRequestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                                    transaction.getPaymentNotices().get(0).rptId(),
                                    authorizationData
                            );

                            return transactionRequestAuthorizationHandler
                                    .handle(transactionRequestAuthorizationCommand)
                                    .doOnNext(
                                            res -> log.info(
                                                    "Requested authorization for transaction: {}",
                                                    transactionDocument.getTransactionId()
                                            )
                                    )
                                    .flatMap(
                                            res -> authorizationProjectionHandler
                                                    .handle(authorizationData)
                                                    .thenReturn(res)
                                    );
                        }
                );
    }

    @CircuitBreaker(name = "node-backend")
    @Retry(name = "updateTransactionAuthorization")
    public Mono<TransactionInfoDto> updateTransactionAuthorization(
                                                                   String transactionId,
                                                                   UpdateAuthorizationRequestDto updateAuthorizationRequestDto
    ) {

        String transactionIdDecoded = uuidUtils.uuidFromBase64(transactionId).toString();
        return transactionsViewRepository
                .findById(transactionIdDecoded)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionIdDecoded)))
                .flatMap(
                        transactionDocument -> {
                            TransactionActivated transaction = new TransactionActivated(
                                    new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
                                    transactionDocument.getPaymentNotices().stream()
                                            .map(
                                                    paymentNotice -> new PaymentNotice(
                                                            new PaymentToken(paymentNotice.getPaymentToken()),
                                                            new RptId(paymentNotice.getRptId()),
                                                            new TransactionAmount(paymentNotice.getAmount()),
                                                            new TransactionDescription(paymentNotice.getDescription()),
                                                            new PaymentContextCode(
                                                                    paymentNotice.getPaymentContextCode()
                                                            )
                                                    )
                                            ).toList(),
                                    transactionDocument.getEmail(),
                                    null,
                                    null,
                                    transactionDocument.getClientId()
                            );

                            UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                                    transaction,
                                    updateAuthorizationRequestDto
                            );

                            // FIXME Handle multiple rtpId
                            TransactionUpdateAuthorizationCommand transactionUpdateAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                                    transaction.getPaymentNotices().get(0).rptId(),
                                    updateAuthorizationStatusData
                            );

                            return transactionUpdateAuthorizationHandler
                                    .handle(transactionUpdateAuthorizationCommand)
                                    .doOnNext(
                                            authorizationStatusUpdatedEvent -> log.info(
                                                    "Requested authorization update for rptId: {}",
                                                    // FIXME Handle multiple rtpId
                                                    transaction.getPaymentNotices().get(0).rptId()
                                            )
                                    )
                                    .flatMap(
                                            authorizationStatusUpdatedEvent -> authorizationUpdateProjectionHandler
                                                    .handle(
                                                            authorizationStatusUpdatedEvent
                                                    )
                                    );
                        }
                )
                .cast(TransactionActivated.class)
                .flatMap(
                        transaction -> {
                            ClosureSendData closureSendData = new ClosureSendData(
                                    transaction,
                                    updateAuthorizationRequestDto
                            );

                            TransactionClosureSendCommand transactionClosureSendCommand = new TransactionClosureSendCommand(
                                    transaction.getPaymentNotices().get(0).rptId(),
                                    closureSendData
                            );

                            return transactionSendClosureHandler
                                    .handle(transactionClosureSendCommand)
                                    .doOnNext(
                                            closureSentEvent ->
                            // FIXME Handle multiple rtpId
                            log.info(
                                    "Requested transaction closure for rptId: {}",
                                    transaction.getPaymentNotices().get(0).rptId().value()
                            )
                                    )
                                    .flatMap(
                                            result -> result.fold(
                                                    errorEvent -> closureErrorProjectionHandler.handle(errorEvent),
                                                    closureSentEvent -> closureSendProjectionHandler
                                                            .handle(closureSentEvent)
                                            )
                                    )
                                    .map(
                                            transactionDocument -> new TransactionInfoDto()
                                                    .transactionId(transactionDocument.getTransactionId())
                                                    .payments(
                                                            transactionDocument.getPaymentNotices().stream().map(
                                                                    paymentNotice -> new PaymentInfoDto()
                                                                            .amount(paymentNotice.getAmount())
                                                                            .reason(paymentNotice.getDescription())
                                                                            .paymentToken(
                                                                                    paymentNotice.getPaymentToken()
                                                                            )
                                                                            .rptId(paymentNotice.getRptId())
                                                            ).toList()
                                                    )
                                                    .status(
                                                            TransactionStatusDto.fromValue(
                                                                    transactionDocument.getStatus().toString()
                                                            )
                                                    )
                                    );
                        }
                );
    }

    @CircuitBreaker(name = "transactions-backend")
    @Retry(name = "addUserReceipt")
    public Mono<TransactionInfoDto> addUserReceipt(
                                                   String transactionId,
                                                   AddUserReceiptRequestDto addUserReceiptRequest
    ) {
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .map(
                        transactionDocument -> {
                            TransactionActivated transaction = new TransactionActivated(
                                    new TransactionId(UUID.fromString(transactionDocument.getTransactionId())),
                                    transactionDocument.getPaymentNotices().stream()
                                            .map(
                                                    paymentNotice -> new PaymentNotice(
                                                            new PaymentToken(paymentNotice.getPaymentToken()),
                                                            new RptId(paymentNotice.getRptId()),
                                                            new TransactionAmount(paymentNotice.getAmount()),
                                                            new TransactionDescription(paymentNotice.getDescription()),
                                                            new PaymentContextCode(
                                                                    paymentNotice.getPaymentContextCode()
                                                            )
                                                    )
                                            )
                                            .toList(),
                                    transactionDocument.getEmail(),
                                    null,
                                    null,
                                    transactionDocument.getClientId()
                            );
                            AddUserReceiptData addUserReceiptData = new AddUserReceiptData(
                                    transaction,
                                    addUserReceiptRequest
                            );
                            // FIXME Handle multiple rtpId
                            return new TransactionAddUserReceiptCommand(
                                    transaction.getPaymentNotices().get(0).rptId(),
                                    addUserReceiptData
                            );
                        }
                )
                .flatMap(
                        transactionAddUserReceiptCommand -> transactionAddUserReceiptHandler
                                .handle(transactionAddUserReceiptCommand)
                )
                .doOnNext(
                        transactionUserReceiptAddedEvent -> log.info(
                                "{} for transactionId: {}",
                                TransactionEventCode.TRANSACTION_USER_RECEIPT_ADDED_EVENT,
                                transactionUserReceiptAddedEvent.getTransactionId()
                        )
                )
                .flatMap(
                        transactionUserReceiptAddedEvent -> transactionUserReceiptProjectionHandler
                                .handle(transactionUserReceiptAddedEvent)
                )
                .cast(TransactionActivated.class)
                .map(
                        transaction -> new TransactionInfoDto()
                                .transactionId(transaction.getTransactionId().value().toString())
                                .payments(
                                        transaction.getPaymentNotices().stream().map(
                                                paymentNotice -> new PaymentInfoDto()
                                                        .amount(
                                                                transaction.getTransactionActivatedData()
                                                                        .getPaymentNotices().stream()
                                                                        .filter(
                                                                                paymentNoticeData -> paymentNoticeData
                                                                                        .getRptId().equals(
                                                                                                paymentNotice.rptId()
                                                                                                        .value()
                                                                                        )
                                                                        )
                                                                        .findFirst().get()
                                                                        .getAmount()
                                                        )
                                                        .reason(paymentNotice.transactionDescription().value())
                                                        .paymentToken(paymentNotice.paymentToken().value())
                                                        .rptId(paymentNotice.rptId().value())
                                        ).toList()
                                )
                                .status(TransactionStatusDto.NOTIFIED)
                )
                .doOnNext(
                        transaction -> log.info(
                                "Transaction status updated {} for transactionId: {}",
                                transaction.getStatus(),
                                transaction.getTransactionId()
                        )
                );
    }

    @CircuitBreaker(name = "node-backend")
    @Retry(name = "activateTransaction")
    public Mono<ActivationResultResponseDto> activateTransaction(
                                                                 String paymentContextCode,
                                                                 ActivationResultRequestDto activationResultRequestDto
    ) {
        return Mono.error(new NotImplementedException("Activate transaction operation not implemented"));
    }

    private Mono<NewTransactionResponseDto> projectActivatedEvent(
                                                                  TransactionActivatedEvent transactionActivatedEvent,
                                                                  String authToken
    ) {
        return transactionsActivationProjectionHandler
                .handle(transactionActivatedEvent)
                .map(
                        transaction -> new NewTransactionResponseDto()
                                .transactionId(transaction.getTransactionId().value().toString())
                                .payments(
                                        transaction.getPaymentNotices().stream().map(
                                                paymentNotice -> new PaymentInfoDto()
                                                        .amount(paymentNotice.transactionAmount().value())
                                                        .reason(paymentNotice.transactionDescription().value())
                                                        .rptId(paymentNotice.rptId().value())
                                                        .paymentToken(paymentNotice.paymentToken().value())
                                        ).toList()
                                )
                                .authToken(authToken)
                                .status(TransactionStatusDto.fromValue(transaction.getStatus().toString()))
                                // .feeTotal()//TODO da dove prendere le fees?
                                .clientId(convertClientId(transaction.getClientId()))
                );
    }

    NewTransactionResponseDto.ClientIdEnum convertClientId(
                                                           it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId clientId
    ) {
        return Optional.ofNullable(clientId).filter(Objects::nonNull)
                .map(
                        enumVal -> {
                            try {
                                return NewTransactionResponseDto.ClientIdEnum.fromValue(enumVal.toString());
                            } catch (IllegalArgumentException e) {
                                log.error("Unknown input origin ", e);
                                throw new InvalidRequestException("Unknown input origin", e);
                            }
                        }
                ).orElseThrow(() -> new InvalidRequestException("Null value as input origin"));
    }
}
