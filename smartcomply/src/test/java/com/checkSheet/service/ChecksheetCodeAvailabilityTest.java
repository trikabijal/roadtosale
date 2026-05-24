package com.checkSheet.service;

import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.entity.Checksheet;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.ChecksheetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChecksheetCodeAvailabilityTest {

    @Mock
    private ChecksheetRepository checksheetRepository;

    @InjectMocks
    private ChecksheetServiceImpl checksheetService;

    @Test
    void checkCodeAvailability_codeNotTaken_returnsAvailableTrue() throws CustomException {
        when(checksheetRepository.existsByModelNoIgnoreCase("UNIQUE-CODE")).thenReturn(false);

        ResponseDTO<?> resp = checksheetService.checkCodeAvailability("UNIQUE-CODE", null);

        assertNotNull(resp);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getData();
        assertEquals(true, data.get("available"));
    }

    @Test
    void checkCodeAvailability_codeTaken_returnsAvailableFalse() throws CustomException {
        when(checksheetRepository.existsByModelNoIgnoreCase("TAKEN")).thenReturn(true);

        ResponseDTO<?> resp = checksheetService.checkCodeAvailability("TAKEN", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getData();
        assertEquals(false, data.get("available"));
    }

    @Test
    void checkCodeAvailability_withExcludeId_codeNotTakenByOther_returnsAvailableTrue() throws CustomException {
        when(checksheetRepository.existsByModelNoIgnoreCaseAndIdNot("CODE", 5L)).thenReturn(false);

        ResponseDTO<?> resp = checksheetService.checkCodeAvailability("CODE", 5L);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getData();
        assertEquals(true, data.get("available"));
    }

    @Test
    void checkCodeAvailability_withExcludeId_codeTakenByOther_returnsAvailableFalse() throws CustomException {
        when(checksheetRepository.existsByModelNoIgnoreCaseAndIdNot("CODE", 5L)).thenReturn(true);

        ResponseDTO<?> resp = checksheetService.checkCodeAvailability("CODE", 5L);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getData();
        assertEquals(false, data.get("available"));
    }

    @Test
    void checkCodeAvailability_blankCode_returnsAvailableTrue() throws CustomException {
        // Blank codes are optional — always available
        ResponseDTO<?> resp = checksheetService.checkCodeAvailability("", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getData();
        assertEquals(true, data.get("available"));
        verifyNoInteractions(checksheetRepository);
    }
}
