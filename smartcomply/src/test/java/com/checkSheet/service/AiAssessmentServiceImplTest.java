package com.checkSheet.service;

import com.checkSheet.DTO.AiAssessmentDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.entity.*;
import com.checkSheet.exception.CustomException;
import com.trika.llm.LlmProvider;
import com.trika.llm.LlmClient;
import com.trika.llm.LlmResponse;
import com.checkSheet.repository.AiAssessmentRepository;
import com.checkSheet.repository.ChksQuestionResultOptionRepository;
import com.checkSheet.repository.ChksQuestionResultRepository;
import com.checkSheet.repository.UserChecksheetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiAssessmentServiceImplTest {

    @Mock
    private AiAssessmentRepository aiAssessmentRepository;

    @Mock
    private UserChecksheetRepository userChecksheetRepository;

    @Mock
    private ChksQuestionResultRepository chksQuestionResultRepository;

    @Mock
    private ChksQuestionResultOptionRepository chksQuestionResultOptionRepository;

    @Mock
    private LlmClient llmClient;

    @Mock
    private UtilityService utilityService;

    @InjectMocks
    private AiAssessmentServiceImpl aiAssessmentService;

    private User testUser;
    private Inspection testUserChecksheet;
    private ChksQuestionResult testQuestionResult;
    private ChksQuestion testQuestion;
    private List<ChksQuestionResultOption> testOptions;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);

        Checksheet testChecksheet = new Checksheet();
        testChecksheet.setId(1L);

        testUserChecksheet = new Inspection();
        testUserChecksheet.setId(100L);
        testUserChecksheet.setOperatorUser(testUser);
        testUserChecksheet.setChecksheet(testChecksheet);

        testQuestion = new ChksQuestion();
        testQuestion.setId(10L);
        testQuestion.setName("Front ACP + Logo — Damage");
        testQuestion.setDescription("Check for visible damage on front ACP panel and logo");

        testQuestionResult = new ChksQuestionResult();
        testQuestionResult.setId(15L);
        testQuestionResult.setChksQuestion(testQuestion);
        testQuestionResult.setChecksheet(testChecksheet);

        ChksQuestionResultOption okOption = new ChksQuestionResultOption();
        okOption.setId(1L);
        okOption.setOption("No visible damage on ACP or logo");
        okOption.setJudgement("OK");

        ChksQuestionResultOption notOkOption = new ChksQuestionResultOption();
        notOkOption.setId(2L);
        notOkOption.setOption("Visible dents, scratches, or panel damage");
        notOkOption.setJudgement("NOT OK");

        testOptions = List.of(okOption, notOkOption);
    }

    @Test
    void assessPhoto_shouldReturnAssessmentWhenValid() throws Exception {
        MockMultipartFile photo = new MockMultipartFile(
                "photo", "test.jpg", "image/jpeg", "fake-image-data".getBytes());

        when(utilityService.getCurrentLoggedInUser()).thenReturn(Optional.of(testUser));
        when(userChecksheetRepository.findById(100L)).thenReturn(Optional.of(testUserChecksheet));
        when(chksQuestionResultRepository.findById(15L)).thenReturn(Optional.of(testQuestionResult));
        when(chksQuestionResultOptionRepository.findByChksQuestionResult_Id(15L)).thenReturn(testOptions);

        // Provider returns raw text that the service will parse
        String aiText = "{\"judgement\": \"NOT_OK\", \"explanation\": \"Dent visible on upper panel\", \"confidence\": 0.87}";
        when(llmClient.assessImage(anyString(), eq("image/jpeg"), anyString(), anyString()))
                .thenReturn(new LlmResponse(
                        aiText, "raw-api-response", "claude-sonnet-4-20250514",
                        LlmProvider.ANTHROPIC, 500, 50, 0, 1200L));

        AiAssessment savedAssessment = new AiAssessment();
        savedAssessment.setId(42L);
        savedAssessment.setInspection(testUserChecksheet);
        savedAssessment.setChksQuestionResult(testQuestionResult);
        savedAssessment.setPhotoPath("ai-assessments/test.jpg");
        savedAssessment.setSuggestedJudgement("NOT_OK");
        savedAssessment.setExplanation("Dent visible on upper panel");
        savedAssessment.setConfidence(0.87);
        savedAssessment.setAssessedAt(new Date());

        when(aiAssessmentRepository.save(any(AiAssessment.class))).thenReturn(savedAssessment);

        try (MockedStatic<com.checkSheet.helper.FileStorageUtil> fileStorageMock =
                     mockStatic(com.checkSheet.helper.FileStorageUtil.class)) {
            fileStorageMock.when(() -> com.checkSheet.helper.FileStorageUtil.storeFile(
                    any(MultipartFile.class), anyString(), anyString())).thenReturn("ai-assessments/test.jpg");
            fileStorageMock.when(() -> com.checkSheet.helper.FileStorageUtil.getFileURL(anyString()))
                    .thenReturn("http://localhost/api/checksheet/doc/ai-assessments/test.jpg");

            ResponseDTO<?> response = aiAssessmentService.assessPhoto(photo, 100L, 15L);

            assertTrue(response.getStatus());
            assertEquals("AI assessment completed", response.getMessage());
            assertNotNull(response.getData());
            AiAssessmentDTO dto = (AiAssessmentDTO) response.getData();
            assertEquals("NOT_OK", dto.getSuggestedJudgement());
            assertEquals("Dent visible on upper panel", dto.getExplanation());
            assertEquals(0.87, dto.getConfidence(), 0.01);
        }
    }

    @Test
    void assessPhoto_shouldThrowWhenUserNotAuthenticated() throws CustomException {
        MockMultipartFile photo = new MockMultipartFile(
                "photo", "test.jpg", "image/jpeg", "data".getBytes());

        when(utilityService.getCurrentLoggedInUser()).thenReturn(Optional.empty());

        assertThrows(CustomException.class,
                () -> aiAssessmentService.assessPhoto(photo, 100L, 15L));
    }

    @Test
    void assessPhoto_shouldThrowWhenPhotoIsNull() throws CustomException {
        when(utilityService.getCurrentLoggedInUser()).thenReturn(Optional.of(testUser));

        assertThrows(CustomException.class,
                () -> aiAssessmentService.assessPhoto(null, 100L, 15L));
    }

    @Test
    void assessPhoto_shouldThrowWhenPhotoIsEmpty() throws CustomException {
        MockMultipartFile emptyPhoto = new MockMultipartFile(
                "photo", "empty.jpg", "image/jpeg", new byte[0]);

        when(utilityService.getCurrentLoggedInUser()).thenReturn(Optional.of(testUser));

        assertThrows(CustomException.class,
                () -> aiAssessmentService.assessPhoto(emptyPhoto, 100L, 15L));
    }

    @Test
    void assessPhoto_shouldThrowWhenUnsupportedMediaType() throws CustomException {
        MockMultipartFile photo = new MockMultipartFile(
                "photo", "test.bmp", "image/bmp", "data".getBytes());

        when(utilityService.getCurrentLoggedInUser()).thenReturn(Optional.of(testUser));

        assertThrows(CustomException.class,
                () -> aiAssessmentService.assessPhoto(photo, 100L, 15L));
    }

    @Test
    void assessPhoto_shouldThrowWhenChecksheetNotFound() throws CustomException {
        MockMultipartFile photo = new MockMultipartFile(
                "photo", "test.jpg", "image/jpeg", "data".getBytes());

        when(utilityService.getCurrentLoggedInUser()).thenReturn(Optional.of(testUser));
        when(userChecksheetRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(CustomException.class,
                () -> aiAssessmentService.assessPhoto(photo, 999L, 15L));
    }

    @Test
    void assessPhoto_shouldThrowWhenCheckpointNotFound() throws CustomException {
        MockMultipartFile photo = new MockMultipartFile(
                "photo", "test.jpg", "image/jpeg", "data".getBytes());

        when(utilityService.getCurrentLoggedInUser()).thenReturn(Optional.of(testUser));
        when(userChecksheetRepository.findById(100L)).thenReturn(Optional.of(testUserChecksheet));
        when(chksQuestionResultRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(CustomException.class,
                () -> aiAssessmentService.assessPhoto(photo, 100L, 999L));
    }

    // --- Response parsing tests (moved from AiVisionClientTest) ---

    @Test
    void parseAiResponse_shouldParseValidJson() throws CustomException {
        String text = "{\"judgement\": \"OK\", \"explanation\": \"No damage\", \"confidence\": 0.95}";
        var parsed = aiAssessmentService.parseAiResponse(text);
        assertEquals("OK", parsed.judgement());
        assertEquals("No damage", parsed.explanation());
        assertEquals(0.95, parsed.confidence(), 0.01);
    }

    @Test
    void parseAiResponse_shouldHandleMarkdownCodeBlock() throws CustomException {
        String text = "```json\n{\"judgement\": \"NOT_OK\", \"explanation\": \"Dent\", \"confidence\": 0.8}\n```";
        var parsed = aiAssessmentService.parseAiResponse(text);
        assertEquals("NOT_OK", parsed.judgement());
    }

    @Test
    void parseAiResponse_shouldDefaultInvalidJudgementToNotOk() throws CustomException {
        String text = "{\"judgement\": \"MAYBE\", \"explanation\": \"Unclear\", \"confidence\": 0.3}";
        var parsed = aiAssessmentService.parseAiResponse(text);
        assertEquals("NOT_OK", parsed.judgement());
    }

    @Test
    void parseAiResponse_shouldClampConfidence() throws CustomException {
        String text = "{\"judgement\": \"OK\", \"explanation\": \"Fine\", \"confidence\": 1.5}";
        var parsed = aiAssessmentService.parseAiResponse(text);
        assertEquals(1.0, parsed.confidence(), 0.01);
    }

    @Test
    void parseAiResponse_shouldDefaultMissingConfidence() throws CustomException {
        String text = "{\"judgement\": \"OK\", \"explanation\": \"Fine\"}";
        var parsed = aiAssessmentService.parseAiResponse(text);
        assertEquals(0.5, parsed.confidence(), 0.01);
    }

    @Test
    void parseAiResponse_shouldThrowOnInvalidText() {
        assertThrows(CustomException.class,
                () -> aiAssessmentService.parseAiResponse("This is not JSON"));
    }

    @Test
    void buildUserPrompt_shouldBeCompact() {
        String prompt = aiAssessmentService.buildUserPrompt(testQuestion, testOptions);
        assertTrue(prompt.contains("Front ACP + Logo — Damage"));
        assertTrue(prompt.contains("OK: No visible damage on ACP or logo"));
        assertTrue(prompt.contains("NOT_OK: Visible dents, scratches, or panel damage"));
        assertFalse(prompt.contains("You are an expert")); // system prompt content should NOT be here
    }

    // --- Retrieval tests ---

    @Test
    void getAssessment_shouldReturnExistingAssessment() throws CustomException {
        when(utilityService.getCurrentLoggedInUser()).thenReturn(Optional.of(testUser));
        when(userChecksheetRepository.findById(100L)).thenReturn(Optional.of(testUserChecksheet));

        AiAssessment assessment = new AiAssessment();
        assessment.setId(42L);
        assessment.setInspection(testUserChecksheet);
        assessment.setChksQuestionResult(testQuestionResult);
        assessment.setPhotoPath("ai-assessments/test.jpg");
        assessment.setSuggestedJudgement("OK");
        assessment.setExplanation("No damage visible");
        assessment.setConfidence(0.95);
        assessment.setAssessedAt(new Date());

        when(aiAssessmentRepository.findFirstByInspectionIdAndChksQuestionResultIdAndDeletedAtIsNullOrderByAssessedAtDesc(100L, 15L))
                .thenReturn(Optional.of(assessment));

        try (MockedStatic<com.checkSheet.helper.FileStorageUtil> fileStorageMock =
                     mockStatic(com.checkSheet.helper.FileStorageUtil.class)) {
            fileStorageMock.when(() -> com.checkSheet.helper.FileStorageUtil.getFileURL(anyString()))
                    .thenReturn("http://localhost/api/checksheet/doc/ai-assessments/test.jpg");

            ResponseDTO<?> response = aiAssessmentService.getAssessment(100L, 15L);

            assertNotNull(response.getData());
            AiAssessmentDTO dto = (AiAssessmentDTO) response.getData();
            assertEquals("OK", dto.getSuggestedJudgement());
            assertEquals(42L, dto.getId());
        }
    }

    @Test
    void getAssessment_shouldReturnNotFoundWhenNoAssessment() throws CustomException {
        when(utilityService.getCurrentLoggedInUser()).thenReturn(Optional.of(testUser));
        when(userChecksheetRepository.findById(100L)).thenReturn(Optional.of(testUserChecksheet));
        when(aiAssessmentRepository.findFirstByInspectionIdAndChksQuestionResultIdAndDeletedAtIsNullOrderByAssessedAtDesc(100L, 15L))
                .thenReturn(Optional.empty());

        ResponseDTO<?> response = aiAssessmentService.getAssessment(100L, 15L);

        assertEquals("No AI assessment found for this checkpoint", response.getMessage());
    }

    @Test
    void getAssessment_shouldThrowWhenNotAuthenticated() throws CustomException {
        when(utilityService.getCurrentLoggedInUser()).thenReturn(Optional.empty());

        assertThrows(CustomException.class,
                () -> aiAssessmentService.getAssessment(100L, 15L));
    }
}
