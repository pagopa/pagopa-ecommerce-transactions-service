package it.pagopa.transactions.client;

import it.pagopa.generated.notifications.templates.success.*;
import it.pagopa.generated.notifications.v1.api.DefaultApi;
import it.pagopa.generated.notifications.v1.dto.NotificationEmailRequestDto;
import it.pagopa.generated.notifications.v1.dto.NotificationEmailResponseDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
public class NotificationsServiceClientTest {
    @InjectMocks
    private NotificationsServiceClient client;

    @Mock
    private DefaultApi defaultApi;

    @Test
    void shouldReturnEmailOutcome() {
        NotificationEmailRequestDto notificationEmailRequest = new NotificationEmailRequestDto()
                .language("it-IT")
                .subject("subject")
                .to("foo@example.com")
                .templateId("template-id")
                .parameters(Map.of("param1", "value1"));

        NotificationEmailResponseDto expected = new NotificationEmailResponseDto()
                .outcome("OK");

        Mockito.when(defaultApi.sendNotificationEmail(null, notificationEmailRequest)).thenReturn(Mono.just(expected));

        StepVerifier.create(client.sendNotificationEmail(notificationEmailRequest))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void shouldReturnEmailOutcomeWithSuccessTemplate() {
        NotificationsServiceClient.SuccessTemplateRequest successTemplateRequest = new NotificationsServiceClient.SuccessTemplateRequest(
                "foo@example.com",
                "Hai pagato un avviso di pagamento PagoPA",
                "it-IT",
                new SuccessTemplate(
                        new TransactionTemplate(
                                "transactionId",
                                ZonedDateTime.now().toString(),
                                "€ 0.00",
                                new PspTemplate(
                                        "pspId",
                                        new FeeTemplate("€ 0.00")
                                ),
                                "RRN",
                                "authorizationCode",
                                new PaymentMethodTemplate(
                                        "paymentInstrumentId",
                                        "paymentMethodLogo",
                                        null,
                                        false
                                )
                        ),
                        new UserTemplate(
                                new DataTemplate(
                                        null,
                                        null,
                                        null
                                ),
                                "foo@example.com"
                        ),
                        new CartTemplate(
                                List.of(
                                        new ItemTemplate(
                                                new RefNumberTemplate(
                                                        RefNumberTemplate.Type.CODICE_AVVISO,
                                                        "rptId"
                                                ),
                                                new DebtorTemplate(
                                                        null,
                                                        null
                                                ),
                                                new PayeeTemplate(
                                                        null,
                                                        null
                                                ),
                                                "description",
                                                "€ 0.00"
                                        )
                                ),
                                "€ 0.00"
                        )
                )
        );

        NotificationEmailRequestDto request = new NotificationEmailRequestDto()
                .language(successTemplateRequest.language())
                .subject(successTemplateRequest.subject())
                .to(successTemplateRequest.to())
                .templateId(NotificationsServiceClient.SuccessTemplateRequest.TEMPLATE_ID)
                .parameters(successTemplateRequest.templateParameters());

        NotificationEmailResponseDto expected = new NotificationEmailResponseDto()
                .outcome("OK");

        Mockito.when(defaultApi.sendNotificationEmail(null, request)).thenReturn(Mono.just(expected));

        StepVerifier.create(client.sendNotificationEmail(request))
                .expectNext(expected)
                .verifyComplete();
    }
}
