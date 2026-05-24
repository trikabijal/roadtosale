package com.checkSheet.service;


import com.amazonaws.AmazonServiceException;
import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.checkSheet.exception.CustomException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Date;

@Service
public class AWSS3ServiceImpl implements AWSS3Service {
    private static final Logger LOGGER = LoggerFactory.getLogger(AWSS3ServiceImpl.class);

    @Autowired
    private AmazonS3 amazonS3;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    @Override
    // @Async annotation ensures that the method is executed in a different background thread
    // but not consume the main thread.
    @Async
    public void uploadFile(final File file, final String filename) {
        try {
            uploadFileToS3Bucket(bucketName, file, filename);
            //file.delete();  // To remove the file locally created in the project folder.
        } catch (final AmazonServiceException ex) {
            ex.printStackTrace();
        }
    }

    private void uploadFileToS3Bucket(final String bucketName,final File file, String fileName) {
        final PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName, fileName, file);
        amazonS3.putObject(putObjectRequest);
    }

    @Override
    public URL getFileUrl(final String key) {
        return amazonS3.getUrl(bucketName, key);
    }

    @Override
    public void uploadMultipartFile(MultipartFile file, String filename) throws CustomException {
        try {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(file.getSize());

            PutObjectRequest putObjectRequest;
            try {
                putObjectRequest = new PutObjectRequest(bucketName, filename, file.getInputStream(),metadata);
            } catch (Exception e) {
                e.printStackTrace();
                throw new CustomException(e.getMessage(), new Exception());
            }
            amazonS3.putObject(putObjectRequest);
        } catch (final AmazonServiceException e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), new AmazonServiceException(filename));
        }
    }

    @Override
    public void deleteFile(String key) {
        try {
            amazonS3.deleteObject(new DeleteObjectRequest(bucketName, key));
        } catch (Exception e) {
            e.printStackTrace();
//            throw new CustomException(e.getMessage(), new AmazonServiceException(key));
        }
    }

    @Override
    public void copyQuestionDocs(String sourceKey , String destinationKey) {
        amazonS3.copyObject(bucketName, sourceKey, bucketName, destinationKey);
    }

    @Override
    public URL getDocs(String imagePath) {
        try {
            Date expiration = new Date(System.currentTimeMillis() + 3000000);

            GeneratePresignedUrlRequest generatePresignedUrlRequest = new GeneratePresignedUrlRequest(bucketName, imagePath)
                    .withMethod(HttpMethod.GET)
                    .withExpiration(expiration);

            return amazonS3.generatePresignedUrl(generatePresignedUrlRequest);
        } catch(Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public byte[] downloadFileFromS3(String objectKey) throws IOException {
        try {
            InputStream inputStream = amazonS3.getObject(bucketName, objectKey).getObjectContent();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, length);
            }
            return outputStream.toByteArray();
        } catch (IOException ie) {
            ie.printStackTrace();
           throw new IOException(ie);
        } catch (Exception e) {
            e.printStackTrace();
            throw new IOException(e.getMessage());
        }
    }

    @Override
    public String downloadFileFromS3InBase64(String objectKey) throws IOException {
        try {
            File file = new File(objectKey);
            byte[] fileContent = Files.readAllBytes(file.toPath());

            // Convert to Base64
            String base64Image = Base64.getEncoder().encodeToString(fileContent);
            return base64Image;
        } catch (IOException ie) {
            ie.printStackTrace();
            throw new IOException(ie);
        } catch (Exception e) {
            e.printStackTrace();
            throw new IOException(e.getMessage());
        }
    }

    @Override
    public void copyFile(String sourceKey, String destinationKey) throws CustomException {
        try {
            amazonS3.copyObject(bucketName, sourceKey, bucketName, destinationKey);
        } catch (AmazonServiceException e) {
            e.printStackTrace();
            throw new CustomException("Error copying file in S3: " + e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

}
