package com.checkSheet.service.Notification;

import com.checkSheet.entity.User;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.checkSheet.DTO.response.ResponseDTO;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

@Service
public class FCMInitializerServiceImpl {

    @Autowired
    private Environment env;

    @Value("${is_notification_send:false}")
    private Boolean isNotificationSend = false;

    /*public void initialize() throws IOException {
        // Load the JSON file from the resources directory
//        InputStream serviceAccount = new ClassPathResource(env.getProperty("fcm.notification_json_file")).getInputStream();
        FileInputStream serviceAccount =
                new FileInputStream(env.getProperty("fcm.notification_json_file"));

        FirebaseOptions options = new FirebaseOptions.Builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build();

        FirebaseApp.initializeApp(options);
    }*/
    private static final Object lock = new Object();
    private static boolean initialized = false;

    public void initialize() throws IOException {
        synchronized (lock) {
            if (!initialized) {
                FileInputStream serviceAccount = new FileInputStream(env.getProperty("fcm.notification_json_file"));
                FirebaseOptions options = new FirebaseOptions.Builder()
                        .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                        .build();
                FirebaseApp.initializeApp(options);
                initialized = true;
            }
        }
    }


    //    @Override
    @Async("threadPoolTaskExecutorForNotification")
    @Transactional
    public ResponseDTO<String> sendPushNotification(String token, String title, String message, String additionalData, User user,
        String imagePath, Long enquiryMasterId, Long bulletinId, Long trainingToolId, Long salesToolsId) {

        /*if(isNotificationSend) {
            try {
                // Ensure FirebaseApp is initialized
                if (!initialized) {
                    initialize();
                }
                Notification notification = null;
                JSONObject jsonObject = new JSONObject();
                if(!Objects.equals(imagePath, null) && !Objects.equals(imagePath, "")) {
                    String docs = awss3Service.getDocs(imagePath).toString();
                    notification = Notification.builder()
                            .setTitle(title)
                            .setBody(message)
                            .setImage(docs)
                            .build();
//                    jsonObject.put("imagePath", imagePath);
                    if(notificationType.equals(NotificationType.GENERAL)) {
                        jsonObject.put("filePath", imagePath);
                    }
                } else {
                    notification = Notification.builder()
                            .setTitle(title)
                            .setBody(message)
                            .build();
                }
                if(Objects.equals(additionalData, null)) {
                    additionalData = "";
                }
                String params = "";
                if((notificationType.equals(NotificationType.TAT) || notificationType.equals(NotificationType.LOST) || notificationType.equals(NotificationType.LOST_APPROVAL) ||
                    notificationType.equals(NotificationType.LOST_DECLINE) || notificationType.equals(NotificationType.ASSIGN_ENQUIRY_TYPE) || notificationType.equals(NotificationType.UNASSIGNED) ||
                    notificationType.equals(NotificationType.PERSONAL_FOLLOWUP) || notificationType.equals(NotificationType.PENDING_LOST_APPROVAL) || notificationType.equals(NotificationType.FOLLOWUP))
                    && !Objects.equals(enquiryMasterId, null)) {
                    JSONObject tempJsonObject = new JSONObject();
                    tempJsonObject.put("id", enquiryMasterId);
                    params = tempJsonObject.toString();
                } else if((notificationType.equals(NotificationType.BULLETIN))
                        && !Objects.equals(bulletinId, null)) {
                    JSONObject tempJsonObject = new JSONObject();
                    tempJsonObject.put("id", bulletinId);
                    params = tempJsonObject.toString();
                } else if((notificationType.equals(NotificationType.SALES))
                        && !Objects.equals(salesToolsId, null)) {
                    JSONObject tempJsonObject = new JSONObject();
                    tempJsonObject.put("id", salesToolsId);
                    Optional<SalesTools> salesToolsOptional = salesToolsRepository.findById(salesToolsId);
                    if(salesToolsOptional.isPresent()) {
                        if(!Objects.equals(salesToolsOptional.get().getType(), null) && !Objects.equals(salesToolsOptional.get().getType(), "")) {
                            tempJsonObject.put("screenType", salesToolsOptional.get().getType());
                            if(Objects.equals(salesToolsOptional.get().getType().toLowerCase(), "gallery")) {
                                if(!Objects.equals(imagePath, null) && !Objects.equals(imagePath, "")) {
                                    jsonObject.put("filePath", imagePath);
                                }
                            }
                        }
                    }
                    params = tempJsonObject.toString();
                } else if((notificationType.equals(NotificationType.TRAINING))
                        && !Objects.equals(trainingToolId, null)) {
                    JSONObject tempJsonObject = new JSONObject();
                    tempJsonObject.put("id", trainingToolId);
                    params = tempJsonObject.toString();
                }
                jsonObject.put("title", title);
                jsonObject.put("message", message);
                jsonObject.put("data", additionalData);
                if(!Objects.equals(params, null) && !Objects.equals(params, "")) {
                    jsonObject.put("params", params);
                }

                userNotification.setFcmRequest(jsonObject.toString());
                userNotification.setIsViewed(false);
                userNotification.setNotificationType(notificationType);
                userNotification.setNotificationId(notifications);
                userNotification.setUserId(user);
                userNotificationRepository.save(userNotification);
                // Create a FCM message with the notification
                Message fcmMessage = Message.builder()
                        .setToken(token)
                        .setNotification(notification)
                        .putData("data", additionalData)
                        .putData("params", params)
                        .putData("notificationType", notificationType.toString())
                        .putData("notificationId", userNotification.getId().toString())
                        .build();
//            initialize();
                String response = FirebaseMessaging.getInstance().send(fcmMessage);

                userNotification.setFcmResponse(response);
                userNotificationRepository.save(userNotification);
                return new ResponseDTO<>(true, response);
            } catch(Exception e) {
                e.printStackTrace();
                if(!Objects.equals(userNotification.getId(), null)) {
                    userNotificationRepository.delete(userNotification);
                }
                return new ResponseDTO<>(false, e.getMessage());
            }

        }*/
        return new ResponseDTO<>(false, "Notification send currently stop");
    }
}
