package it.pagopa.transactions.utils;

import io.opentelemetry.api.common.Attributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UpdateTransactionStatusTracerUtilsTest {

    private final OpenTelemetryUtils openTelemetryUtils = Mockito.mock(OpenTelemetryUtils.class);
    private final UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils = new UpdateTransactionStatusTracerUtils(
            openTelemetryUtils
    );

    @Captor
    private ArgumentCaptor<Attributes> attributesCaptor;

    @ParameterizedTest
    @EnumSource(UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.class)
    void shouldTraceTransactionUpdateStatusSuccessfullyForNodoDetails(
                                                                      UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome outcome
    ) {
        UpdateTransactionStatusTracerUtils.StatusUpdateInfo statusUpdateInfo = new UpdateTransactionStatusTracerUtils.NodoStatusUpdate(
                outcome
        );
        // pre-conditions
        doNothing().when(openTelemetryUtils).addSpanWithAttributes(
                eq(UpdateTransactionStatusTracerUtils.UPDATE_TRANSACTION_STATUS_SPAN_NAME),
                attributesCaptor.capture()
        );
        // test
        updateTransactionStatusTracerUtils.traceStatusUpdateOperation(statusUpdateInfo);
        // assertions
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

    private static Stream<Arguments> tracePaymentGatewayDetailsTestMethodSource() {
        return Stream.of(
                Arguments.of(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.PGS_XPAY
                ),
                Arguments.of(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.PGS_VPOS
                ),
                Arguments.of(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.NPG
                ),
                Arguments.of(UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.UNKNOWN)
        );
    }

    @ParameterizedTest
    @MethodSource("tracePaymentGatewayDetailsTestMethodSource")
    void shouldTraceTransactionUpdateStatusSuccessfullyForPaymentGatewayDetails(
                                                                                UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger trigger
    ) {
        UpdateTransactionStatusTracerUtils.StatusUpdateInfo statusUpdateInfo = new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdate(
                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.OK,
                new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdateContext(
                        trigger,
                        Optional.of("CHECKOUT"),
                        Optional.of("CP"),
                        Optional.empty(),
                        Optional.empty()
                )
        );
        // pre-conditions
        doNothing().when(openTelemetryUtils).addSpanWithAttributes(
                eq(UpdateTransactionStatusTracerUtils.UPDATE_TRANSACTION_STATUS_SPAN_NAME),
                attributesCaptor.capture()
        );
        // test
        updateTransactionStatusTracerUtils.traceStatusUpdateOperation(statusUpdateInfo);
        // assertions
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

    private static Stream<Arguments> tracePspIdMethodSource() {
        return Stream.of(
                Arguments.of("pspId", "pspId"),
                Arguments.of(null, "N/A")
        );
    }

    @ParameterizedTest
    @MethodSource("tracePspIdMethodSource")
    void shouldTracePspId(
                          String pspId,
                          String expectedSpanPspAttribute
    ) {
        UpdateTransactionStatusTracerUtils.StatusUpdateInfo statusUpdateInfo = new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdate(
                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.OK,
                new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdateContext(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.REDIRECT,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.ofNullable(pspId),
                        Optional.empty()
                )
        );
        // pre-conditions
        doNothing().when(openTelemetryUtils).addSpanWithAttributes(
                eq(UpdateTransactionStatusTracerUtils.UPDATE_TRANSACTION_STATUS_SPAN_NAME),
                attributesCaptor.capture()
        );
        // test
        updateTransactionStatusTracerUtils.traceStatusUpdateOperation(statusUpdateInfo);
        // assertions
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

        assertEquals(
                expectedSpanPspAttribute,
                attributes.get(UpdateTransactionStatusTracerUtils.UPDATE_TRANSACTION_STATUS_PSP_ID_ATTRIBUTE_KEY)
        );

    }

    @Test
    void shouldThrowExceptionBuildingInvalidPaymentGatewayStatusUpdateRecordWithNullAttributes() {
        assertThrows(
                NullPointerException.class,
                () -> new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdate(
                        null,
                        null
                )
        );
    }

    @Test
    void shouldThrowExceptionBuildingInvalidPaymentGatewayStatusUpdateContextWithNullAttributes() {
        assertThrows(
                NullPointerException.class,
                () -> new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdateContext(
                        null,
                        null,
                        null,
                        null,
                        null
                )
        );
    }

    @Test
    void shouldThrowExceptionBuildingInvalidPaymentGatewayStatusUpdateRecordWithInvalidPaymentGatewayType() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdate(
                        UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.OK,
                        new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdateContext(
                                UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.NODO,
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()
                        )
                )
        );
        assertEquals("Invalid trigger for PaymentGatewayStatusUpdate: NODO", exception.getMessage());
    }

}
