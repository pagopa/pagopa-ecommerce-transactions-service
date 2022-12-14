package it.pagopa.transactions.services;

import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestInfo;
import it.pagopa.ecommerce.commons.repositories.PaymentRequestsInfoRepository;
import it.pagopa.generated.nodoperpsp.model.*;
import it.pagopa.generated.payment.requests.model.PaymentRequestsGetResponseDto;
import it.pagopa.generated.transactions.model.CtQrCode;
import it.pagopa.generated.transactions.model.StOutcome;
import it.pagopa.generated.transactions.model.VerifyPaymentNoticeReq;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.NodoPerPspClient;
import it.pagopa.transactions.exceptions.NodoErrorException;
import it.pagopa.transactions.utils.NodoOperations;
import it.pagopa.transactions.utils.NodoUtilities;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import javax.xml.datatype.XMLGregorianCalendar;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class PaymentRequestsService {

  @Autowired private PaymentRequestsInfoRepository paymentRequestsInfoRepository;

  @Autowired private NodoPerPspClient nodoPerPspClient;

  @Autowired private NodeForPspClient nodeForPspClient;

  @Autowired private it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryNodoPerPsp;

  @Autowired private it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp;

  @Autowired private NodoVerificaRPT baseNodoVerificaRPTRequest;

  @Autowired private VerifyPaymentNoticeReq baseVerifyPaymentNoticeReq;

  @Autowired private NodoOperations nodoOperations;

  @Autowired private NodoUtilities nodoUtilities;

  public Mono<PaymentRequestsGetResponseDto> getPaymentRequestInfo(String rptId) {

    final RptId rptIdRecord = new RptId(rptId);
    final String paymentContextCode = UUID.randomUUID().toString().replace("-", "");

    return getPaymentInfoFromCache(rptIdRecord)
        .doOnNext(
            paymentRequestFromCache ->
                log.info(
                    "PaymentRequestInfo cache hit for {}: {}",
                    rptId,
                    paymentRequestFromCache != null))
        .switchIfEmpty(
            Mono.defer(
                () ->
                    getPaymentInfoFromNodo(rptIdRecord, paymentContextCode)
                        .doOnNext(
                            paymentRequestFromNodo ->
                                log.info(
                                    "PaymentRequestInfo from nodo pagoPA for {}, isNM3 {}",
                                    rptId,
                                    paymentRequestFromNodo.isNM3()))
                        .doOnSuccess(
                            paymenRequestInfo ->
                                paymentRequestsInfoRepository.save(paymenRequestInfo))))
        .map(
            paymentInfo ->
                new PaymentRequestsGetResponseDto()
                    .rptId(paymentInfo.id().value())
                    .paFiscalCode(paymentInfo.paFiscalCode())
                    .paName(paymentInfo.paName())
                    .description(paymentInfo.description())
                    .amount(paymentInfo.amount())
                    .dueDate(paymentInfo.dueDate())
                    .paymentContextCode(paymentContextCode))
        .doOnNext(
            paymentInfo ->
                log.info("PaymentRequestInfo retrived for {}: {}", rptId, paymentInfo != null));
  }

  private Mono<PaymentRequestInfo> getPaymentInfoFromCache(RptId rptId) {

    Optional<PaymentRequestInfo> paymentRequestInfoOptional =
        paymentRequestsInfoRepository.findById(rptId);
    return paymentRequestInfoOptional.map(Mono::just).orElseGet(Mono::empty);
  }

  private Mono<PaymentRequestInfo> getPaymentInfoFromNodo(RptId rptId, String paymentContextCode) {

    return Mono.just(rptId)
        .flatMap(
            request -> {
              NodoVerificaRPT nodoVerificaRPTRequest = baseNodoVerificaRPTRequest;
              NodoTipoCodiceIdRPT nodoTipoCodiceIdRPT = nodoUtilities.getCodiceIdRpt(rptId);
              nodoVerificaRPTRequest.setCodiceIdRPT(nodoTipoCodiceIdRPT);
              nodoVerificaRPTRequest.setCodiceContestoPagamento(paymentContextCode);
              return nodoPerPspClient.verificaRPT(
                  objectFactoryNodoPerPsp.createNodoVerificaRPT(nodoVerificaRPTRequest));
            })
        .flatMap(
            nodoVerificaRPTResponse -> {
              final EsitoNodoVerificaRPTRisposta nodoVerificaRPTRResponse =
                  nodoVerificaRPTResponse.getNodoVerificaRPTRisposta();

              final FaultBean faultBean = nodoVerificaRPTRResponse.getFault();
              final boolean isNM3 = isNm3(nodoVerificaRPTRResponse);
              final boolean isNodoErrorException = isNodoError(nodoVerificaRPTRResponse);
              return isNodoErrorException
                  ? Mono.error(new NodoErrorException(faultBean))
                  : Mono.just(Tuples.of(nodoVerificaRPTRResponse, isNM3));
            })
        .flatMap(
            args -> {
              final EsitoNodoVerificaRPTRisposta nodoVerificaRPTRResponse = args.getT1();
              final Boolean isNM3 = args.getT2();

              Mono<PaymentRequestInfo> paymentRequestInfo = null;

              if (Boolean.TRUE.equals(isNM3)) {

                VerifyPaymentNoticeReq verifyPaymentNoticeReq = baseVerifyPaymentNoticeReq;
                CtQrCode qrCode = new CtQrCode();
                qrCode.setFiscalCode(rptId.getFiscalCode());
                qrCode.setNoticeNumber(rptId.getNoticeId());
                verifyPaymentNoticeReq.setQrCode(qrCode);

                paymentRequestInfo =
                    nodeForPspClient
                        .verifyPaymentNotice(
                            objectFactoryNodeForPsp.createVerifyPaymentNoticeReq(
                                verifyPaymentNoticeReq))
                        .map(
                            verifyPaymentNoticeRes ->
                                new PaymentRequestInfo(
                                    rptId,
                                    verifyPaymentNoticeRes.getFiscalCodePA(),
                                    verifyPaymentNoticeRes.getCompanyName(),
                                    verifyPaymentNoticeRes.getPaymentDescription(),
                                    nodoOperations.getEuroCentsFromNodoAmount(
                                        verifyPaymentNoticeRes
                                            .getPaymentList()
                                            .getPaymentOptionDescription()
                                            .get(0)
                                            .getAmount()),
                                    getDueDateString(
                                        verifyPaymentNoticeRes
                                            .getPaymentList()
                                            .getPaymentOptionDescription()
                                            .get(0)
                                            .getDueDate()),
                                    true,
                                    null,
                                    null));

              } else {

                final CtEnteBeneficiario enteBeneficiario =
                    nodoVerificaRPTRResponse.getDatiPagamentoPA().getEnteBeneficiario();

                paymentRequestInfo =
                    Mono.just(
                        new PaymentRequestInfo(
                            rptId,
                            enteBeneficiario != null
                                    && enteBeneficiario.getIdentificativoUnivocoBeneficiario()
                                        != null
                                ? enteBeneficiario
                                    .getIdentificativoUnivocoBeneficiario()
                                    .getCodiceIdentificativoUnivoco()
                                : null,
                            enteBeneficiario != null
                                ? enteBeneficiario.getDenominazioneBeneficiario()
                                : null,
                            nodoVerificaRPTRResponse.getDatiPagamentoPA().getCausaleVersamento(),
                            nodoOperations.getEuroCentsFromNodoAmount(
                                nodoVerificaRPTRResponse
                                    .getDatiPagamentoPA()
                                    .getImportoSingoloVersamento()),
                            null,
                            false,
                            null,
                            null));
              }
              return paymentRequestInfo;
            });
  }

  private Boolean isNm3(EsitoNodoVerificaRPTRisposta nodoVerificaRPTRResponse) {
    final String outcome = nodoVerificaRPTRResponse.getEsito();
    final Boolean ko = StOutcome.KO.value().equals(outcome);
    return ko
        && nodoVerificaRPTRResponse.getFault().getFaultCode().equals("PPT_MULTI_BENEFICIARIO");
  }

  private Boolean isNodoError(EsitoNodoVerificaRPTRisposta nodoVerificaRPTRResponse) {
    final String outcome = nodoVerificaRPTRResponse.getEsito();
    final Boolean ko = StOutcome.KO.value().equals(outcome);
    return ko
        && !nodoVerificaRPTRResponse.getFault().getFaultCode().equals("PPT_MULTI_BENEFICIARIO");
  }

  private String getDueDateString(XMLGregorianCalendar date) {
    return date != null ? date.toString() : null;
  }
}
