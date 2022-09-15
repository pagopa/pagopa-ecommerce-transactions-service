package it.pagopa.transactions.client;

import it.pagopa.generated.notifications.v1.api.DefaultApi;
import it.pagopa.generated.notifications.v1.dto.NotificationEmailRequestDto;
import it.pagopa.generated.notifications.v1.dto.NotificationEmailResponseDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class NotificationsServiceClient {
    @Value("${notificationsService.apiKey}")
    private String notificationsServiceApiKey;

    @Autowired
    private DefaultApi notificationsServiceApi;

    public Mono<NotificationEmailResponseDto> sendNotificationEmail(NotificationEmailRequestDto notificationEmailRequestDto) {
        if (!(notificationEmailRequestDto.getParameters() instanceof Map)) {
            throw new IllegalArgumentException("Notifications service `parameters` field in `sendNotificationsEmail` request body must implement `java.util.Map`");
        }

        return notificationsServiceApi.sendNotificationEmail(notificationsServiceApiKey, notificationEmailRequestDto);
    }
}
