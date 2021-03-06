package it.pagopa.transactions.documents;

import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentResponseDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.data.mongodb.core.mapping.Document;

@AllArgsConstructor
@Data
@Document
public class TransactionClosureSendData {
    private ClosePaymentResponseDto.EsitoEnum nodeClosePaymentOutcome;
    private TransactionStatusDto newTransactionStatus;
}
