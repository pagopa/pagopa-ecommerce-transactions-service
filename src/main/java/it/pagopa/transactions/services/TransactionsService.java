package it.pagopa.transactions.services;

import it.pagopa.nodeforpsp.ActivatePaymentNoticeReq;
import it.pagopa.nodeforpsp.CtQrCode;
import it.pagopa.nodeforpsp.ObjectFactory;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.model.IdempotencyKey;
import it.pagopa.transactions.model.RptId;
import it.pagopa.transactions.repositories.TransactionTokens;
import it.pagopa.transactions.repositories.TransactionTokensRepository;
import it.pagopa.transactions.server.api.TransactionsApi;
import it.pagopa.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.transactions.server.model.NewTransactionResponseDto;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.Service;

import java.math.BigDecimal;
import java.util.Random;

@Service
@Slf4j
public class TransactionsService {

    private static final String ALPHANUMERICS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Random RANDOM = new Random();
    private static final String PSP_PAGOPA_ECOMMERCE_FISCAL_CODE = "00000000000";

    @Autowired
    private final TransactionTokensRepository transactionTokensRepository;
    @Autowired
    private final ObjectFactory objectFactory;
    @Autowired
    private final NodeForPspClient nodeForPspClient;

    @Override
    public NewTransactionResponseDto createTransactions(NewTransactionRequestDto newTransactionRequestDto) {
        RptId rptId = new RptId(newTransactionRequestDto.getRptId());
        TransactionTokens transactionTokens = transactionTokensRepository
                .findById(rptId)
                .orElseGet(() -> {
                    logger.info("Creating new idempotency key for rptId {}", rptId);
                    final IdempotencyKey key = new IdempotencyKey(
                            PSP_PAGOPA_ECOMMERCE_FISCAL_CODE,
                            randomString(10));
                    final TransactionTokens tokens = new TransactionTokens(rptId, key, null);
                    transactionTokensRepository.save(tokens);
                    return tokens;
                });

        logger.info("Transaction tokens for " + rptId + ": " + transactionTokens);
        String fiscalCode = rptId.getFiscalCode();
        String noticeId = rptId.getNoticeId();

        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(noticeId);

        BigDecimal amount = BigDecimal.valueOf(1200);

        ActivatePaymentNoticeReq request = objectFactory.createActivatePaymentNoticeReq();
        request.setAmount(amount);
        request.setQrCode(qrCode);

        // ActivatePaymentNoticeRes activatePaymentNoticeRes =
        // nodeForPspClient.activatePaymentNotice(objectFactory.createActivatePaymentNoticeReq(request));

        // TransactionTokens tokens = new TransactionTokens(rptId, idempotencyKey,
        // activatePaymentNoticeRes.getPaymentToken());

        NewTransactionResponseDto response = new NewTransactionResponseDto();
        // .amount(activatePaymentNoticeRes.getTotalAmount().intValue())
        // .reason(activatePaymentNoticeRes.getPaymentDescription());

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
