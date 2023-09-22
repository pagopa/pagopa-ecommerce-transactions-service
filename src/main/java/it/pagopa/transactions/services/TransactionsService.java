package it.pagopa.transactions.services;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.BaseTransactionView;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedData;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.domain.v1.TransactionEventCode;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.BundleDto;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.CalculateFeeRequestDto;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.CalculateFeeResponseDto;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.TransferListItemDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.commands.*;
import it.pagopa.transactions.commands.data.AddUserReceiptData;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.commands.data.ClosureSendData;
import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;
import it.pagopa.transactions.commands.handlers.TransactionActivateHandler;
import it.pagopa.transactions.commands.handlers.TransactionRequestUserReceiptHandler;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.projections.handlers.TransactionUserReceiptProjectionHandler;
import it.pagopa.transactions.projections.handlers.TransactionsActivationProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.UUIDUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class TransactionsService {

    @Autowired
    private TransactionActivateHandler transactionActivateHandler;

    @Autowired
    private it.pagopa.transactions.commands.handlers.v1.TransactionRequestAuthorizationHandler requestAuthHandlerV1;
    @Autowired
    private it.pagopa.transactions.commands.handlers.v2.TransactionRequestAuthorizationHandler requestAuthHandlerV2;

    @Autowired
    @Qualifier(it.pagopa.transactions.commands.handlers.v1.TransactionUpdateAuthorizationHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.commands.handlers.v1.TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandlerV1;

    @Autowired
    @Qualifier(it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandlerV2;

    @Autowired
    @Qualifier(it.pagopa.transactions.commands.handlers.v1.TransactionSendClosureHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.commands.handlers.v1.TransactionSendClosureHandler transactionSendClosureHandlerV1;

    @Autowired
    @Qualifier(it.pagopa.transactions.commands.handlers.v2.TransactionSendClosureHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.commands.handlers.v2.TransactionSendClosureHandler transactionSendClosureHandlerV2;

    @Autowired
    private TransactionRequestUserReceiptHandler transactionRequestUserReceiptHandler;

    @Autowired
    @Qualifier(it.pagopa.transactions.commands.handlers.v1.TransactionUserCancelHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.commands.handlers.v1.TransactionUserCancelHandler transactionCancelHandlerV1;

    @Autowired
    @Qualifier(it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler transactionCancelHandlerV2;

    @Autowired
    @Qualifier(it.pagopa.transactions.projections.handlers.v1.AuthorizationRequestProjectionHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.projections.handlers.v1.AuthorizationRequestProjectionHandler authorizationProjectionHandlerV1;

    @Autowired
    @Qualifier(it.pagopa.transactions.projections.handlers.v2.AuthorizationRequestProjectionHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.projections.handlers.v2.AuthorizationRequestProjectionHandler authorizationProjectionHandlerV2;

    @Autowired
    @Qualifier(it.pagopa.transactions.projections.handlers.v1.AuthorizationUpdateProjectionHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.projections.handlers.v1.AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandlerV1;

    @Autowired
    @Qualifier(it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandlerV2;

    @Autowired
    @Qualifier(it.pagopa.transactions.projections.handlers.v1.RefundRequestProjectionHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.projections.handlers.v1.RefundRequestProjectionHandler refundRequestProjectionHandlerV1;

    @Autowired
    @Qualifier(it.pagopa.transactions.projections.handlers.v2.RefundRequestProjectionHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.projections.handlers.v2.RefundRequestProjectionHandler refundRequestProjectionHandlerV2;

    @Autowired
    @Qualifier(it.pagopa.transactions.projections.handlers.v1.ClosureSendProjectionHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.projections.handlers.v1.ClosureSendProjectionHandler closureSendProjectionHandlerV1;

    @Autowired
    @Qualifier(it.pagopa.transactions.projections.handlers.v2.ClosureSendProjectionHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.projections.handlers.v2.ClosureSendProjectionHandler closureSendProjectionHandlerV2;

    @Autowired
    @Qualifier(it.pagopa.transactions.projections.handlers.v1.ClosureErrorProjectionHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.projections.handlers.v1.ClosureErrorProjectionHandler closureErrorProjectionHandlerV1;

    @Autowired
    @Qualifier(it.pagopa.transactions.projections.handlers.v2.ClosureErrorProjectionHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.projections.handlers.v2.ClosureErrorProjectionHandler closureErrorProjectionHandlerV2;

    @Autowired
    @Qualifier(it.pagopa.transactions.projections.handlers.v1.CancellationRequestProjectionHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.projections.handlers.v1.CancellationRequestProjectionHandler cancellationRequestProjectionHandlerV1;

    @Autowired
    @Qualifier(it.pagopa.transactions.projections.handlers.v2.CancellationRequestProjectionHandler.QUALIFIER_NAME)
    private it.pagopa.transactions.projections.handlers.v2.CancellationRequestProjectionHandler cancellationRequestProjectionHandlerV2;

    @Autowired
    private TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandler;

    @Autowired
    private TransactionsViewRepository transactionsViewRepository;

    @Autowired
    private EcommercePaymentMethodsClient ecommercePaymentMethodsClient;

    @Autowired
    private TransactionsActivationProjectionHandler transactionsActivationProjectionHandler;

    @Autowired
    private UUIDUtils uuidUtils;

    @Autowired
    private TransactionsUtils transactionsUtils;

    @Autowired
    private TransactionsEventStoreRepository<TransactionAuthorizationCompletedData> eventStoreRepository;

    @Autowired
    private TransactionsEventStoreRepository<Object> eventsRepository;

    @Value("${payment.token.validity}")
    private Integer paymentTokenValidity;

    @CircuitBreaker(name = "node-backend")
    @Retry(name = "newTransaction")
    public Mono<NewTransactionResponseDto> newTransaction(
                                                          NewTransactionRequestDto newTransactionRequestDto,
                                                          ClientIdDto clientIdDto,
                                                          TransactionId transactionId
    ) {
        Transaction.ClientId clientId = Transaction.ClientId.fromString(
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
                clientId,
                transactionId
        );

        return transactionActivateHandler.handle(transactionActivateCommand)
                .doOnNext(
                        args -> log.info(
                                "Transaction initialized for rptId: {}",
                                newTransactionRequestDto.getPaymentNotices().get(0).getRptId()
                        )
                )
                .flatMap(
                        es -> {
                            final Mono<it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent> transactionActivatedEvent = es
                                    .getT1();
                            final String authToken = es.getT2();
                            return transactionActivatedEvent
                                    .flatMap(t -> projectActivatedEvent(t, authToken));
                        }
                );
    }

    @CircuitBreaker(name = "ecommerce-db")
    @Retry(name = "getTransactionInfo")
    public Mono<TransactionInfoDto> getTransactionInfo(String transactionId) {
        log.info("Get Transaction Invoked with id {} ", transactionId);
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .map(this::buildTransactionInfoDtoFromView);
    }

    private TransactionInfoDto buildTransactionInfoDtoFromView(BaseTransactionView baseTransactionView) {
        return switch (baseTransactionView) {
            case it.pagopa.ecommerce.commons.documents.v1.Transaction transaction -> new TransactionInfoDto()
                    .transactionId(transaction.getTransactionId())
                    .payments(
                            transaction.getPaymentNotices().stream().map(
                                    paymentNotice -> new PaymentInfoDto()
                                            .amount(paymentNotice.getAmount())
                                            .reason(paymentNotice.getDescription())
                                            .paymentToken(paymentNotice.getPaymentToken())
                                            .rptId(paymentNotice.getRptId())
                                            .isAllCCP(paymentNotice.isAllCCP())
                                            .transferList(
                                                    paymentNotice.getTransferList().stream().map(
                                                            notice -> new TransferDto()
                                                                    .transferCategory(
                                                                            notice.getTransferCategory()
                                                                    )
                                                                    .transferAmount(
                                                                            notice.getTransferAmount()
                                                                    ).digitalStamp(notice.getDigitalStamp())
                                                                    .paFiscalCode(notice.getPaFiscalCode())
                                                    ).toList()
                                            )
                            ).toList()
                    )
                    .feeTotal(transaction.getFeeTotal())
                    .clientId(
                            TransactionInfoDto.ClientIdEnum.valueOf(
                                    transaction.getClientId().toString()
                            )
                    )
                    .status(transactionsUtils.convertEnumeration(transaction.getStatus()))
                    .idCart(transaction.getIdCart())
                    .paymentGateway(transaction.getPaymentGateway())
                    .sendPaymentResultOutcome(
                            transaction.getSendPaymentResultOutcome() == null ? null
                                    : TransactionInfoDto.SendPaymentResultOutcomeEnum
                                    .valueOf(transaction.getSendPaymentResultOutcome().name())
                    )
                    .authorizationCode(transaction.getAuthorizationCode())
                    .authorizationErrorCode(transaction.getAuthorizationErrorCode());
            case it.pagopa.ecommerce.commons.documents.v2.Transaction transaction -> new TransactionInfoDto()
                    .transactionId(transaction.getTransactionId())
                    .payments(
                            transaction.getPaymentNotices().stream().map(
                                    paymentNotice -> new PaymentInfoDto()
                                            .amount(paymentNotice.getAmount())
                                            .reason(paymentNotice.getDescription())
                                            .paymentToken(paymentNotice.getPaymentToken())
                                            .rptId(paymentNotice.getRptId())
                                            .isAllCCP(paymentNotice.isAllCCP())
                                            .transferList(
                                                    paymentNotice.getTransferList().stream().map(
                                                            notice -> new TransferDto()
                                                                    .transferCategory(
                                                                            notice.getTransferCategory()
                                                                    )
                                                                    .transferAmount(
                                                                            notice.getTransferAmount()
                                                                    ).digitalStamp(notice.getDigitalStamp())
                                                                    .paFiscalCode(notice.getPaFiscalCode())
                                                    ).toList()
                                            )
                            ).toList()
                    )
                    .feeTotal(transaction.getFeeTotal())
                    .clientId(
                            TransactionInfoDto.ClientIdEnum.valueOf(
                                    transaction.getClientId().toString()
                            )
                    )
                    .status(transactionsUtils.convertEnumeration(transaction.getStatus()))
                    .idCart(transaction.getIdCart())
                    .paymentGateway(transaction.getPaymentGateway())
                    .sendPaymentResultOutcome(
                            transaction.getSendPaymentResultOutcome() == null ? null
                                    : TransactionInfoDto.SendPaymentResultOutcomeEnum
                                    .valueOf(transaction.getSendPaymentResultOutcome().name())
                    )
                    .authorizationCode(transaction.getAuthorizationCode())
                    .authorizationErrorCode(transaction.getAuthorizationErrorCode());
            default -> throw new IllegalStateException("Unexpected value: " + baseTransactionView);
        };
    }

    @Retry(name = "cancelTransaction")
    public Mono<Void> cancelTransaction(String transactionId) {
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .flatMap(
                        transactionDocument -> {
                            TransactionUserCancelCommand transactionCancelCommand = new TransactionUserCancelCommand(
                                    null,
                                    new TransactionId(transactionId)
                            );

                            return switch (transactionDocument) {
                                case it.pagopa.ecommerce.commons.documents.v1.Transaction t ->
                                        transactionCancelHandlerV1
                                                .handle(transactionCancelCommand).flatMap(event -> cancellationRequestProjectionHandlerV1
                                                        .handle((it.pagopa.ecommerce.commons.documents.v1.TransactionUserCanceledEvent) event));

                                case it.pagopa.ecommerce.commons.documents.v2.Transaction t ->
                                        transactionCancelHandlerV2
                                                .handle(transactionCancelCommand).flatMap(event -> cancellationRequestProjectionHandlerV2
                                                        .handle((it.pagopa.ecommerce.commons.documents.v2.TransactionUserCanceledEvent) event));
                                default ->
                                        Mono.error(new BadGatewayException("Error while processing request unexpected transaction version type", HttpStatus.BAD_GATEWAY));
                            };
                        }
                )
                .then();

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
                            Integer amountTotal = transactionsUtils.getTransactionTotalAmount(transaction);

                            Boolean isAllCCP = transactionsUtils.isAllCcp(transaction, 0);
                            log.info(
                                    "Authorization request amount validation for transactionId: {}",
                                    transactionId
                            );
                            boolean amountMismatch = !amountTotal.equals(requestAuthorizationRequestDto.getAmount());
                            boolean allCCPMismatch = !isAllCCP.equals(requestAuthorizationRequestDto.getIsAllCCP());
                            return amountMismatch || allCCPMismatch
                                    ? (amountMismatch ? Mono.error(
                                    new TransactionAmountMismatchException(
                                            requestAuthorizationRequestDto.getAmount(),
                                            amountTotal
                                    )
                            )
                                    : Mono.error(
                                    new PaymentNoticeAllCCPMismatchException(
                                            transactionsUtils.getRptId(transaction, 0),
                                            requestAuthorizationRequestDto.getIsAllCCP(),
                                            isAllCCP
                                    )
                            ))
                                    : Mono.just(transaction);
                        }
                )
                .flatMap(
                        transaction -> {
                            log.info(
                                    "Authorization psp validation for transactionId: {}",
                                    transactionId
                            );
                            Integer amountTotal = transactionsUtils.getTransactionTotalAmount(transaction);
                            return retrieveInformationFromAuthorizationRequest(requestAuthorizationRequestDto)
                                    .flatMap(
                                            authorizationInfo -> ecommercePaymentMethodsClient
                                                    .calculateFee(
                                                            requestAuthorizationRequestDto.getPaymentInstrumentId(),
                                                            transactionId,
                                                            new CalculateFeeRequestDto()
                                                                    .touchpoint(
                                                                            transactionsUtils.getClientId(transaction)
                                                                    )
                                                                    .bin(
                                                                            authorizationInfo.map(Tuple2::getT1)
                                                                                    .orElse(null)
                                                                    )
                                                                    .idPspList(
                                                                            List.of(
                                                                                    requestAuthorizationRequestDto
                                                                                            .getPspId()
                                                                            )
                                                                    )
                                                                    .paymentAmount(amountTotal.longValue())
                                                                    .primaryCreditorInstitution(
                                                                            transactionsUtils.getRptId(transaction, 0)
                                                                                    .substring(0, 11)
                                                                    )
                                                                    .transferList(
                                                                            transactionsUtils
                                                                                    .getPaymentNotices(transaction)
                                                                                    .get(0)
                                                                                    .getTransferList()
                                                                                    .stream()
                                                                                    .map(
                                                                                            t -> new TransferListItemDto()
                                                                                                    .creditorInstitution(
                                                                                                            t.getPaFiscalCode()
                                                                                                    )
                                                                                                    .digitalStamp(
                                                                                                            t.getDigitalStamp()
                                                                                                    )
                                                                                                    .transferCategory(
                                                                                                            t.getTransferCategory()
                                                                                                    )
                                                                                    ).toList()
                                                                    )
                                                                    .isAllCCP(
                                                                            transactionsUtils.isAllCcp(transaction, 0)
                                                                    ),
                                                            Integer.MAX_VALUE
                                                    )
                                                    .map(
                                                            calculateFeeResponseDto -> Tuples.of(
                                                                    calculateFeeResponseDto,
                                                                    authorizationInfo.flatMap(Tuple2::getT2)
                                                            )
                                                    )
                                    )
                                    .map(
                                            data -> {
                                                CalculateFeeResponseDto calculateFeeResponse = data.getT1();
                                                return Tuples.of(
                                                        calculateFeeResponse.getPaymentMethodName(),
                                                        calculateFeeResponse.getPaymentMethodDescription(),
                                                        calculateFeeResponse.getBundles().stream()
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
                                                                ).findFirst(),
                                                        data.getT2()
                                                );
                                            }
                                    )
                                    .filter(t -> t.getT3().isPresent())
                                    .switchIfEmpty(
                                            Mono.error(
                                                    new UnsatisfiablePspRequestException(
                                                            new PaymentToken(transactionId),
                                                            requestAuthorizationRequestDto.getLanguage(),
                                                            requestAuthorizationRequestDto.getFee()
                                                    )
                                            )
                                    )
                                    .map(
                                            t -> Tuples.of(
                                                    transaction,
                                                    t.getT1(),
                                                    t.getT2(),
                                                    t.getT3().get(),
                                                    t.getT4()
                                            )

                                    );
                        }
                )
                .flatMap(
                        args -> {
                            it.pagopa.ecommerce.commons.documents.BaseTransactionView transactionDocument = args
                                    .getT1();
                            String paymentMethodName = args.getT2();
                            String paymentMethodDescription = args.getT3();
                            BundleDto bundle = args.getT4();
                            Optional<String> sessionId = args.getT5();

                            log.info(
                                    "Requesting authorization for transactionId: {}",
                                    transactionDocument.getTransactionId()
                            );

                            AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                                    new TransactionId(
                                            transactionDocument.getTransactionId()
                                    ),
                                    transactionsUtils.getPaymentNotices(transactionDocument).stream().map(
                                            paymentNotice -> new PaymentNotice(
                                                    new PaymentToken(paymentNotice.getPaymentToken()),
                                                    new RptId(paymentNotice.getRptId()),
                                                    new TransactionAmount(paymentNotice.getAmount()),
                                                    new TransactionDescription(paymentNotice.getDescription()),
                                                    new PaymentContextCode(
                                                            paymentNotice.getPaymentContextCode()
                                                    ),
                                                    paymentNotice.getTransferList().stream()
                                                            .map(
                                                                    transfer -> new PaymentTransferInfo(
                                                                            transfer.getPaFiscalCode(),
                                                                            transfer.getDigitalStamp(),
                                                                            transfer.getTransferAmount(),
                                                                            transfer.getTransferCategory()
                                                                    )
                                                            ).toList(),
                                                    paymentNotice.isAllCCP()
                                            )
                                    ).toList(),
                                    transactionsUtils.getEmail(transactionDocument),
                                    requestAuthorizationRequestDto.getFee(),
                                    requestAuthorizationRequestDto.getPaymentInstrumentId(),
                                    requestAuthorizationRequestDto.getPspId(),
                                    bundle.getPaymentMethod(),
                                    bundle.getIdBrokerPsp(),
                                    bundle.getIdChannel(),
                                    paymentMethodName,
                                    paymentMethodDescription,
                                    bundle.getBundleName(),
                                    bundle.getOnUs(),
                                    paymentGatewayId,
                                    sessionId,
                                    requestAuthorizationRequestDto.getDetails()
                            );

                            // FIXME Handle multiple rtpId
                            TransactionRequestAuthorizationCommand transactionRequestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                                    new RptId(transactionsUtils.getRptId(transactionDocument, 0)),
                                    authorizationData
                            );
                            Mono<RequestAuthorizationResponseDto> authorizationHandler = switch (transactionDocument) {
                                case it.pagopa.ecommerce.commons.documents.v1.Transaction t -> requestAuthHandlerV1
                                        .handle(transactionRequestAuthorizationCommand)
                                        .doOnNext(
                                                res -> log.info(
                                                        "Requested authorization for transaction: {}",
                                                        transactionDocument.getTransactionId()
                                                )
                                        )
                                        .flatMap(
                                                res -> authorizationProjectionHandlerV1
                                                        .handle(authorizationData)
                                                        .thenReturn(res)
                                        );
                                case it.pagopa.ecommerce.commons.documents.v2.Transaction t -> requestAuthHandlerV2
                                        .handle(transactionRequestAuthorizationCommand).doOnNext(
                                                res -> log.info(
                                                        "Requested authorization for transaction: {}",
                                                        transactionDocument.getTransactionId()
                                                )
                                        )
                                        .flatMap(
                                                res -> authorizationProjectionHandlerV2
                                                        .handle(authorizationData)
                                                        .thenReturn(res)
                                        );
                                default -> throw new RuntimeException("OPS");
                            };
                            return authorizationHandler;
                        }
                );
    }

    @Retry(name = "updateTransactionAuthorization")
    public Mono<TransactionInfoDto> updateTransactionAuthorization(
                                                                   UUID decodedTransactionId,
                                                                   UpdateAuthorizationRequestDto updateAuthorizationRequestDto
    ) {

        TransactionId transactionId = new TransactionId(decodedTransactionId);
        log.info("decoded transaction id: {}", transactionId.value());

        Flux<BaseTransactionEvent<Object>> events = eventsRepository
                .findByTransactionIdOrderByCreationDateAsc(transactionId.value())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId.value())));

        Mono<it.pagopa.ecommerce.commons.domain.v1.Transaction> transactionV1 = transactionsUtils.reduceEvents(
                events,
                new it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction(),
                it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent,
                it.pagopa.ecommerce.commons.domain.v1.Transaction.class
        )
                .filter(t -> !(t instanceof it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction));

        Mono<it.pagopa.ecommerce.commons.domain.v2.Transaction> transactionV2 = transactionsUtils.reduceEvents(
                events,
                new it.pagopa.ecommerce.commons.domain.v2.EmptyTransaction(),
                it.pagopa.ecommerce.commons.domain.v2.Transaction::applyEvent,
                it.pagopa.ecommerce.commons.domain.v2.Transaction.class
        )
                .filter(t -> !(t instanceof it.pagopa.ecommerce.commons.domain.v2.EmptyTransaction));

        Mono<TransactionInfoDto> v1Info = transactionV1
                .cast(it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction.class)
                .flatMap(t -> this.updateTransactionAuthorizationStatusV1(t, updateAuthorizationRequestDto));

        Mono<TransactionInfoDto> v2Info = transactionV2
                .cast(it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction.class)
                .flatMap(t -> this.updateTransactionAuthorizationStatusV2(t, updateAuthorizationRequestDto));

        return v1Info
                .switchIfEmpty(v2Info)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId.value())));
    }

    private Mono<TransactionInfoDto> updateTransactionAuthorizationStatusV1(
                                                                            it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction transaction,
                                                                            UpdateAuthorizationRequestDto updateAuthorizationRequestDto
    ) {
        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction.getTransactionId(),
                transaction.getStatus().toString(),
                updateAuthorizationRequestDto
        );

        // FIXME Handle multiple rtpId
        TransactionUpdateAuthorizationCommand transactionUpdateAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                updateAuthorizationStatusData
        );

        Mono<it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction> baseTransaction = Mono.just(transaction);
        return wasTransactionAuthorized(transaction.getTransactionId())
                .<Either<TransactionInfoDto, Mono<BaseTransaction>>>flatMap(alreadyAuthorized -> {
                    if (Boolean.FALSE.equals(alreadyAuthorized)) {
                        return Mono.just(baseTransaction).map(Either::right);
                    } else {
                        return baseTransaction.map(
                                trx -> {
                                    log.info(
                                            "Transaction authorization outcome already received. Transaction status: {}",
                                            trx.getStatus()
                                    );
                                    return buildTransactionInfoDtoV1(trx);
                                }
                        ).map(Either::left);
                    }
                })
                .flatMap(
                        either -> either.fold(
                                Mono::just,
                                tx -> baseTransaction
                                        .flatMap(
                                                t -> transactionUpdateAuthorizationHandlerV1
                                                        .handle(transactionUpdateAuthorizationCommand)
                                                        .cast(
                                                                it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedEvent.class
                                                        )
                                                        .doOnNext(
                                                                authorizationStatusUpdatedEvent -> log.info(
                                                                        "Requested authorization update for rptId: {}",
                                                                        transaction.getPaymentNotices().get(0).rptId()
                                                                )
                                                        )
                                                        .flatMap(
                                                                authorizationStatusUpdatedEvent -> authorizationUpdateProjectionHandlerV1
                                                                        .handle(authorizationStatusUpdatedEvent)
                                                        )
                                        )
                                        .doOnError(
                                                AlreadyProcessedException.class,
                                                t -> log.error(
                                                        "Error: requesting authorization update for transaction in state {}",
                                                        baseTransaction
                                                )
                                        )
                                        .cast(
                                                it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithPaymentToken.class
                                        )
                                        .flatMap(
                                                t -> closePaymentV1(
                                                        t,
                                                        updateAuthorizationRequestDto
                                                )
                                        )
                                        .map(this::buildTransactionInfoDtoV1)
                        )
                );
    }

    private Mono<TransactionInfoDto> updateTransactionAuthorizationStatusV2(
                                                                            it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction transaction,
                                                                            UpdateAuthorizationRequestDto updateAuthorizationRequestDto
    ) {
        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction.getTransactionId(),
                transaction.getStatus().toString(),
                updateAuthorizationRequestDto
        );

        // FIXME Handle multiple rtpId
        TransactionUpdateAuthorizationCommand transactionUpdateAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                updateAuthorizationStatusData
        );

        Mono<it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction> baseTransaction = Mono.just(transaction);
        return wasTransactionAuthorized(
                transaction.getTransactionId()
        ).<Either<TransactionInfoDto, Mono<it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction>>>flatMap(
                alreadyAuthorized -> {
                    if (Boolean.FALSE.equals(alreadyAuthorized)) {
                        return Mono.just(baseTransaction).map(Either::right);
                    } else {
                        return baseTransaction.map(
                                trx -> {
                                    log.info(
                                            "Transaction authorization outcome already received. Transaction status: {}",
                                            trx.getStatus()
                                    );
                                    return buildTransactionInfoDtoV2(trx);
                                }
                        ).map(Either::left);
                    }
                }
        )
                .flatMap(
                        either -> either.fold(
                                Mono::just,
                                tx -> baseTransaction
                                        .flatMap(
                                                t -> transactionUpdateAuthorizationHandlerV2
                                                        .handle(transactionUpdateAuthorizationCommand)
                                                        .cast(
                                                                it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedEvent.class
                                                        )
                                                        .doOnNext(
                                                                authorizationStatusUpdatedEvent -> log.info(
                                                                        "Requested authorization update for rptId: {}",
                                                                        transaction.getPaymentNotices().get(0).rptId()
                                                                )
                                                        )
                                                        .flatMap(
                                                                authorizationStatusUpdatedEvent -> authorizationUpdateProjectionHandlerV2
                                                                        .handle(authorizationStatusUpdatedEvent)
                                                        )
                                        )
                                        .doOnError(
                                                AlreadyProcessedException.class,
                                                t -> log.error(
                                                        "Error: requesting authorization update for transaction in state {}",
                                                        baseTransaction
                                                )
                                        )
                                        .cast(
                                                it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransactionWithPaymentToken.class
                                        )
                                        .flatMap(
                                                t -> closePaymentV2(
                                                        t,
                                                        updateAuthorizationRequestDto
                                                )
                                        )
                                        .map(this::buildTransactionInfoDtoV2)
                        )
                );
    }

    private Mono<it.pagopa.ecommerce.commons.documents.v1.Transaction> closePaymentV1(
                                                                                      it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithPaymentToken transaction,
                                                                                      UpdateAuthorizationRequestDto updateAuthorizationRequestDto
    ) {
        ClosureSendData closureSendData = new ClosureSendData(
                transaction.getTransactionId(),
                updateAuthorizationRequestDto
        );

        TransactionClosureSendCommand transactionClosureSendCommand = new TransactionClosureSendCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                closureSendData
        );

        return transactionSendClosureHandlerV1
                .handle(transactionClosureSendCommand)
                .doOnNext(closureSentEvent ->
                // FIXME Handle multiple rtpId
                log.info(
                        "Requested transaction closure for rptId: {}",
                        transaction.getPaymentNotices().get(0).rptId().value()
                )
                )
                .flatMap(
                        el -> el.getT1().map(
                                refundEvent -> refundRequestProjectionHandlerV1.handle(
                                        (it.pagopa.ecommerce.commons.documents.v1.TransactionRefundRequestedEvent) refundEvent
                                )
                        ).orElse(
                                el.getT2().fold(
                                        closureErrorEvent -> closureErrorProjectionHandlerV1.handle(
                                                (it.pagopa.ecommerce.commons.documents.v1.TransactionClosureErrorEvent) closureErrorEvent
                                        ),
                                        closureDataTransactionEvent -> closureSendProjectionHandlerV1
                                                .handle(
                                                        (it.pagopa.ecommerce.commons.documents.v1.TransactionEvent<it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData>) closureDataTransactionEvent
                                                )
                                )
                        )

                );
    }

    private Mono<it.pagopa.ecommerce.commons.documents.v2.Transaction> closePaymentV2(
                                                                                      it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransactionWithPaymentToken transaction,
                                                                                      UpdateAuthorizationRequestDto updateAuthorizationRequestDto
    ) {
        ClosureSendData closureSendData = new ClosureSendData(
                transaction.getTransactionId(),
                updateAuthorizationRequestDto
        );

        TransactionClosureSendCommand transactionClosureSendCommand = new TransactionClosureSendCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                closureSendData
        );

        return transactionSendClosureHandlerV2
                .handle(transactionClosureSendCommand)
                .doOnNext(closureSentEvent ->
                // FIXME Handle multiple rtpId
                log.info(
                        "Requested transaction closure for rptId: {}",
                        transaction.getPaymentNotices().get(0).rptId().value()
                )
                )
                .flatMap(
                        el -> el.getT1().map(
                                refundEvent -> refundRequestProjectionHandlerV2.handle(
                                        (it.pagopa.ecommerce.commons.documents.v2.TransactionRefundRequestedEvent) refundEvent
                                )
                        ).orElse(
                                el.getT2().fold(
                                        closureErrorEvent -> closureErrorProjectionHandlerV2.handle(
                                                (it.pagopa.ecommerce.commons.documents.v2.TransactionClosureErrorEvent) closureErrorEvent
                                        ),
                                        closureDataTransactionEvent -> closureSendProjectionHandlerV2
                                                .handle(
                                                        (it.pagopa.ecommerce.commons.documents.v2.TransactionEvent<it.pagopa.ecommerce.commons.documents.v2.TransactionClosureData>) closureDataTransactionEvent
                                                )
                                )
                        )

                );
    }

    private TransactionInfoDto buildTransactionInfoDtoV1(
                                                         it.pagopa.ecommerce.commons.documents.v1.Transaction transactionDocument
    ) {
        return new TransactionInfoDto()
                .transactionId(
                        transactionDocument
                                .getTransactionId()
                )
                .payments(
                        transactionDocument
                                .getPaymentNotices()
                                .stream()
                                .map(
                                        paymentNotice -> new PaymentInfoDto()
                                                .amount(paymentNotice.getAmount())
                                                .reason(paymentNotice.getDescription())
                                                .paymentToken(paymentNotice.getPaymentToken())
                                                .rptId(paymentNotice.getRptId())
                                )
                                .toList()
                )
                .status(transactionsUtils.convertEnumeration(transactionDocument.getStatus()));

    }

    private TransactionInfoDto buildTransactionInfoDtoV2(
                                                         it.pagopa.ecommerce.commons.documents.v2.Transaction transactionDocument
    ) {
        return new TransactionInfoDto()
                .transactionId(
                        transactionDocument
                                .getTransactionId()
                )
                .payments(
                        transactionDocument
                                .getPaymentNotices()
                                .stream()
                                .map(
                                        paymentNotice -> new PaymentInfoDto()
                                                .amount(paymentNotice.getAmount())
                                                .reason(paymentNotice.getDescription())
                                                .paymentToken(paymentNotice.getPaymentToken())
                                                .rptId(paymentNotice.getRptId())
                                )
                                .toList()
                )
                .status(transactionsUtils.convertEnumeration(transactionDocument.getStatus()));

    }

    private TransactionInfoDto buildTransactionInfoDtoV1(
                                                         it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction baseTransaction
    ) {
        return new TransactionInfoDto()
                .transactionId(baseTransaction.getTransactionId().value())
                .payments(
                        baseTransaction.getPaymentNotices()
                                .stream().map(
                                        paymentNotice -> new PaymentInfoDto()
                                                .amount(paymentNotice.transactionAmount().value())
                                                .reason(paymentNotice.transactionDescription().value())
                                                .paymentToken(paymentNotice.paymentToken().value())
                                                .rptId(paymentNotice.rptId().value())
                                ).toList()
                )
                .status(transactionsUtils.convertEnumeration(baseTransaction.getStatus()));
    }

    private TransactionInfoDto buildTransactionInfoDtoV2(
                                                         it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction baseTransaction
    ) {
        return new TransactionInfoDto()
                .transactionId(baseTransaction.getTransactionId().value())
                .payments(
                        baseTransaction.getPaymentNotices()
                                .stream().map(
                                        paymentNotice -> new PaymentInfoDto()
                                                .amount(paymentNotice.transactionAmount().value())
                                                .reason(paymentNotice.transactionDescription().value())
                                                .paymentToken(paymentNotice.paymentToken().value())
                                                .rptId(paymentNotice.rptId().value())
                                ).toList()
                )
                .status(transactionsUtils.convertEnumeration(baseTransaction.getStatus()));

    }

    private Mono<Boolean> wasTransactionAuthorized(
                                                   TransactionId transactionId
    ) {
        /*
         * @formatter:off
         *
         * This method determines whether transaction has been previously authorized or not
         * by searching for an authorization completed event.
         * The check is performed directly on the presence of an authorization completed event
         * and not on the fact that the transaction aggregate is an instance of `BaseTransactionWithCompletedAuthorization`
         * because a generic transaction can go in the REFUNDED or EXPIRED states without undergoing authorization
         * (the corresponding aggregates do not extend, in fact, `BaseTransactionWithCompletedAuthorization`).
         *
         * This can happen, for example, when a transaction expires before getting a payment gateway response
         * (for the EXPIRED state; if in REFUNDED that means the transaction was already refunded).
         *
         * @formatter:on
         */
        return eventStoreRepository
                .findByTransactionIdAndEventCode(
                        transactionId.value(),
                        TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT.toString()
                )
                .map(v -> true)
                .switchIfEmpty(Mono.just(false));

    }

    @Retry(name = "addUserReceipt")
    public Mono<TransactionInfoDto> addUserReceipt(
                                                   String transactionId,
                                                   AddUserReceiptRequestDto addUserReceiptRequest
    ) {
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .cast(it.pagopa.ecommerce.commons.documents.v1.Transaction.class)
                .map(
                        transactionDocument -> {
                            TransactionActivated transaction = new TransactionActivated(
                                    new TransactionId(transactionDocument.getTransactionId()),
                                    transactionDocument.getPaymentNotices().stream()
                                            .map(
                                                    paymentNotice -> new PaymentNotice(
                                                            new PaymentToken(paymentNotice.getPaymentToken()),
                                                            new RptId(paymentNotice.getRptId()),
                                                            new TransactionAmount(paymentNotice.getAmount()),
                                                            new TransactionDescription(paymentNotice.getDescription()),
                                                            new PaymentContextCode(
                                                                    paymentNotice.getPaymentContextCode()
                                                            ),
                                                            paymentNotice.getTransferList().stream()
                                                                    .map(
                                                                            transfer -> new PaymentTransferInfo(
                                                                                    transfer.getPaFiscalCode(),
                                                                                    transfer.getDigitalStamp(),
                                                                                    transfer.getTransferAmount(),
                                                                                    transfer.getTransferCategory()
                                                                            )
                                                                    ).toList(),
                                                            paymentNotice.isAllCCP()
                                                    )
                                            )
                                            .toList(),
                                    transactionDocument.getEmail(),
                                    null,
                                    null,
                                    transactionDocument.getClientId(),
                                    transactionDocument.getIdCart(),
                                    paymentTokenValidity

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
                        transactionAddUserReceiptCommand -> transactionRequestUserReceiptHandler
                                .handle(transactionAddUserReceiptCommand)
                )
                .doOnNext(
                        transactionUserReceiptRequestedEvent -> log.info(
                                "{} for transactionId: {}",
                                TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT,
                                transactionUserReceiptRequestedEvent.getTransactionId()
                        )
                )
                .flatMap(
                        transactionUserReceiptRequestedEvent -> transactionUserReceiptProjectionHandler
                                .handle(transactionUserReceiptRequestedEvent)
                )
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
                                .status(transactionsUtils.convertEnumeration(transaction.getStatus()))
                )
                .doOnNext(
                        transaction -> log.info(
                                "Transaction status updated {} for transactionId: {}",
                                transaction.getStatus(),
                                transaction.getTransactionId()
                        )
                );

    }

    private Mono<NewTransactionResponseDto> projectActivatedEvent(
                                                                  TransactionActivatedEvent transactionActivatedEvent,
                                                                  String authToken
    ) {
        return transactionsActivationProjectionHandler
                .handle(transactionActivatedEvent)
                .map(
                        transaction -> new NewTransactionResponseDto()
                                .transactionId(transaction.getTransactionId().value())
                                .payments(
                                        transaction.getPaymentNotices().stream().map(
                                                paymentNotice -> new PaymentInfoDto()
                                                        .amount(paymentNotice.transactionAmount().value())
                                                        .reason(paymentNotice.transactionDescription().value())
                                                        .rptId(paymentNotice.rptId().value())
                                                        .paymentToken(paymentNotice.paymentToken().value())
                                                        .isAllCCP(paymentNotice.isAllCCP())
                                                        .transferList(
                                                                paymentNotice.transferList().stream().map(
                                                                        paymentTransferInfo -> new TransferDto()
                                                                                .digitalStamp(
                                                                                        paymentTransferInfo
                                                                                                .digitalStamp()
                                                                                )
                                                                                .paFiscalCode(
                                                                                        paymentTransferInfo
                                                                                                .paFiscalCode()
                                                                                )
                                                                                .transferAmount(
                                                                                        paymentTransferInfo
                                                                                                .transferAmount()
                                                                                )
                                                                                .transferCategory(
                                                                                        paymentTransferInfo
                                                                                                .transferCategory()
                                                                                )
                                                                ).toList()
                                                        )
                                        ).toList()
                                )
                                .authToken(authToken)
                                .status(transactionsUtils.convertEnumeration(transaction.getStatus()))
                                // .feeTotal()//TODO da dove prendere le fees?
                                .clientId(convertClientId(transaction.getClientId()))
                                .idCart(transaction.getTransactionActivatedData().getIdCart())
                );
    }

    NewTransactionResponseDto.ClientIdEnum convertClientId(
                                                           it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId clientId
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

    private Mono<Optional<Tuple2<String, Optional<String>>>> retrieveInformationFromAuthorizationRequest(RequestAuthorizationRequestDto requestAuthorizationRequestDto) {
        return switch (requestAuthorizationRequestDto.getDetails()) {
            case CardAuthRequestDetailsDto cardData ->
                    Mono.just(Optional.of(Tuples.of(cardData.getPan().substring(0, 6), Optional.empty())));
            case CardsAuthRequestDetailsDto cards ->
                    ecommercePaymentMethodsClient.retrieveCardData(requestAuthorizationRequestDto.getPaymentInstrumentId(), cards.getOrderId()).map(response -> Optional.of(Tuples.of(response.getBin(), Optional.of(response.getSessionId()))));
            default -> Mono.just(Optional.empty());
        };
    }
}
