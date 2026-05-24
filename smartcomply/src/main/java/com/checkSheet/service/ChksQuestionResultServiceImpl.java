package com.checkSheet.service;

import java.util.*;

import com.checkSheet.helper.FileStorageUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkSheet.DAO.ChksGeneralFieldDAO;
import com.checkSheet.DAO.ChksHeaderDAO;
import com.checkSheet.DAO.ChksQuestionResultDAO;
import com.checkSheet.DAO.ChksQuestionResultMatrixDAO;
import com.checkSheet.DAO.ChksQuestionResultOptionDAO;
import com.checkSheet.DTO.ChksQuestionResultDTO;
import com.checkSheet.DTO.ChksQuestionResultMatrixDTO;
import com.checkSheet.DTO.ChksQuestionResultOptionDTO;
import com.checkSheet.DTO.BulkChksQuestionResultDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.constant.ChksQuestionResultObjectiveType;
import com.checkSheet.constant.ChksQuestionResultType;
import com.checkSheet.entity.Checksheet;
import com.checkSheet.entity.ChksHeader;
import com.checkSheet.entity.ChksQuestion;
import com.checkSheet.entity.ChksQuestionResult;
import com.checkSheet.entity.ChksQuestionResultMatrix;
import com.checkSheet.entity.ChksQuestionResultOption;
import com.checkSheet.entity.User;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.ChecksheetRepository;
import com.checkSheet.repository.ChksGeneralFieldRepository;
import com.checkSheet.repository.ChksHeaderRepository;
import com.checkSheet.repository.ChksQuestionFileRepository;
import com.checkSheet.repository.ChksQuestionRepository;
import com.checkSheet.repository.ChksQuestionResultMatrixRepository;
import com.checkSheet.repository.ChksQuestionResultOptionRepository;
import com.checkSheet.repository.ChksQuestionResultRepository;

@Service
public class ChksQuestionResultServiceImpl implements ChksQuestionResultService {
    @Autowired
    private ChksGeneralFieldRepository chksGeneralFieldRepository;

    @Autowired
    private ChecksheetRepository checksheetRepository;

    @Autowired
    private UtilityService utilityService;

    @Autowired
    private ChksGeneralFieldDAO chksGeneralFieldDAO;

    @Autowired
    private ChksHeaderRepository chksHeaderRepository;

    @Autowired
    private ChksHeaderDAO chksHeaderDAO;

    @Autowired
    private ChksQuestionRepository chksQuestionRepository;

    @Autowired
    private ChksQuestionFileRepository chksQuestionFileRepository;

    @Autowired
    private AWSS3Service awsS3Service;

    @Autowired
    private ChksQuestionResultRepository chksQuestionResultRepository;

    @Autowired
    private ChksQuestionResultOptionRepository chksQuestionResultOptionRepository;

    @Autowired
    private ChksQuestionResultMatrixRepository chksQuestionResultMatrixRepository;

    @Autowired
    private ChksQuestionResultDAO chksQuestionResultDAO;

    @Autowired
    private ChksQuestionResultOptionDAO chksQuestionResultOptionDAO;

