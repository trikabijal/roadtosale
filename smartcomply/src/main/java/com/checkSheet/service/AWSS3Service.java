package com.checkSheet.service;

import com.checkSheet.exception.CustomException;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.net.URL;

public interface AWSS3Service {
    void uploadFile(File file, String filename);

    void uploadMultipartFile(MultipartFile file, String filename) throws CustomException;

    URL getFileUrl(String key);

    void copyQuestionDocs(String sourceKey , String destinationKey);


    void deleteFile(String key) throws CustomException;

    URL getDocs(String imagePath);

    byte[] downloadFileFromS3(String objectKey) throws IOException;

    String downloadFileFromS3InBase64(String objectKey) throws IOException;

    void copyFile(String sourceKey, String destinationKey) throws CustomException;
}
