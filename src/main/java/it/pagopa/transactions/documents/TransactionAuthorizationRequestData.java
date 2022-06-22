package it.pagopa.transactions.documents;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.data.mongodb.core.mapping.Document;

@AllArgsConstructor
@Data
@Document
public class TransactionAuthorizationRequestData {
    private int amount;
    private int fee;
    private String paymentInstrumentId;
    private String pspId;
}
