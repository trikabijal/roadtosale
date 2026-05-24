package com.checkSheet.service.Notification;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;

@Component
public class FirebaseInitializer {

    @Autowired
    private FCMInitializerServiceImpl fcmInitializerServiceImpl;

//    @PostConstruct
//    public void initializeFirebase() {
//        try {
//            fcmInitializerServiceImpl.initialize(); // Initialize Firebase
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }
}

