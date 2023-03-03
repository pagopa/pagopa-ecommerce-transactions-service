package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.paymentinstruments.v1.api.DefaultApi;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EcommercePaymentMethodsClientTest {

    @InjectMocks
    private EcommercePaymentInstrumentsClient ecommercePaymentInstrumentsClient;

    @Mock
    private DefaultApi ecommercePaymentInstrumentsWebClient;

    @Test
    void shouldReturnBundleList() {
        Integer TEST_MAX_OCCURRERNCES = 10;
        PaymentOptionDto paymentOptionDto = new PaymentOptionDto()
                .paymentMethodId("paymentMethodId").paymentAmount(BigInteger.TEN.longValue()).bin("57497554")
                .touchpoint("CHECKOUT").primaryCreditorInstitution("7777777777").idPspList(List.of("pspId"));

        BundleOptionDto bundleOptionDto = new BundleOptionDto().belowThreshold(true).bundleOptions(
                List.of(
                        new TransferDto().abi("abiTest")
                                .bundleDescription("descriptionTest")
                                .bundleName("bundleNameTest")
                                .idBrokerPsp("idBrokerPspTest")
                                .idBundle("idBundleTest")
                                .idChannel("idChannelTest")
                                .idCiBundle("idCiBundleTest")
                                .idPsp("idPspTest")
                                .onUs(true)
                                .paymentMethod("idPaymentMethodTest")
                                .primaryCiIncurredFee(BigInteger.ZERO.longValue())
                                .taxPayerFee(BigInteger.ZERO.longValue())
                                .touchpoint("CHECKOUT")
                )
        );

        /**
         * preconditions
         */
        when(ecommercePaymentInstrumentsWebClient.calculateFees(paymentOptionDto, TEST_MAX_OCCURRERNCES))
                .thenReturn(Mono.just(bundleOptionDto));

        /**
         * test
         */
        BundleOptionDto bundleOptionDtoResponse = ecommercePaymentInstrumentsClient
                .calculateFee(paymentOptionDto, TEST_MAX_OCCURRERNCES)
                .block();

        /**
         * asserts
         */
        assertThat(bundleOptionDtoResponse).isEqualTo(bundleOptionDto);
    }

    @Test
    void shouldReturnPaymentMethod() {
        String TEST_ID = UUID.randomUUID().toString();

        PaymentMethodResponseDto testPaymentMethodResponseDto = new PaymentMethodResponseDto();
        testPaymentMethodResponseDto
                .description("")
                .addRangesItem(new RangeDto().max(100L).min(0L))
                .paymentTypeCode("PO")
                .status(PaymentMethodResponseDto.StatusEnum.ENABLED)
                .id(TEST_ID)
                .name("test");

        /**
         * preconditions
         */
        when(ecommercePaymentInstrumentsWebClient.getPaymentMethod(TEST_ID))
                .thenReturn(Mono.just(testPaymentMethodResponseDto));

        /**
         * test
         */
        PaymentMethodResponseDto paymentMethodResponseDto = ecommercePaymentInstrumentsClient.getPaymentMethod(TEST_ID)
                .block();

        /**
         * asserts
         */
        assertThat(testPaymentMethodResponseDto.getId()).isEqualTo(paymentMethodResponseDto.getId());
    }

}
