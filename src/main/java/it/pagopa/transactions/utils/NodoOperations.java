package it.pagopa.transactions.utils;

import it.pagopa.generated.nodoperpsp.model.*;
import it.pagopa.generated.transactions.model.ActivatePaymentNoticeReq;
import it.pagopa.generated.transactions.model.CtQrCode;
import it.pagopa.generated.transactions.model.StOutcome;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.NodoPerPspClient;
import it.pagopa.transactions.domain.IdempotencyKey;
import it.pagopa.transactions.domain.RptId;
import it.pagopa.transactions.exceptions.NodoErrorException;
import it.pagopa.transactions.repositories.PaymentRequestInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Slf4j
@Component
public class NodoOperations {

  @Autowired NodoPerPspClient nodoPerPspClient;

  @Autowired NodeForPspClient nodeForPspClient;

  @Autowired ActivatePaymentNoticeReq baseActivatePaymentNoticeReq;

  @Autowired NodoAttivaRPT baseNodoAttivaRPT;

  @Autowired it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryNodoPerPsp;

  @Autowired it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp;

  public Mono<PaymentRequestInfo> activatePaymentRequest(
      RptId rptId,
      String paymentContextCode,
      Boolean isNM3,
      Integer amount,
      String paTaxCode,
      String paName,
      IdempotencyKey idempotencyKey,
      String dueDate,
      String description) {

    BigDecimal amountAsBigDecimal =
        BigDecimal.valueOf(amount / 100).setScale(2, RoundingMode.CEILING);

    String fiscalCode = rptId.getFiscalCode();
    String noticeId = rptId.getNoticeId();

    CtQrCode qrCode = new CtQrCode();
    qrCode.setFiscalCode(fiscalCode);
    qrCode.setNoticeNumber(noticeId);

    ActivatePaymentNoticeReq request = baseActivatePaymentNoticeReq;
    request.setAmount(amountAsBigDecimal);
    request.setQrCode(qrCode);
    request.setIdempotencyKey(idempotencyKey.getKey());

    NodoAttivaRPT nodoAttivaRPTReq = baseNodoAttivaRPT;

    NodoTipoCodiceIdRPT nodoTipoCodiceIdRPT = objectFactoryNodoPerPsp.createNodoTipoCodiceIdRPT();
    NodoTipoCodiceIdRPT.QrCode qrCodeVerificaRPT = new NodoTipoCodiceIdRPT.QrCode();
    qrCodeVerificaRPT.setCF(rptId.getFiscalCode());
    qrCodeVerificaRPT.setCodIUV(rptId.getNoticeId().substring(1));
    qrCodeVerificaRPT.setAuxDigit(rptId.getNoticeId().substring(0, 1));
    nodoTipoCodiceIdRPT.setQrCode(qrCodeVerificaRPT);
    NodoTipoDatiPagamentoPSP datiPagamentoPsp =
        objectFactoryNodoPerPsp.createNodoTipoDatiPagamentoPSP();
    datiPagamentoPsp.setImportoSingoloVersamento(amountAsBigDecimal);
    nodoAttivaRPTReq.setDatiPagamentoPSP(datiPagamentoPsp);
    nodoAttivaRPTReq.setCodiceIdRPT(nodoTipoCodiceIdRPT);
    nodoAttivaRPTReq.setCodiceContestoPagamento(paymentContextCode);

    return Mono.just(isNM3)
        .flatMap(
            validIsNM3 ->
                Boolean.TRUE.equals(validIsNM3)
                    ? nodeForPspClient
                        .activatePaymentNotice(
                            objectFactoryNodeForPsp.createActivatePaymentNoticeReq(request))
                        .flatMap(
                            activatePaymentNoticeRes -> {
                              final String outcome = activatePaymentNoticeRes.getOutcome().value();
                              final Boolean isNM3GivenActivatePaymentNotice =
                                  StOutcome.KO.value().equals(outcome)
                                      && "PPT_MULTI_BENEFICIARIO"
                                          .equals(
                                              activatePaymentNoticeRes.getFault().getFaultCode());

                              return StOutcome.KO.value().equals(outcome)
                                      && Boolean.FALSE.equals(isNM3GivenActivatePaymentNotice)
                                  ? Mono.error(
                                      new NodoErrorException(
                                          activatePaymentNoticeRes.getFault().getFaultCode()))
                                  : Mono.just(activatePaymentNoticeRes.getPaymentToken());
                            })
                    : nodoPerPspClient
                        .attivaRPT(objectFactoryNodoPerPsp.createNodoAttivaRPT(nodoAttivaRPTReq))
                        .flatMap(
                            nodoAttivaRPTResponse -> {
                              final EsitoNodoAttivaRPTRisposta nodoAttivaRPTRResponse =
                                  nodoAttivaRPTResponse.getNodoAttivaRPTRisposta();
                              final String outcome = nodoAttivaRPTRResponse.getEsito();
                              final Boolean isNM3GivenAttivaRPTRisposta =
                                  StOutcome.KO.value().equals(outcome)
                                      && "PPT_MULTI_BENEFICIARIO"
                                          .equals(nodoAttivaRPTRResponse.getFault().getFaultCode());

                              return Mono.just(
                                  Tuples.of(nodoAttivaRPTRResponse, isNM3GivenAttivaRPTRisposta));
                            })
                        .flatMap(
                            args -> {
                              final EsitoNodoAttivaRPTRisposta nodoAttivaRPTRResponse =
                                  args.getT1();
                              final Boolean isNM3GivenAttivaRPTRisposta = args.getT2();
                              return Boolean.TRUE.equals(isNM3GivenAttivaRPTRisposta)
                                  ? nodeForPspClient
                                      .activatePaymentNotice(
                                          objectFactoryNodeForPsp.createActivatePaymentNoticeReq(
                                              request))
                                      .flatMap(
                                          x ->
                                              StOutcome.KO.value().equals(x.getOutcome().value())
                                                  ? Mono.error(
                                                      new NodoErrorException(
                                                          nodoAttivaRPTRResponse
                                                              .getFault()
                                                              .getFaultCode()))
                                                  : Mono.just(x.getPaymentToken()))
                                  : StOutcome.KO.value().equals(nodoAttivaRPTRResponse.getEsito())
                                      ? Mono.error(
                                          new NodoErrorException(
                                              nodoAttivaRPTRResponse.getFault().getFaultCode()))
                                      : Mono.just("");
                            }))
        .flatMap(
            paymentToken ->
                Mono.just(
                    new PaymentRequestInfo(
                        rptId,
                        paTaxCode,
                        paName,
                        description,
                        amountAsBigDecimal,
                        dueDate,
                        isNM3,
                        paymentToken,
                        idempotencyKey)));
  }
}
