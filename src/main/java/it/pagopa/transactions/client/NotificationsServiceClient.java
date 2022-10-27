package it.pagopa.transactions.client;

import it.pagopa.generated.notifications.templates.ko.KoTemplate;
import it.pagopa.generated.notifications.templates.success.SuccessTemplate;
import it.pagopa.generated.notifications.v1.api.DefaultApi;
import it.pagopa.generated.notifications.v1.dto.NotificationEmailRequestDto;
import it.pagopa.generated.notifications.v1.dto.NotificationEmailResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class NotificationsServiceClient {
    @Value("${notificationsService.apiKey}")
    private String notificationsServiceApiKey;

    @Autowired
    private DefaultApi notificationsServiceApi;

    public Mono<NotificationEmailResponseDto> sendNotificationEmail(NotificationEmailRequestDto notificationEmailRequestDto) {
        return notificationsServiceApi.sendNotificationEmail(notificationsServiceApiKey, notificationEmailRequestDto)
                .doOnError(WebClientResponseException.class, e -> log.info("Got bad response from notifications-service [HTTP {}]: {}", e.getStatusCode(), e.getResponseBodyAsString()))
                .doOnError(e -> log.info(e.toString()));
    }

    public Mono<NotificationEmailResponseDto> sendSuccessEmail(SuccessTemplateRequest successTemplateRequest) {
        return sendNotificationEmail(new NotificationEmailRequestDto()
                .language(successTemplateRequest.language)
                .subject(successTemplateRequest.subject)
                .to(successTemplateRequest.to)
                .templateId(SuccessTemplateRequest.TEMPLATE_ID)
                .parameters(successTemplateRequest.templateParameters)
        );
    }

    public Mono<NotificationEmailResponseDto> sendKoEmail(KoTemplateRequest koTemplateRequest) {
        return sendNotificationEmail(new NotificationEmailRequestDto()
                .language(koTemplateRequest.language)
                .subject(koTemplateRequest.subject)
                .to(koTemplateRequest.to)
                .templateId(KoTemplateRequest.TEMPLATE_ID)
                .parameters(koTemplateRequest.templateParameters)
        );
    }

    public record SuccessTemplateRequest(
            String to,
            String subject,
            String language,
            SuccessTemplate templateParameters
    ) {
        public static final String TEMPLATE_ID = "success";
    }

    public record KoTemplateRequest(
            String to,
            String subject,
            String language,
            KoTemplate templateParameters
    ) {
        public static final String TEMPLATE_ID = "ko";
    }
}
