package it.pagopa.transactions.documents;

import it.pagopa.generated.transactions.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.data.mongodb.core.mapping.Document;

@AllArgsConstructor
@Data
@Document
public class TransactionStatusUpdateData {
    private AuthorizationResultDto authorizationResult;
    private TransactionStatusDto newTransactionStatus;
}
