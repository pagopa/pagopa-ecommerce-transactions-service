package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.paymentinstruments.v1.api.DefaultApi;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PSPsResponseDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PaymentMethodResponseDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.PspDto;
import it.pagopa.generated.ecommerce.paymentinstruments.v1.dto.RangeDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

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
    void shouldReturnPspList() {
        Integer TEST_AMOUNT = 100;
        String TEST_LANG = "IT";
        String TEST_ID = UUID.randomUUID().toString();

        PSPsResponseDto testResponseDto = new PSPsResponseDto();
        testResponseDto.setPsp(List.of(new PspDto()
                .code("AA")
                .language(PspDto.LanguageEnum.IT)
                .paymentTypeCode("PO")
                .fixedCost(100.0)
                .brokerName("brokerName")
                .description("")
                .maxAmount(0.0)
                .maxAmount(1000.0)
                .channelCode("CH1")
                .status(PspDto.StatusEnum.ENABLED)));

        /**
         * preconditions
         */
        when(ecommercePaymentInstrumentsWebClient.getPaymentMethodsPSPs(TEST_ID, TEST_AMOUNT, TEST_LANG))
                .thenReturn(Mono.just(testResponseDto));

        /**
         * test
         */
        PSPsResponseDto pspResponseDto = ecommercePaymentInstrumentsClient.getPSPs(TEST_AMOUNT, TEST_LANG, TEST_ID).block();

        /**
         * asserts
         */
        assertThat(testResponseDto.getPsp()).isEqualTo(pspResponseDto.getPsp());
    }

    @Test
    void shouldReturnPaymentMethod() {
        String TEST_ID = UUID.randomUUID().toString();

        PaymentMethodResponseDto testPaymentMethodResponseDto = new PaymentMethodResponseDto();
        testPaymentMethodResponseDto
                .description("")
                .addRangesItem( new RangeDto().max(100L).min(0L))
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
        PaymentMethodResponseDto paymentMethodResponseDto = ecommercePaymentInstrumentsClient.getPaymentMethod(TEST_ID).block();

        /**
         * asserts
         */
        assertThat(testPaymentMethodResponseDto.getId()).isEqualTo(paymentMethodResponseDto.getId());
    }



    }
