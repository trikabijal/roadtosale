package com.checkSheet.service.Email;

import com.checkSheet.DTO.AttachmentFileDTO;
import com.checkSheet.entity.User;
import com.checkSheet.service.AWSS3Service;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class EmailService {
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_RETRY_DELAY = 3000; // 3 second
    @Autowired
    private JavaMailSender emailSender;

    @Autowired
    private AWSS3Service awss3Service;

    @Value("${is_email_send:false}")
    private Boolean isEmailSend = false;

    @Value("${spring.mail.username}")
    private String mailUsername;

    @Value("${audit.mail.cc.user}")
    private String cc;
    @Value("${audit.mail.bcc.user}")
    private String bcc;

    @Autowired
    private Environment env;

    @Async("threadPoolTaskExecutorForEmail")
    public void sendEmail(String to, String subject, String text, byte[] bytes, String objectKey, User user) {
        if (!isEmailSend) {
            return;
        }

        int retryCount = 0;

        while (retryCount < MAX_RETRIES) {
            try {
                MimeMessage message = emailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true);
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(text, true);

                if(cc!=null && !cc.isEmpty()) {
                    String[] multiCc = cc.split(",");
                    helper.setCc(multiCc);
                }

                if(bcc!=null && !bcc.isEmpty()) helper.setBcc(bcc);
                helper.setFrom(env.getProperty("spring.mail.username"));
                emailSender.send(message);
                System.out.println("Email sent successfully");
                return; // Success - exit the retry loop

            } catch (Exception e) {
                System.out.println("Email sent failed....");
                retryCount++;
                if (retryCount == MAX_RETRIES) {
                    // Log final failure and save error
                    e.printStackTrace();
                    return;
                }

                // Wait before retrying
                try {
                    Thread.sleep(INITIAL_RETRY_DELAY * (long) Math.pow(2, retryCount - 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @Async("threadPoolTaskExecutorForEmailWithAttachment")
    public void sendEmailWithAttachement(String to, String subject, String text, List<AttachmentFileDTO> files) {
        if (!isEmailSend) {
            return;
        }

        int retryCount = 0;

        while (retryCount < MAX_RETRIES) {
            try {
                MimeMessage message = emailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true);

                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(text,true);
                helper.setFrom(env.getProperty("spring.mail.username"));
                if(!Objects.equals(files, null) && !files.isEmpty()) {
                    for(AttachmentFileDTO fileDTO : files) {
                        helper.addAttachment(fileDTO.getImageName(), new ByteArrayResource(fileDTO.getImage()));
                    }
                }
                emailSender.send(message);
                System.out.println("Email sent successfully");
                return; // Success - exit the retry loop

            } catch (Exception e) {
                System.out.println("Email sent failed....");
                retryCount++;
                if (retryCount == MAX_RETRIES) {
                    // Log final failure and save error
                    e.printStackTrace();
                    return;
                }

                // Wait before retrying
                try {
                    Thread.sleep(INITIAL_RETRY_DELAY * (long) Math.pow(2, retryCount - 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    @Async
    public void sendTextMail(String to, String subject, String body, String cc, String bcc) throws MessagingException {
        if(isEmailSend) {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailUsername);
            message.setTo(to);
            message.setText(body);
            message.setSubject(subject);
            if (cc != null) {
                String[] multiCc = cc.split(",");
                message.setCc(multiCc);
            }
            if (bcc != null) message.setBcc(bcc);
            emailSender.send(message);
        }
    }
}
