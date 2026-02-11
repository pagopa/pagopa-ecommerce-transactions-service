package it.pagopa.transactions.utils;

import co.elastic.logging.AdditionalField;
import co.elastic.logging.logback.EcsEncoder;

public class EcsAuditLogEncoder extends EcsEncoder {
    public EcsAuditLogEncoder() {

        this.addAdditionalField(new AdditionalField("audit", "true"));
    }

}
