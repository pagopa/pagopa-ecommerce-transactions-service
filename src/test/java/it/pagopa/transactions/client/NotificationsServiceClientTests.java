package it.pagopa.transactions.client;

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

import java.util.Map;

@ExtendWith(MockitoExtension.class)
public class NotificationsServiceClientTests {
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
    void shouldThrowOnNonMapParameters() {
        NotificationEmailRequestDto notificationEmailRequest = new NotificationEmailRequestDto()
                .language("it-IT")
                .subject("subject")
                .to("foo@example.com")
                .templateId("template-id")
                .parameters(null);

        StepVerifier.create(client.sendNotificationEmail(notificationEmailRequest))
                .expectError(IllegalArgumentException.class)
                .verify();
    }
}
