package it.pagopa.transactions.utils;

import io.opentelemetry.api.common.Attributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UpdateTransactionStatusTracerUtilsTest {

    private OpenTelemetryUtils openTelemetryUtils = Mockito.mock(OpenTelemetryUtils.class);
    private UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils = new UpdateTransactionStatusTracerUtils(
            openTelemetryUtils
    );

    @Captor
    private ArgumentCaptor<Attributes> attributesCaptor;

    @Test
    void shouldTraceTransactionUpdateStatusSuccessfully() {
        UpdateTransactionStatusTracerUtils.StatusUpdateInfo statusUpdateInfo = new UpdateTransactionStatusTracerUtils.StatusUpdateInfo(
                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusType.AUTHORIZATION_OUTCOME,
                UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.NPG,
                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.WRONG_TRANSACTION_STATUS
        );
        // pre-conditions
        doNothing().when(openTelemetryUtils).addSpanWithAttributes(
                eq(UpdateTransactionStatusTracerUtils.UPDATE_TRANSACTION_STATUS_SPAN_NAME),
                attributesCaptor.capture()
        );
        // test
        updateTransactionStatusTracerUtils.traceStatusUpdateOperation(statusUpdateInfo);
        verify(openTelemetryUtils, times(1)).addSpanWithAttributes(any(), any());
        Attributes attributes = attributesCaptor.getValue();
        assertEquals(
                statusUpdateInfo.outcome().toString(),
                attributes.get(UpdateTransactionStatusTracerUtils.UPDATE_TRANSACTION_STATUS_OUTCOME_ATTRIBUTE_KEY)
        );
        assertEquals(
                statusUpdateInfo.type().toString(),
                attributes.get(UpdateTransactionStatusTracerUtils.UPDATE_TRANSACTION_STATUS_TYPE_ATTRIBUTE_KEY)
        );
        assertEquals(
                statusUpdateInfo.trigger().toString(),
                attributes.get(UpdateTransactionStatusTracerUtils.UPDATE_TRANSACTION_STATUS_TRIGGER_ATTRIBUTE_KEY)
        );

    }

}
