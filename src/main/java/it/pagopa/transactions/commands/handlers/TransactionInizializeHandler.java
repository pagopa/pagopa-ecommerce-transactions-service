package it.pagopa.transactions.commands.handlers;

import java.math.BigDecimal;
import java.security.SecureRandom;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import it.pagopa.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.ecommerce.sessions.v1.dto.SessionTokenDto;
import it.pagopa.nodeforpsp.ActivatePaymentNoticeReq;
import it.pagopa.nodeforpsp.ActivatePaymentNoticeRes;
import it.pagopa.nodeforpsp.CtQrCode;
import it.pagopa.nodeforpsp.ObjectFactory;
import it.pagopa.transactions.client.EcommerceSessionsClient;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.commands.TransactionsCommand;
import it.pagopa.transactions.documents.TransactionEvent;
import it.pagopa.transactions.documents.TransactionInitData;
import it.pagopa.transactions.model.IdempotencyKey;
import it.pagopa.transactions.model.RptId;
import it.pagopa.transactions.repositories.TransactionTokens;
import it.pagopa.transactions.repositories.TransactionTokensRepository;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.transactions.utils.TransactionEventCode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class TransactionInizializeHandler
                implements CommandHandler<TransactionsCommand<NewTransactionRequestDto>, Object> {

        private static final String ALPHANUMERICS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        private static final SecureRandom RANDOM = new SecureRandom();
        private static final String PSP_PAGOPA_ECOMMERCE_FISCAL_CODE = "00000000000";

        @Autowired
        private TransactionTokensRepository transactionTokensRepository;

        @Autowired
        private ObjectFactory objectFactory;

        @Autowired
        private NodeForPspClient nodeForPspClient;

        @Autowired
        private EcommerceSessionsClient ecommerceSessionsClient;

        @Autowired
        private TransactionsEventStoreRepository<TransactionInitData> transactionEventStoreRepository;

        @Override
        public NewTransactionResponseDto handle(TransactionsCommand<NewTransactionRequestDto> command) {

                final RptId rptId = command.getRptId();
                final NewTransactionRequestDto newTransactionRequestDto = command.getData();

                TransactionTokens transactionTokens = transactionTokensRepository
                                .findById(command.getRptId())
                                .orElseGet(() -> {
                                        log.info("Creating new idempotency key for rptId {}", rptId);
                                        final IdempotencyKey key = new IdempotencyKey(
                                                        PSP_PAGOPA_ECOMMERCE_FISCAL_CODE,
                                                        randomString(10));
                                        final TransactionTokens tokens = new TransactionTokens(rptId, key, null);
                                        transactionTokensRepository.save(tokens);
                                        return tokens;
                                });

                log.info("Transaction tokens for " + rptId + ": " + transactionTokens);
                String fiscalCode = rptId.getFiscalCode();
                String noticeId = rptId.getNoticeId();

                CtQrCode qrCode = new CtQrCode();
                qrCode.setFiscalCode(fiscalCode);
                qrCode.setNoticeNumber(noticeId);

                BigDecimal amount = BigDecimal.valueOf(1200);

                ActivatePaymentNoticeReq request = objectFactory.createActivatePaymentNoticeReq();
                request.setAmount(amount);
                request.setQrCode(qrCode);
                request.setIdPSP("6666");
                request.setIdChannel("7777");
                request.setIdBrokerPSP("8888");
                request.setPassword("password");
                request.setIdempotencyKey(transactionTokens.idempotencyKey().getKey());
                request.setPaymentNote(rptId.getNoticeId());
                ActivatePaymentNoticeRes activatePaymentNoticeRes = nodeForPspClient
                                .activatePaymentNotice(objectFactory.createActivatePaymentNoticeReq(request)).block();

                TransactionTokens tokens = new TransactionTokens(rptId, transactionTokens.idempotencyKey(),
                                activatePaymentNoticeRes.getPaymentToken());
                transactionTokensRepository.save(tokens);
                log.info("Persisted transaction tokens for payment token {}",
                                activatePaymentNoticeRes.getPaymentToken());

                SessionDataDto sessionRequest = new SessionDataDto();
                sessionRequest.setEmail(newTransactionRequestDto.getEmail());
                sessionRequest.setPaymentToken(activatePaymentNoticeRes.getPaymentToken());
                sessionRequest.setRptId(newTransactionRequestDto.getRptId());

                SessionTokenDto sessionToken = ecommerceSessionsClient.createSessionToken(sessionRequest).block();

                TransactionInitData data = new TransactionInitData();
                data.setAmount(activatePaymentNoticeRes.getTotalAmount().intValue());
                data.setDescription(activatePaymentNoticeRes.getPaymentDescription());

                TransactionEvent<TransactionInitData> transactionInitializedEvent = new TransactionEvent<>(
                                newTransactionRequestDto.getRptId(), activatePaymentNoticeRes.getPaymentToken(),
                                TransactionEventCode.TRANSACTION_INITIALIZED_EVENT, data);

                transactionEventStoreRepository.save(transactionInitializedEvent)
                                .doOnSuccess(event -> log.info(
                                                "Generated event TRANSACTION_INITIALIZED_EVENT for payment token {}",
                                                event.getPaymentToken()))
                                .subscribe();

                NewTransactionResponseDto response = new NewTransactionResponseDto()
                                .amount(activatePaymentNoticeRes.getTotalAmount().intValue())
                                .reason(activatePaymentNoticeRes.getPaymentDescription());

                response.setAuthToken(sessionToken.getSessionToken());
                response.setPaymentToken(activatePaymentNoticeRes.getPaymentToken());
                response.setRptId(rptId.getRptId());
                return response;
        }

        private String randomString(int len) {
                StringBuilder sb = new StringBuilder(len);
                for (int i = 0; i < len; i++) {
                        sb.append(ALPHANUMERICS.charAt(RANDOM.nextInt(ALPHANUMERICS.length())));
                }
                return sb.toString();
        }

}
