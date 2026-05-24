package com.checkSheet.service.Notification;

import com.checkSheet.DTO.response.ResponseDTO;
import org.springframework.scheduling.annotation.Async;

public interface FCMInitializerService {
    @Async("threadPoolTaskExecutorForNotification")
    ResponseDTO<String> sendPushNotification(String token, String title, String message, String additionalData);
}
