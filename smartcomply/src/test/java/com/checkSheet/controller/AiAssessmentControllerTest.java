package com.checkSheet.controller;

import com.checkSheet.DTO.AiAssessmentDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.AiAssessmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAssessmentControllerTest {

    @Mock
    private AiAssessmentService aiAssessmentService;

    @InjectMocks
    private AiAssessmentController aiAssessmentController;

    @Test
    void assessPhoto_shouldReturn200OnSuccess() throws CustomException {
        MockMultipartFile photo = new MockMultipartFile(
                "photo", "test.jpg", "image/jpeg", "data".getBytes());

        AiAssessmentDTO dto = new AiAssessmentDTO(
                1L, 100L, 15L, "OK", "No damage", 0.95,
                "http://localhost/photo.jpg", new Date());
        ResponseDTO<?> serviceResponse = new ResponseDTO<>("AI assessment completed", dto);

        doReturn(serviceResponse).when(aiAssessmentService).assessPhoto(any(), eq(100L), eq(15L));

        ResponseEntity<?> response = aiAssessmentController.assessPhoto(photo, 100L, 15L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void assessPhoto_shouldReturnErrorOnCustomException() throws CustomException {
        MockMultipartFile photo = new MockMultipartFile(
                "photo", "test.jpg", "image/jpeg", "data".getBytes());

        when(aiAssessmentService.assessPhoto(any(), eq(100L), eq(15L)))
                .thenThrow(new CustomException("Checkpoint not found", HttpStatus.NOT_FOUND));

        ResponseEntity<?> response = aiAssessmentController.assessPhoto(photo, 100L, 15L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        ResponseDTO<?> body = (ResponseDTO<?>) response.getBody();
        assertNotNull(body);
        assertFalse(body.getStatus());
        assertEquals("Checkpoint not found", body.getMessage());
    }

    @Test
    void getAssessment_shouldReturn200OnSuccess() throws CustomException {
        AiAssessmentDTO dto = new AiAssessmentDTO(
                1L, 100L, 15L, "OK", "No damage", 0.95,
                "http://localhost/photo.jpg", new Date());
        ResponseDTO<?> serviceResponse = new ResponseDTO<>("AI assessment retrieved", dto);

        doReturn(serviceResponse).when(aiAssessmentService).getAssessment(100L, 15L);

        ResponseEntity<?> response = aiAssessmentController.getAssessment(100L, 15L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getAssessment_shouldReturnErrorWhenNotAuthenticated() throws CustomException {
        when(aiAssessmentService.getAssessment(100L, 15L))
                .thenThrow(new CustomException("User not authenticated", HttpStatus.UNAUTHORIZED));

        ResponseEntity<?> response = aiAssessmentController.getAssessment(100L, 15L);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void getAssessmentsByChecksheet_shouldReturn200OnSuccess() throws CustomException {
        ResponseDTO<?> serviceResponse = new ResponseDTO<>("AI assessments retrieved", java.util.List.of(), 0L);

        doReturn(serviceResponse).when(aiAssessmentService).getAssessmentsByChecksheet(100L);

        ResponseEntity<?> response = aiAssessmentController.getAssessmentsByChecksheet(100L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void getAssessmentsByChecksheet_shouldReturnErrorOnFailure() throws CustomException {
        when(aiAssessmentService.getAssessmentsByChecksheet(100L))
                .thenThrow(new CustomException("User not authenticated", HttpStatus.UNAUTHORIZED));

        ResponseEntity<?> response = aiAssessmentController.getAssessmentsByChecksheet(100L);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