    @Autowired
    private ChksQuestionResultMatrixDAO chksQuestionResultMatrixDAO;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> setQuestionResult(ChksQuestionResultDTO chksQuestionResultDTO) throws CustomException {
        try {
            // Validate required fields
            if (Objects.isNull(chksQuestionResultDTO) || 
                Objects.isNull(chksQuestionResultDTO.getChecksheetId()) ||
                Objects.isNull(chksQuestionResultDTO.getChksHeaderId()) ||
                Objects.isNull(chksQuestionResultDTO.getChksQuestionId()) ||
                Objects.isNull(chksQuestionResultDTO.getAnswerType())) {
                throw new CustomException("Please provide checksheetId, chksHeaderId, chksQuestionId and answerType", 
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }
            boolean isExist = false;
            ChksQuestionResult chksQuestionResult = new ChksQuestionResult();
            if(Objects.isNull(chksQuestionResultDTO.getId())) {
                /*Optional<ChksQuestionResult> chksQuestionResultIsExist = chksQuestionResultRepository.findByChksHeader_IdAndChksQuestion_IdAndChecksheet_Id(
                        chksQuestionResultDTO.getChksHeaderId(), chksQuestionResultDTO.getChksQuestionId(), chksQuestionResultDTO.getChecksheetId()
                );
                if(chksQuestionResultIsExist.isPresent()) {
                    throw new CustomException("Already checksheet question result, please provide id", HttpStatus.UNPROCESSABLE_ENTITY);
                }*/
                Optional<ChksQuestionResult> existingResult = chksQuestionResultRepository.findByChksHeader_IdAndChksQuestion_IdAndChecksheet_Id(
                        chksQuestionResultDTO.getChksHeaderId(),
                        chksQuestionResultDTO.getChksQuestionId(),
                        chksQuestionResultDTO.getChecksheetId()
                );

                if (existingResult.isPresent()) {
                    chksQuestionResult = existingResult.get();
                    chksQuestionResultOptionRepository.deleteByChksQuestionResult_Id(chksQuestionResult.getId());
                    isExist = true;
                }
                chksQuestionResultDTO.setId(chksQuestionResult.getId());
            } else {
                Optional<ChksQuestionResult> chksQuestionResultById = chksQuestionResultRepository.findById(chksQuestionResultDTO.getId());
                if(chksQuestionResultById.isEmpty()) {
                    throw new CustomException("Please provide valid id", HttpStatus.FORBIDDEN);
                }
                chksQuestionResult = chksQuestionResultById.get();
                chksQuestionResultOptionRepository.deleteByChksQuestionResult_Id(chksQuestionResultDTO.getId());
                isExist = true;
            }

            Optional<Checksheet> checksheet = checksheetRepository.findById(chksQuestionResultDTO.getChecksheetId());
            if(!checksheet.isPresent()) {
                throw new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();

            if(Objects.isNull(checksheet.get().getPreparerUser()) ||
                    !Objects.equals(checksheet.get().getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You have not access of this checksheet", HttpStatus.FORBIDDEN);
            }
//            if(!Objects.isNull(chksQuestionResultDTO.getId())) {
//            }
//            else if (Objects.isNull(chksQuestionResultDTO.getId())) {
//            }
            chksQuestionResult.setChecksheet(checksheetRepository.findById(chksQuestionResultDTO.getChecksheetId())
                .orElseThrow(() -> new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY)));
            Optional<ChksHeader> chkHeader = chksHeaderRepository.findById(chksQuestionResultDTO.getChksHeaderId());
            chksQuestionResult.setChksHeader(chkHeader
                .orElseThrow(() -> new CustomException("Invalid chksHeaderId", HttpStatus.UNPROCESSABLE_ENTITY)));
            Optional<ChksQuestion> chksQuestion = chksQuestionRepository.findById(chksQuestionResultDTO.getChksQuestionId());
            chksQuestionResult.setChksQuestion(chksQuestion
                .orElseThrow(() -> new CustomException("Invalid chksQuestionId", HttpStatus.UNPROCESSABLE_ENTITY)));
            chksQuestionResult.setAnswerType(chksQuestionResultDTO.getAnswerType());
            chksQuestionResult.setIsOptional(chksQuestionResultDTO.getIsOptional());
            chksQuestionResultRepository.save(chksQuestionResult);

            if(!Objects.equals(chksQuestion.get().getChecksheet().getId(), checksheet.get().getId()) ||
                    !Objects.equals(chkHeader.get().getChecksheet().getId(), checksheet.get().getId())) {
                throw new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            if(!Objects.isNull(chkHeader.get().getIsResultColumn()) && !chkHeader.get().getIsResultColumn()) {
                throw new CustomException("You can set result for only result columns", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Handle different answer types
            switch (chksQuestionResultDTO.getAnswerType()) {
                case OBJECTIVE:
                    validateAndSetObjectiveType(chksQuestionResultDTO, chksQuestionResult);
                    break;
                    
                case SUBJECTIVE, NA:
                    break;
                    
                case SUBJECTIVE_CONDITION, SELECTIVE:
                    if (Objects.isNull(chksQuestionResultDTO.getChksQuestionResultOptions()) || 
                        chksQuestionResultDTO.getChksQuestionResultOptions().isEmpty()) {
                        throw new CustomException("Please provide options for subjective condition", 
                            HttpStatus.UNPROCESSABLE_ENTITY);
                    }

                    // Validate each option has both option and judgement
                    for (ChksQuestionResultOptionDTO option : chksQuestionResultDTO.getChksQuestionResultOptions()) {
                        if (Objects.isNull(option.getOption()) || Objects.isNull(option.getJudgement())) {
                            try {
                                throw new CustomException("Each option must have both option and judgement",
                                        HttpStatus.UNPROCESSABLE_ENTITY);
                            } catch (CustomException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        ChksQuestionResultOption chksQuestionResultOption = new ChksQuestionResultOption();
                        chksQuestionResultOption.setOption(option.getOption());
                        chksQuestionResultOption.setJudgement(option.getJudgement());
                        chksQuestionResultOption.setChksQuestionResult(chksQuestionResult);
                        chksQuestionResultOption.setChecksheet(checksheet.get());
                        chksQuestionResultOption.setCreatedBy(currentUser.get());
                        chksQuestionResultOptionRepository.save(chksQuestionResultOption);
                    }
                    break;

                case MATRIX:
                    validateMatrixType(chksQuestionResultDTO, chksQuestionResult, currentUser);
                    chksQuestionResult.setMatrixName(chksQuestionResultDTO.getMatrixName());
                    chksQuestionResult.setNoOfResults(chksQuestionResultDTO.getNoOfResults());
                    chksQuestionResult.setNoOfRows(chksQuestionResultDTO.getNoOfRows());
                    chksQuestionResult.setNoOfColumns(chksQuestionResultDTO.getNoOfColumns());
                    chksQuestionResult.setChksMatrixColName(chksQuestionResultDTO.getChksMatrixColName());
                    chksQuestionResult.setChksMatrixRowName(chksQuestionResultDTO.getChksMatrixRowName());
                    break;
            }

            chksQuestionResult.setCreatedBy(currentUser.get());

            chksQuestionResultRepository.save(chksQuestionResult);

            if(Objects.equals(chksQuestionResultDTO.getAnswerType(), ChksQuestionResultType.MATRIX)) {
//                System.out.println("isExist : " + isExist);
//                System.out.println("chksQuestionResult.getMatrixFileLocation() : " + chksQuestionResult.getMatrixFileLocation());
                if(isExist && !Objects.isNull(chksQuestionResult.getMatrixFileLocation())) {
//                    System.out.println("chksQuestionResult.getMatrixFileLocation()------ : " + chksQuestionResult.getMatrixFileLocation());
//                    Path path = Paths.get(chksQuestionResult.getMatrixFileLocation());
//                    String directoryPath = path.getParent() != null ? path.getParent().toString() : "";
//                    String fileName = path.getFileName().toString();
//                    awsS3Service.deleteFile(chksQuestionResult.getMatrixFileLocation());
//                    FileStorageUtil.deleteFile(directoryPath,fileName);
                    FileStorageUtil.deleteFile(chksQuestionResult.getMatrixFileLocation());
                }
                String truncatedFileName = utilityService.getTruncatedFileName(chksQuestionResultDTO.getMatrixFile().getOriginalFilename(), 50);
//                String fileName = "ChecksheetQuestionResultData/MatrixFiles/" + chksQuestionResult.getId() + "_" + truncatedFileName;
//                awsS3Service.uploadMultipartFile(chksQuestionResultDTO.getMatrixFile(), fileName);
                String fileName = FileStorageUtil.storeFile(chksQuestionResultDTO.getMatrixFile(),"ChecksheetQuestionResultData/MatrixFiles/", chksQuestionResult.getId() + "_" + truncatedFileName);

                chksQuestionResult.setMatrixFileLocation(fileName);
                chksQuestionResultRepository.save(chksQuestionResult);
            }

            ChksQuestionResultDTO allResults = getQuestionResultsByQuestionIdAndChecksheetIdAndChecksheetHeaderId(
                    chksQuestionResultDTO.getChksQuestionId(), checksheet.get().getId(), chksQuestionResultDTO.getChksHeaderId(), chksQuestionResult);
            
            return new ResponseDTO<>(true, "Question result created successfully", allResults);
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error creating question result: " + e.getMessage(), 
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public byte[] downloadQuestionResultMatrixFile(
            ChksQuestionResultDTO chksQuestionResultDTO) throws CustomException {
        try {
            if (Objects.isNull(chksQuestionResultDTO) ||
                    Objects.isNull(chksQuestionResultDTO.getId())) {
                throw new CustomException("Please provide id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<ChksQuestionResult> chksQuestionResult = chksQuestionResultRepository.findById(chksQuestionResultDTO.getId());
            if (chksQuestionResult.isEmpty()) {
                throw new CustomException("Please provide valid id", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            if(Objects.isNull(chksQuestionResult.get().getMatrixFileLocation()) || Objects.equals(chksQuestionResult.get().getMatrixFileLocation(), "")) {
                throw new CustomException("Matrix file is not present", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            return awsS3Service.downloadFileFromS3(chksQuestionResult.get().getMatrixFileLocation());
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            throw new CustomException("Error fetching result data: " + e.getMessage(),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseEntity<Resource> downloadQuestionResultMatrixFile(
            ChksQuestionResultDTO chksQuestionResultDTO,
            HttpServletRequest request
    ) throws CustomException {
        try {
            if (Objects.isNull(chksQuestionResultDTO) ||
                    Objects.isNull(chksQuestionResultDTO.getId())) {
                throw new CustomException("Please provide id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<ChksQuestionResult> chksQuestionResult = chksQuestionResultRepository.findById(chksQuestionResultDTO.getId());
            if (chksQuestionResult.isEmpty()) {
                throw new CustomException("Please provide valid id", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            if(Objects.isNull(chksQuestionResult.get().getMatrixFileLocation()) || Objects.equals(chksQuestionResult.get().getMatrixFileLocation(), "")) {
                throw new CustomException("Matrix file is not present", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            String matrixFileLocation = chksQuestionResult.get().getMatrixFileLocation();
            String directoryPath = matrixFileLocation.substring(0, matrixFileLocation.lastIndexOf('/'));
            String fileName = matrixFileLocation.substring(matrixFileLocation.lastIndexOf('/') + 1);

            return FileStorageUtil.getDocs(directoryPath,fileName,request);
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            throw new CustomException("Error fetching result data: " + e.getMessage(),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getResultData(ChksQuestionResultDTO chksQuestionResultDTO) throws CustomException {
        try {
            if (Objects.isNull(chksQuestionResultDTO) || 
                Objects.isNull(chksQuestionResultDTO.getChecksheetId()) ||
                Objects.isNull(chksQuestionResultDTO.getChksHeaderId()) ||
                Objects.isNull(chksQuestionResultDTO.getChksQuestionId())) {
                throw new CustomException("Please provide checksheetId, chksHeaderId and chksQuestionId", 
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }

            ChksQuestionResultDTO resultData = chksQuestionResultDAO.getResultData(
                chksQuestionResultDTO.getChecksheetId(),
                chksQuestionResultDTO.getChksHeaderId(),
                chksQuestionResultDTO.getChksQuestionId(),
                chksQuestionResultDTO.getId()
            );

            if (resultData == null) {
                return new ResponseDTO<>(true, "No data found");
            }

            // Get additional data based on answer type
            if (resultData.getAnswerType() == ChksQuestionResultType.SUBJECTIVE_CONDITION) {
                List<ChksQuestionResultOptionDTO> options = chksQuestionResultOptionDAO
                    .getOptionData(resultData.getId());
                resultData.setChksQuestionResultOptions(options);
            } else if (resultData.getAnswerType() == ChksQuestionResultType.MATRIX) {
                List<ChksQuestionResultMatrixDTO> matrices = chksQuestionResultMatrixDAO
                    .getMatrixData(resultData.getId());
                resultData.setChksQuestionResultMatrices(matrices);
            }

            return new ResponseDTO<>(true, "Data fetched successfully", resultData);
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            throw new CustomException("Error fetching result data: " + e.getMessage(), 
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private void validateAndSetObjectiveType(ChksQuestionResultDTO dto, ChksQuestionResult result) throws CustomException {
        ChksQuestionResultObjectiveType objectiveType = dto.getChksQuestionResultObjectiveType();
        if (Objects.isNull(objectiveType)) {
            throw new CustomException("Please provide objective type for OBJECTIVE answer type", 
                HttpStatus.UNPROCESSABLE_ENTITY);
        }

        if (Objects.isNull(dto.getUnit())) {
            throw new CustomException("Unit is required for OBJECTIVE type", 
                HttpStatus.UNPROCESSABLE_ENTITY);
        }

        switch (objectiveType) {
            case RANGE:
                if (Objects.isNull(dto.getLowerLimit()) || Objects.isNull(dto.getUpperLimit())) {
                    throw new CustomException("Both lower and upper limits are required for RANGE type", 
                        HttpStatus.UNPROCESSABLE_ENTITY);
                }
                if (dto.getLowerLimit() > dto.getUpperLimit()) {
                    throw new CustomException("Lower limit is lower than upper limit ",
                            HttpStatus.UNPROCESSABLE_ENTITY);
                }
                result.setUpperLimit(dto.getUpperLimit());
                result.setLowerLimit(dto.getLowerLimit());
                break;
            case EQUAL_TO:
            case LESS_THAN:
            case LESS_THAN_OR_EQUAL_TO:
                if (Objects.isNull(dto.getUpperLimit())) {
                    throw new CustomException("Upper limit is required for " + objectiveType, 
                        HttpStatus.UNPROCESSABLE_ENTITY);
                }
                result.setUpperLimit(dto.getUpperLimit());
                break;
            case GREATER_THAN:
            case GREATER_THAN_OR_EQUAL_TO:
                if (Objects.isNull(dto.getLowerLimit())) {
                    throw new CustomException("Lower limit is required for GREATER_THAN type", 
                        HttpStatus.UNPROCESSABLE_ENTITY);
                }
                result.setLowerLimit(dto.getLowerLimit());
                break;
            default:
                throw new CustomException("Invalid object type",
                        HttpStatus.UNPROCESSABLE_ENTITY);
        }
        result.setObjectiveType(objectiveType);
        result.setUnit(dto.getUnit());
        result.setNoOfResults(dto.getNoOfResults());
    }

    private void validateMatrixType(ChksQuestionResultDTO dto, ChksQuestionResult result, Optional<User> currentUser) throws CustomException {
        if ((Objects.isNull(dto.getMatrixName()) ||
            Objects.isNull(dto.getNoOfResults()) ||
            Objects.isNull(dto.getNoOfRows()) ||
            Objects.isNull(dto.getNoOfColumns()) ||
            Objects.isNull(dto.getMatrixFile()) ||
                dto.getMatrixFile().getSize()<=0)
        && Objects.isNull(dto.getId())) {
            throw new CustomException("Matrix type requires matrixName, noOfResults, noOfRows, noOfColumns," +
                " matrixFile", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if(!Objects.isNull(dto.getId())) {
            if (Objects.isNull(dto.getMatrixName()) ||
                    Objects.isNull(dto.getNoOfResults()) ||
                    Objects.isNull(dto.getNoOfRows()) ||
                    Objects.isNull(dto.getNoOfColumns())) {
                throw new CustomException("Matrix type requires matrixName, noOfResults, noOfRows, noOfColumns", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if((!Objects.equals(dto.getNoOfRows(), result.getNoOfRows()) || !Objects.equals(dto.getNoOfColumns(), result.getNoOfColumns()))
            && (Objects.isNull(dto.getMatrixFile()) ||
                    dto.getMatrixFile().getSize()<=0)) {
                throw new CustomException("Please share matrixFile", HttpStatus.UNPROCESSABLE_ENTITY);
            }
//            chksQuestionResultMatrixRepository.deleteByChksQuestionResult_Id(dto.getId());
        }
        if(Objects.isNull(dto.getMatrixFile()) || dto.getMatrixFile().getSize()<=0) {
            return;
        } else {
            chksQuestionResultMatrixRepository.deleteByChksQuestionResult_Id(dto.getId());
            chksQuestionResultMatrixRepository.flush();
        }
        try {
            // Process Excel file
            Workbook workbook = WorkbookFactory.create(dto.getMatrixFile().getInputStream());
            Sheet sheet = workbook.getSheetAt(0);

            // Validate rows and columns
            if (sheet.getLastRowNum() < dto.getNoOfRows()) {
                throw new CustomException("Excel file has fewer rows than specified", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Row headerRow = sheet.getRow(0);
            if (headerRow.getLastCellNum() < dto.getNoOfColumns() + 1) { // +1 for the first column
                throw new CustomException("Excel file has fewer columns than specified", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Read column headers
            List<String> columnHeaders = new ArrayList<>();
            for (int i = 1; i <= dto.getNoOfColumns(); i++) {
                Cell cell = headerRow.getCell(i);
                if (cell == null) {
                    throw new CustomException("Missing column header at position " + i, HttpStatus.UNPROCESSABLE_ENTITY);
                }
                if(columnHeaders.contains(cell.toString().trim())) {
                    throw new CustomException("Duplicate column header found", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                columnHeaders.add(utilityService.getCellValueAsString(cell));
            }
            result.setMatrixColumnHeaderNames(columnHeaders);

            // Read row headers
            List<String> rowHeaders = new ArrayList<>();
            for (int i = 1; i <= dto.getNoOfRows(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    throw new CustomException("Missing row at position " + i, HttpStatus.UNPROCESSABLE_ENTITY);
                }
                Cell cell = row.getCell(0);
                if (cell == null) {
                    throw new CustomException("Missing row header at position " + i, HttpStatus.UNPROCESSABLE_ENTITY);
                }
                if(rowHeaders.contains(cell.toString().trim())) {
                    throw new CustomException("Duplicate row header found", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                rowHeaders.add(utilityService.getCellValueAsString(cell));
            }
            result.setMatrixRowHeaderNames(rowHeaders);

            // Process matrix data
            for (int i = 1; i <= dto.getNoOfRows(); i++) {
                Row row = sheet.getRow(i);
                String rowHeader = utilityService.getCellValueAsString(row.getCell(0));

                for (int j = 1; j <= dto.getNoOfColumns(); j++) {
                    Cell cell = row.getCell(j);
                    if (cell == null) {
                        throw new CustomException("Cell value not present on line number " + (i + 1), HttpStatus.UNPROCESSABLE_ENTITY);
                    }
                    String columnHeader = utilityService.getCellValueAsString(headerRow.getCell(j));

                    ChksQuestionResultMatrix matrix = new ChksQuestionResultMatrix();
                    matrix.setChksQuestionResult(result);
                    matrix.setChecksheet(result.getChecksheet());
                    matrix.setChksMatrixRowHdr(rowHeader);
                    matrix.setChksMatrixColHdr(columnHeader);
                    String cellValueAsString = utilityService.getCellValueAsString(cell);
                    matrix.setData(cellValueAsString);
                    matrix.setRowId((long) (i + 1));
                    matrix.setColumnId((long) (j + 1));
                    matrix.setCreatedBy(currentUser.get());

                    // Get cell comment if exists
                    Comment comment = cell.getCellComment();
                    if (comment != null) {
                        matrix.setComment(comment.getString().getString());
                    }

                    matrix.setCreatedBy(result.getCreatedBy());
                    chksQuestionResultMatrixRepository.save(matrix);
                }
            }

            workbook.close();
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            throw new CustomException("Error processing matrix file: " + e.getMessage(), 
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> cloneQuestionResult(ChksQuestionResultDTO chksQuestionResultDTO) throws CustomException {
        try {
            // Validate required fields
            if (Objects.isNull(chksQuestionResultDTO) || 
                Objects.isNull(chksQuestionResultDTO.getChecksheetId()) ||
                Objects.isNull(chksQuestionResultDTO.getChksHeaderId()) ||
                Objects.isNull(chksQuestionResultDTO.getChksQuestionId()) ||
                Objects.isNull(chksQuestionResultDTO.getCloneChksQuestionResultId())) {
                throw new CustomException("Please provide checksheetId, chksHeaderId, chksQuestionId and cloneChksQuestionResultId", 
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Check if source question result exists
            Optional<ChksQuestionResult> sourceResult = chksQuestionResultRepository
                .findById(chksQuestionResultDTO.getCloneChksQuestionResultId());
            if (sourceResult.isEmpty()) {
                throw new CustomException("Invalid cloneChksQuestionResultId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Validate checksheet and user access
            Optional<Checksheet> checksheet = checksheetRepository.findById(chksQuestionResultDTO.getChecksheetId());
            if (checksheet.isEmpty()) {
                throw new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            if (Objects.isNull(checksheet.get().getPreparerUser()) ||
                    !Objects.equals(checksheet.get().getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You have not access of this checksheet", HttpStatus.FORBIDDEN);
            }

            // Check if target question result already exists
            Optional<ChksQuestionResult> existingResult = chksQuestionResultRepository
                .findByChksHeader_IdAndChksQuestion_IdAndChecksheet_Id(
                    chksQuestionResultDTO.getChksHeaderId(),
                    chksQuestionResultDTO.getChksQuestionId(),
                    chksQuestionResultDTO.getChecksheetId()
                );
            if (existingResult.isPresent()) {
                throw new CustomException("Question result already exists for this combination", 
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Create new question result
            ChksQuestionResult newResult = new ChksQuestionResult();
            newResult.setChecksheet(checksheet.get());
            newResult.setChksHeader(chksHeaderRepository.findById(chksQuestionResultDTO.getChksHeaderId())
                .orElseThrow(() -> new CustomException("Invalid chksHeaderId", HttpStatus.UNPROCESSABLE_ENTITY)));
            newResult.setChksQuestion(chksQuestionRepository.findById(chksQuestionResultDTO.getChksQuestionId())
                .orElseThrow(() -> new CustomException("Invalid chksQuestionId", HttpStatus.UNPROCESSABLE_ENTITY)));
            
            // Copy basic properties
            newResult.setAnswerType(sourceResult.get().getAnswerType());
            newResult.setObjectiveType(sourceResult.get().getObjectiveType());
            newResult.setUpperLimit(sourceResult.get().getUpperLimit());
            newResult.setLowerLimit(sourceResult.get().getLowerLimit());
            newResult.setUnit(sourceResult.get().getUnit());
            newResult.setMatrixName(sourceResult.get().getMatrixName());
            newResult.setNoOfResults(sourceResult.get().getNoOfResults());
            newResult.setNoOfRows(sourceResult.get().getNoOfRows());
            newResult.setNoOfColumns(sourceResult.get().getNoOfColumns());
            newResult.setIsOptional(sourceResult.get().getIsOptional());
            newResult.setChksMatrixColName(sourceResult.get().getChksMatrixColName());
            newResult.setChksMatrixRowName(sourceResult.get().getChksMatrixRowName());
            newResult.setMatrixRowHeaderNames(sourceResult.get().getMatrixRowHeaderNames());
            newResult.setMatrixColumnHeaderNames(sourceResult.get().getMatrixColumnHeaderNames());
            newResult.setCreatedBy(currentUser.get());
            
            chksQuestionResultRepository.save(newResult);

            if(Objects.equals(sourceResult.get().getAnswerType(), ChksQuestionResultType.SUBJECTIVE_CONDITION)) {
                // Clone options if they exist
                List<ChksQuestionResultOption> sourceOptions = chksQuestionResultOptionRepository
                        .findByChksQuestionResult_Id(sourceResult.get().getId());
                for (ChksQuestionResultOption sourceOption : sourceOptions) {
                    ChksQuestionResultOption newOption = new ChksQuestionResultOption();
                    newOption.setChksQuestionResult(newResult);
                    newOption.setChecksheet(checksheet.get());
                    newOption.setOption(sourceOption.getOption());
                    newOption.setJudgement(sourceOption.getJudgement());
                    newOption.setCreatedBy(currentUser.get());
                    chksQuestionResultOptionRepository.save(newOption);
                }
            }

            if(Objects.equals(sourceResult.get().getAnswerType(), ChksQuestionResultType.MATRIX)) {
                // Clone matrices if they exist
                List<ChksQuestionResultMatrix> sourceMatrices = chksQuestionResultMatrixRepository
                        .findByChksQuestionResult_Id(sourceResult.get().getId());
                for (ChksQuestionResultMatrix sourceMatrix : sourceMatrices) {
                    ChksQuestionResultMatrix newMatrix = new ChksQuestionResultMatrix();
                    newMatrix.setChksQuestionResult(newResult);
                    newMatrix.setChecksheet(checksheet.get());
                    newMatrix.setChksMatrixRowHdr(sourceMatrix.getChksMatrixRowHdr());
                    newMatrix.setChksMatrixColHdr(sourceMatrix.getChksMatrixColHdr());
                    newMatrix.setData(sourceMatrix.getData());
                    newMatrix.setComment(sourceMatrix.getComment());
                    newMatrix.setRowId(sourceMatrix.getRowId());
                    newMatrix.setColumnId(sourceMatrix.getColumnId());
                    newMatrix.setCreatedBy(currentUser.get());
                    chksQuestionResultMatrixRepository.save(newMatrix);
                }
            }

            return new ResponseDTO<>(true, "Question result cloned successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error cloning question result: " + e.getMessage(), 
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

//    @Override
    public ResponseDTO<?> getQuestionResults(List<Long> questionIds) throws CustomException {
        try {
            if (Objects.isNull(questionIds) || questionIds.isEmpty()) {
                throw new CustomException("Please provide question ids", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            List<ChksQuestionResultDTO> results = chksQuestionResultDAO.getQuestionResults(questionIds);
            
            // Add file URLs for matrix type results
            results.stream()
                .filter(result -> ChksQuestionResultType.MATRIX.equals(result.getAnswerType()))
                .forEach(result -> {
                    if (result.getMatrixFileLocation() != null) {
                        try {
//                            String fileUrl = awsS3Service.getDocs(result.getMatrixFileLocation()).toString();
                            String fileUrl = FileStorageUtil.getFileURL(result.getMatrixFileLocation());
                            result.setMatrixFileUrl(fileUrl);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });

            return new ResponseDTO<>(true, "Results fetched successfully", results);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            throw new CustomException("Error fetching question results: " + e.getMessage(), 
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public ChksQuestionResultDTO getQuestionResultsByQuestionIdAndChecksheetIdAndChecksheetHeaderId(
            Long questionId, Long checksheetId, Long checkSheetHeaderId, ChksQuestionResult questionResult) throws CustomException {
        try {

            ChksQuestionResultDTO resultDTO = new ChksQuestionResultDTO();
            resultDTO.setId(questionResult.getId());
            resultDTO.setAnswerType(questionResult.getAnswerType());
            resultDTO.setChksHeaderId(questionResult.getChksHeader().getId());
            resultDTO.setChksQuestionId(questionResult.getChksQuestion().getId());
            resultDTO.setUnit(questionResult.getUnit());
            resultDTO.setChksMatrixColName(questionResult.getChksMatrixColName());
            resultDTO.setChksMatrixRowName(questionResult.getChksMatrixRowName());
            resultDTO.setMatrixName(questionResult.getMatrixName());
            resultDTO.setUpperLimit(questionResult.getUpperLimit());
            resultDTO.setLowerLimit(questionResult.getLowerLimit());
            resultDTO.setUnit(questionResult.getUnit());
            resultDTO.setNoOfResults(questionResult.getNoOfResults());
            resultDTO.setNoOfRows(questionResult.getNoOfRows());
            resultDTO.setNoOfColumns(questionResult.getNoOfColumns());
            resultDTO.setIsOptional(questionResult.getIsOptional());
            resultDTO.setChksQuestionResultObjectiveType(questionResult.getObjectiveType());
//                                            if(withResultData){
            if (List.of(ChksQuestionResultType.SUBJECTIVE_CONDITION,ChksQuestionResultType.SELECTIVE).contains(questionResult.getAnswerType())) {
                List<ChksQuestionResultOptionDTO> options = chksQuestionResultOptionDAO
                        .getOptionData(questionResult.getId());
                resultDTO.setChksQuestionResultOptions(options);
            } else if (questionResult.getAnswerType() == ChksQuestionResultType.MATRIX) {
                resultDTO.setChksMatrixColNm(questionResult.getMatrixColumnHeaderNames());
                resultDTO.setChksMatrixRowNm(questionResult.getMatrixRowHeaderNames());
                List<ChksQuestionResultMatrixDTO> matrices = chksQuestionResultMatrixDAO
                        .getMatrixData(questionResult.getId());
                resultDTO.setChksQuestionResultMatrices(matrices);
            }
//                                            }
            return resultDTO;
        } catch (Exception e) {
            throw new CustomException("Error fetching question results: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> bulkSetQuestionResult(BulkChksQuestionResultDTO bulkDTO) throws CustomException {
        try {
            if (Objects.isNull(bulkDTO) || 
                Objects.isNull(bulkDTO.getChecksheetId()) ||
                Objects.isNull(bulkDTO.getChksHeaderIds()) ||
                Objects.isNull(bulkDTO.getChksQuestionIds()) ||
                Objects.isNull(bulkDTO.getAnswerType())) {
                throw new CustomException("Please provide checksheetId, chksHeaderIds, chksQuestionIds and answerType", 
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            if (bulkDTO.getChksHeaderIds().isEmpty() || bulkDTO.getChksQuestionIds().isEmpty()) {
                throw new CustomException("ChksHeaderIds and chksQuestionIds cannot be empty", 
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Optional<Checksheet> checksheet = checksheetRepository.findById(bulkDTO.getChecksheetId());
            if (checksheet.isEmpty()) {
                throw new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            if (Objects.isNull(checksheet.get().getPreparerUser()) ||
                    !Objects.equals(checksheet.get().getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You do not have access to this checksheet", HttpStatus.FORBIDDEN);
            }
            
            List<ChksQuestionResultDTO> createdResults = new ArrayList<>();
            List<String> errors = new ArrayList<>();
            int successCount = 0;

            for (Long headerId : bulkDTO.getChksHeaderIds()) {
                for (Long questionId : bulkDTO.getChksQuestionIds()) {
                    try {
                        ChksQuestionResultDTO dto = new ChksQuestionResultDTO();
                        dto.setChecksheetId(bulkDTO.getChecksheetId());
                        dto.setChksHeaderId(headerId);
                        dto.setChksQuestionId(questionId);
                        dto.setAnswerType(bulkDTO.getAnswerType());
                        dto.setChksQuestionResultObjectiveType(bulkDTO.getChksQuestionResultObjectiveType());
                        dto.setUpperLimit(bulkDTO.getUpperLimit());
                        dto.setLowerLimit(bulkDTO.getLowerLimit());
                        dto.setUnit(bulkDTO.getUnit());
                        dto.setNoOfResults(bulkDTO.getNoOfResults());
                        dto.setIsOptional(bulkDTO.getIsOptional());

                        if (bulkDTO.getAnswerType() == ChksQuestionResultType.SUBJECTIVE_CONDITION) {
                            dto.setChksQuestionResultOptions(bulkDTO.getChksQuestionResultOptions());
                        }
                        if (bulkDTO.getAnswerType() == ChksQuestionResultType.MATRIX) {
                            dto.setChksMatrixColNm(bulkDTO.getChksMatrixColNm());
                            dto.setChksMatrixRowNm(bulkDTO.getChksMatrixRowNm());
                            dto.setChksMatrixColName(bulkDTO.getChksMatrixColName());
                            dto.setChksMatrixRowName(bulkDTO.getChksMatrixRowName());
                            dto.setNoOfRows(bulkDTO.getNoOfRows());
                            dto.setNoOfColumns(bulkDTO.getNoOfColumns());
                            dto.setMatrixName(bulkDTO.getMatrixName());
                            dto.setMatrixFile(bulkDTO.getMatrixFile());                            
                        }
                        
                        // Call existing service method
                        ResponseDTO<?> result = setQuestionResult(dto);
                        if (result.getStatus()) {
                            successCount++;
                            if (result.getData() != null) {
                                createdResults.add((ChksQuestionResultDTO) result.getData());
                            }
                        }
                    } catch (CustomException ce) {
                        ce.printStackTrace();
                        // Collect errors but continue processing
                        errors.add("Error for headerId " + headerId + ", questionId " + questionId + ": " + ce.getMessage());
                        throw ce;
                    } catch (Exception e) {
                        errors.add("Unexpected error for headerId " + headerId + ", questionId " + questionId + ": " + e.getMessage());
                        throw new CustomException("Error creating bulk question results: " + e.getMessage(), 
                            HttpStatus.INTERNAL_SERVER_ERROR);
                    }
                }
            }
            
            // Prepare response message
            String message = "Processed " + successCount + " question results successfully";
            if (!errors.isEmpty()) {
                message += " with " + errors.size() + " errors";
            }
            
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("successCount", successCount);
            
            return new ResponseDTO<>(true, message, responseData);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error creating bulk question results: " + e.getMessage(), 
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
