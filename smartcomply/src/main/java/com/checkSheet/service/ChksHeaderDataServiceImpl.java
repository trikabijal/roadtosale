package com.checkSheet.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.checkSheet.helper.FileStorageUtil;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.checkSheet.DAO.ChksGeneralFieldDAO;
import com.checkSheet.DAO.ChksHeaderDAO;
import com.checkSheet.DAO.ChksHeaderDataDAO;
import com.checkSheet.DAO.ChksHeaderDataFileDAO;
import com.checkSheet.DAO.ChksQuestionDAO;
import com.checkSheet.DAO.ChksQuestionResultDAO;
import com.checkSheet.DAO.ChksQuestionResultMatrixDAO;
import com.checkSheet.DAO.ChksQuestionResultOptionDAO;
import com.checkSheet.DTO.ChksGeneralFieldDTO;
import com.checkSheet.DTO.ChksHeaderDTO;
import com.checkSheet.DTO.ChksHeaderDataDTO;
import com.checkSheet.DTO.ChksHeaderDataFileDTO;
import com.checkSheet.DTO.ChksQuestionDTO;
import com.checkSheet.DTO.ChksQuestionFileDTO;
import com.checkSheet.DTO.ChksQuestionResultDTO;
import com.checkSheet.DTO.ChksQuestionResultMatrixDTO;
import com.checkSheet.DTO.ChksQuestionResultOptionDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.constant.ChecksheetStatusType;
import com.checkSheet.constant.ChksQuestionResultObjectiveType;
import com.checkSheet.constant.ChksQuestionResultType;
import com.checkSheet.entity.Checksheet;
import com.checkSheet.entity.ChksHeader;
import com.checkSheet.entity.ChksHeaderData;
import com.checkSheet.entity.ChksHeaderDataFile;
import com.checkSheet.entity.ChksQuestion;
import com.checkSheet.entity.ChksQuestionFile;
import com.checkSheet.entity.ChksQuestionResult;
import com.checkSheet.entity.User;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.ChecksheetRepository;
import com.checkSheet.repository.ChksGeneralFieldRepository;
import com.checkSheet.repository.ChksHeaderDataFileRepository;
import com.checkSheet.repository.ChksHeaderDataRepository;
import com.checkSheet.repository.ChksHeaderRepository;
import com.checkSheet.repository.ChksQuestionFileRepository;
import com.checkSheet.repository.ChksQuestionRepository;
import com.checkSheet.repository.ChksQuestionResultMatrixRepository;
import com.checkSheet.repository.ChksQuestionResultOptionRepository;
import com.checkSheet.repository.ChksQuestionResultRepository;

@Service
public class ChksHeaderDataServiceImpl implements ChksHeaderDataService {
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
    private ChksHeaderDataRepository chksHeaderDataRepository;

    @Autowired
    private ChksHeaderDataFileRepository chksHeaderDataFileRepository;
    
    @Autowired
    private ChksQuestionRepository chksQuestionRepository;

    @Autowired
    private ChksHeaderDataDAO chksHeaderDataDAO;

    @Autowired
    private ChksQuestionDAO chksQuestionDAO;

//    @Autowired
//    private ChksQuestionResultHeaderRepository chksQuestionResultHeaderRepository;

    @Autowired
    private ChksQuestionResultOptionRepository chksQuestionResultOptionRepository;

    @Autowired
    private ChksQuestionResultRepository chksQuestionResultRepository;

    @Autowired
    private ChksQuestionFileRepository chksQuestionFileRepository;

    @Autowired
    private ChksQuestionResultMatrixRepository chksQuestionResultMatrixRepository;

    @Autowired
    private AWSS3Service awsS3Service;

    @Autowired
    private ChksHeaderDataFileDAO chksHeaderDataFileDAO;

    @Autowired
    private ChksQuestionResultDAO chksQuestionResultDAO;

    @Autowired
    private ChksHeaderService chksHeaderService;

    @Autowired
    private ChksGeneralFieldService chksGeneralFieldService;

    @Autowired
    private ChksQuestionResultOptionDAO chksQuestionResultOptionDAO;
    @Autowired
    private ChksQuestionResultMatrixDAO chksQuestionResultMatrixDAO;

    private static final Set<Long> PROCESSING_CHECKSHEET_IDS = Collections.synchronizedSet(new HashSet<>());

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> validateCheckSheet(MultipartFile file, Long checksheetId, Boolean isAppend) throws CustomException {
        try {
            // Validate input
            if (file == null || file.isEmpty() || checksheetId == null) {
                throw new CustomException("Please provide valid file and checksheet id", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Check if checksheet is already being processed
            if (!PROCESSING_CHECKSHEET_IDS.add(checksheetId)) {
                throw new CustomException("Another file is currently being uploaded for this checksheet. Please wait for it to complete.",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Optional<Checksheet> checksheet = checksheetRepository.findById(checksheetId);
            if (!checksheet.isPresent()) {
                throw new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            return validateAndProcessExcelData(file, checksheetId, isAppend);
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error processing file: " + e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        } finally {
            PROCESSING_CHECKSHEET_IDS.remove(checksheetId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> validateAndProcessExcelData(MultipartFile file, Long checksheetId, Boolean isAppend) throws CustomException {
        // Validate input
        if (file == null || file.isEmpty() || checksheetId == null) {
            throw new CustomException("Please provide valid file and checksheet id", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        // Optional<Checksheet> checksheet = checksheetRepository.findById(checksheetId);
        
        Optional<Checksheet> checksheet = checksheetRepository.findById(checksheetId);
        if(!checksheet.isPresent()) {
            throw new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        try {
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();

            if(Objects.isNull(checksheet.get().getPreparerUser()) ||
                    !Objects.equals(checksheet.get().getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You have not access of this checksheet", HttpStatus.FORBIDDEN);
            }
            if(Objects.equals(ChecksheetStatusType.CREATE_TEMPLATE, checksheet.get().getStatus()) ||
                    Objects.equals(ChecksheetStatusType.INVALIDATED, checksheet.get().getStatus()) ||
                    Objects.equals(ChecksheetStatusType.NOT_APPROVED, checksheet.get().getStatus())) {
                checksheet.get().setStatus(ChecksheetStatusType.CREATE_CONTENT);
               checksheetRepository.save(checksheet.get());
            }

            if(!Objects.equals(ChecksheetStatusType.CREATE_CONTENT, checksheet.get().getStatus())) {
                throw new CustomException("You can not create content now.", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            // Get headers from database
            List<ChksHeader> dbHeaders = chksHeaderRepository.findByChecksheet_IdAndIsResultColumnFalseOrderById(checksheetId);

            if (dbHeaders.isEmpty()) {
                throw new CustomException("No headers found for this checksheet", HttpStatus.UNPROCESSABLE_ENTITY);
            } else if(dbHeaders.size() < 2) {
                throw new CustomException("Minimum 2 headers must have in checksheet", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Read Excel file
            Workbook workbook = WorkbookFactory.create(file.getInputStream());
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);

            if (headerRow == null) {
                throw new CustomException("Excel file is empty", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Validate headers count — extra columns after the db headers are optional answer config
            int excelHeaderCount = headerRow.getPhysicalNumberOfCells();
            boolean hasAnswerColumns = excelHeaderCount > dbHeaders.size();
            if (excelHeaderCount < dbHeaders.size()) {
                throw new CustomException("Header mismatch — Excel has fewer columns than checksheet headers", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Get the result column header for creating question results
            List<ChksHeader> resultHeaders = chksHeaderRepository.findByChecksheet_IdAndIsResultColumnTrueOrderById(checksheetId);
            ChksHeader resultHeader = resultHeaders.isEmpty() ? null : resultHeaders.get(0);

            if (hasAnswerColumns && resultHeader == null) {
                throw new CustomException(
                    "Excel has answer config columns but the checksheet has no Result column. " +
                    "Add a Result column header to the checksheet first, or remove the extra columns from the Excel.",
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Validate header names and order
            for (int i = 0; i < dbHeaders.size(); i++) {
                Cell cell = headerRow.getCell(i);
                String excelHeader = cell != null ? cell.getStringCellValue().trim() : "";
                String dbHeader = dbHeaders.get(i).getName().trim();
                
                if (!excelHeader.equals(dbHeader)) {
                    throw new CustomException("Invalid header. Expected: " + dbHeader + ", Found: " + excelHeader + " at position " + (i + 1), 
                        HttpStatus.UNPROCESSABLE_ENTITY);
                }
            }

            // Process data rows
            List<ChksHeaderData> headerDataList = new ArrayList<>();
            Map<Integer, Map<Integer, ChksHeaderData>> cellMap = new HashMap<>(); // Map to store entries by [column][row]
            int rowCount = sheet.getPhysicalNumberOfRows();
            if(Objects.isNull(isAppend) || !isAppend) {
                ChksHeaderDataDTO tempChksHeaderDataDTO = new ChksHeaderDataDTO();
                tempChksHeaderDataDTO.setChecksheetId(checksheetId);
                resetChecksheetData(tempChksHeaderDataDTO);
            }

            for (int i = 1; i < rowCount; i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                // Skip example/indicator rows from template download
                String firstCellVal = utilityService.getCellValueAsString(row.getCell(0)).trim();
                if (firstCellVal.startsWith("↓") || firstCellVal.startsWith("Example:")) continue;
                // Also skip if the last header column starts with "Example:"
                String lastHeaderCellVal = utilityService.getCellValueAsString(row.getCell(dbHeaders.size() - 1)).trim();
                if (lastHeaderCellVal.startsWith("Example:")) continue;

                for (int j = 0; j < dbHeaders.size(); j++) {
                    Cell cell = row.getCell(j);
                    String cellValue = utilityService.getCellValueAsString(cell).trim();
                    if(j==0 && !cellValue.trim().isEmpty()) {
                        for (int k = 0; k < dbHeaders.size(); k++) {
                            Cell tempCell = row.getCell(k);
                            String tempCellValue = utilityService.getCellValueAsString(tempCell).trim();
                            if(tempCellValue.trim().isEmpty()) {
                                throw new CustomException("Value not provided properly at row " + (i+1),
                                        HttpStatus.UNPROCESSABLE_ENTITY);
                            }
                        }
                    }


                    // If this is the last column, save to chks_questions
                    if (j == dbHeaders.size() - 1) {
                        if(cellValue.isEmpty()) {
                            throw new CustomException("Question not provide at row " + (i + 1),
                                    HttpStatus.UNPROCESSABLE_ENTITY);
                        }
                        ChksQuestion question = new ChksQuestion();
                        question.setName(cellValue);
                        
                        // Get cell comment if exists
                        Comment comment = cell.getCellComment();
                        if (comment != null) {
                            question.setDescription(comment.getString().getString());
                        }

                        question.setCreatedBy(currentUser.get());
                        question.setChecksheet(checksheet.get());
                        question.setChksHeader(dbHeaders.get(j));
                        // Find parent by checking left and upper cells
                        ChksHeaderData parent = findParentCell(cellMap, i, j);
                        if (parent != null) {
                            // Calculate order_no based on existing records with the same chks_header_data_id and checksheet_id
                            Integer maxOrderNo = 0;
                            List<ChksQuestion> existingRecords = chksQuestionRepository.findByChecksheet_IdAndChecksheetChksHeaderDataOrderById(checksheet.get().getId(), parent.getId());
//                            boolean foundMatchingHeader = false;
//                            for (ChksQuestion existing : existingRecords) {
//                                if (existing.getChksHeader().getId().equals(dbHeaders.get(j).getId()) &&
//                                        existing.getOrderNo() != null) {
//                                    foundMatchingHeader = true;
//                                    if (existing.getOrderNo() > maxOrderNo) {
//                                        maxOrderNo = existing.getOrderNo();
//                                    }
//                                }
//                            }

                            if(Objects.nonNull(existingRecords)) {
                                maxOrderNo = existingRecords.size();
                            }
                            // If no records with the same chks_header_id exist, start from 1
                            question.setOrderNo(maxOrderNo + 1);
                            question.setChksHeaderData(parent);
                        } else {
                            // Calculate order_no based on existing records with the same chks_header_id and checksheet_id
                            Integer maxOrderNo = 0;
                            List<ChksQuestion> existingRecords = chksQuestionRepository.findByChecksheet_IdAndChecksheetChksHeaderDataIsNullOrderById(checksheet.get().getId());
//                            boolean foundMatchingHeader = false;
//                            for (ChksQuestion existing : existingRecords) {
//                                if (existing.getChksHeader().getId().equals(dbHeaders.get(j).getId()) &&
//                                        existing.getOrderNo() != null) {
//                                    foundMatchingHeader = true;
//                                    if (existing.getOrderNo() > maxOrderNo) {
//                                        maxOrderNo = existing.getOrderNo();
//                                    }
//                                }
//                            }
                            if(Objects.nonNull(existingRecords)) {
                                maxOrderNo = existingRecords.size();
                            }
                            // If no records with the same chks_header_id exist, start from 1
                            question.setOrderNo(maxOrderNo + 1);
                        }

                        chksQuestionRepository.save(question);

                        // If extra answer columns exist, create question result + options
                        // Expected extra columns after the db headers:
                        //   Col N+0: Answer Type (Subjective Condition, Objective, Subjective, Matrix)
                        //   Col N+1: Param 1 — for SC: Option 1 text; for OBJ: Objective Type; for MATRIX: Matrix Name
                        //   Col N+2: Param 2 — for SC: Option 1 Status; for OBJ: Upper Limit; for MATRIX: Row Headers (comma-separated)
                        //   Col N+3: Param 3 — for SC: Option 2 text; for OBJ: Lower Limit; for MATRIX: Column Headers (comma-separated)
                        //   Col N+4: Param 4 — for SC: Option 2 Status; for OBJ: Unit
                        if (hasAnswerColumns && resultHeader != null) {
                            int colBase = dbHeaders.size();
                            String answerType = getCellSafe(row, colBase, utilityService);
                            String param1 = getCellSafe(row, colBase + 1, utilityService);
                            String param2 = getCellSafe(row, colBase + 2, utilityService);
                            String param3 = getCellSafe(row, colBase + 3, utilityService);
                            String param4 = getCellSafe(row, colBase + 4, utilityService);

                            if (!answerType.isEmpty()) {
                                ChksQuestionResultType resultType = parseAnswerType(answerType);

                                ChksQuestionResult questionResult = new ChksQuestionResult();
                                questionResult.setChksHeader(resultHeader);
                                questionResult.setChksQuestion(question);
                                questionResult.setChecksheet(checksheet.get());
                                questionResult.setAnswerType(resultType);
                                questionResult.setCreatedBy(currentUser.get());

                                switch (resultType) {
                                    case SUBJECTIVE_CONDITION -> {
                                        // param1=OK text, param2=OK status, param3=NOT_OK text, param4=NOT_OK status
                                        chksQuestionResultRepository.save(questionResult);
                                        if (!param1.isEmpty()) {
                                            saveOption(questionResult, checksheet.get(), param1,
                                                    param2.isEmpty() ? "Ok" : param2, currentUser.get());
                                        }
                                        if (!param3.isEmpty()) {
                                            saveOption(questionResult, checksheet.get(), param3,
                                                    param4.isEmpty() ? "Not Ok" : param4, currentUser.get());
                                        }
                                    }
                                    case OBJECTIVE -> {
                                        // param1=Objective Type (Equal To, Range, etc.), param2=Upper Limit, param3=Lower Limit, param4=Unit
                                        questionResult.setObjectiveType(parseObjectiveType(param1));
                                        if (!param2.isEmpty()) {
                                            try { questionResult.setUpperLimit(Double.parseDouble(param2)); }
                                            catch (NumberFormatException e) {
                                                throw new CustomException("Invalid upper limit '" + param2 + "' at row " + (i + 1) + ". Must be a number.", HttpStatus.UNPROCESSABLE_ENTITY);
                                            }
                                        }
                                        if (!param3.isEmpty()) {
                                            try { questionResult.setLowerLimit(Double.parseDouble(param3)); }
                                            catch (NumberFormatException e) {
                                                throw new CustomException("Invalid lower limit '" + param3 + "' at row " + (i + 1) + ". Must be a number.", HttpStatus.UNPROCESSABLE_ENTITY);
                                            }
                                        }
                                        if (!param4.isEmpty()) {
                                            questionResult.setUnit(param4);
                                        }
                                        chksQuestionResultRepository.save(questionResult);
                                    }
                                    case SUBJECTIVE -> {
                                        // Free text — no options or limits needed
                                        chksQuestionResultRepository.save(questionResult);
                                    }
                                    case NA -> {
                                        // Not Applicable — just save the result type, no options or limits
                                        chksQuestionResultRepository.save(questionResult);
                                    }
                                    default -> chksQuestionResultRepository.save(questionResult);
                                }
                            }
                        }

                        continue; // Skip saving to chks_header_data
                    }
                    if (cell == null) continue;
                    if (row == null) continue;
                    if (cellValue.isEmpty()) continue;

                    // Original code for non-last columns
                    ChksHeaderData headerData = new ChksHeaderData();
                    headerData.setChksHeader(dbHeaders.get(j));
                    headerData.setName(cellValue);

                    // Get cell comment if exists
                    Comment comment = cell.getCellComment();
                    if (comment != null) {
                        headerData.setDescription(comment.getString().getString());
                    }

                    if (currentUser.isPresent()) {
                        headerData.setCreatedBy(currentUser.get());
                    }
                    headerData.setChecksheet(checksheet.get());
                    headerData.setLevel((long) (j + 1));
                    ChksHeaderData savedHeaderData = chksHeaderDataRepository.save(headerData);

                    // Find parent by checking left and upper cells
                    ChksHeaderData parent = findParentCell(cellMap, i, j);
                    if (parent != null) {
                        savedHeaderData.setChksHeaderData(parent);
                        // Calculate order_no based on existing records with the same chks_header_data_id and checksheet_id
                        Integer maxOrderNo = 0;
                        List<ChksHeaderData> existingRecords = chksHeaderDataRepository.findByChecksheet_IdAndChecksheetChksHeaderDataOrderById(checksheet.get().getId(), parent.getId());
//                        boolean foundMatchingHeader = false;
//                        for (ChksHeaderData existing : existingRecords) {
//                            if (existing.getChksHeader().getId().equals(dbHeaders.get(j).getId()) &&
//                                    existing.getOrderNo() != null) {
//                                foundMatchingHeader = true;
//                                if (existing.getOrderNo() > maxOrderNo) {
//                                    maxOrderNo = existing.getOrderNo();
//                                }
//                            }
//                        }
                        if(Objects.nonNull(existingRecords)) {
                            maxOrderNo = existingRecords.size();
                        }
                        // If no records with the same chks_header_id exist, start from 1
                        headerData.setOrderNo(maxOrderNo);
                        chksHeaderDataRepository.save(savedHeaderData);
                    } else {
                        // Calculate order_no based on existing records with the same chks_header_id and checksheet_id
                        Integer maxOrderNo = 0;
                        List<ChksHeaderData> existingRecords = chksHeaderDataRepository.findByChecksheet_IdAndChecksheetChksHeaderDataIsNullOrderById(checksheet.get().getId());
//                        boolean foundMatchingHeader = false;
//                        for (ChksHeaderData existing : existingRecords) {
//                            if (existing.getChksHeader().getId().equals(dbHeaders.get(j).getId()) &&
//                                    existing.getOrderNo() != null) {
//                                foundMatchingHeader = true;
//                                if (existing.getOrderNo() > maxOrderNo) {
//                                    maxOrderNo = existing.getOrderNo();
//                                }
//                            }
//                        }

                        if(Objects.nonNull(existingRecords)) {
                            maxOrderNo = existingRecords.size();
                        }
                        // If no records with the same chks_header_id exist, start from 1
                        headerData.setOrderNo(maxOrderNo);
                    }

                    // Store in cell map
                    cellMap.computeIfAbsent(j, k -> new HashMap<>()).put(i, savedHeaderData);
                    headerDataList.add(savedHeaderData);
                }
            }

            //Save file into S3 bucket for downloading -- start
            Checksheet chks = checksheet.get();
            if(chks.getPath() != null && !chks.getPath().trim().isEmpty()){
//                awsS3Service.deleteFile(chks.getPath());
//                Path path = Paths.get(chks.getPath());
//                String directoryPath = path.getParent() != null ? path.getParent().toString() : "";
//                String fileName = path.getFileName().toString();
//                FileStorageUtil.deleteFile(directoryPath,fileName);
                FileStorageUtil.deleteFile(chks.getPath());
            }

            String truncatedFilename = utilityService.getTruncatedFileName(file.getOriginalFilename(), 50);
//            String fileName = "Checksheet/" + chks.getId() + "_" + truncatedFilename;
//            awsS3Service.uploadMultipartFile(file, fileName);
            String fileName = FileStorageUtil.storeFile(file,"Checksheet/", chks.getId() + "_" + truncatedFilename);

            chks.setPath(fileName);
            checksheetRepository.save(chks);
            //Save file into S3 bucket for downloading -- end

            return new ResponseDTO<>(true, "Data processed successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error processing file: " + e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        } finally {
        }
    }

    // Helper method to count leading spaces
    private int countLeadingSpaces(String str) {
        int count = 0;
        while (count < str.length() && Character.isWhitespace(str.charAt(count))) {
            count++;
        }
        return count;
    }

    // Helper method to find parent cell
    private ChksHeaderData findParentCell(Map<Integer, Map<Integer, ChksHeaderData>> cellMap, int currentRow, int currentCol) {
        // First check left side
        if (currentCol > 0) {
            ChksHeaderData leftCell = getCell(cellMap, currentCol - 1, currentRow);
            if (leftCell != null) {
                return leftCell;
            }
        }

        // If no left cell, check upper cells in left column
        if (currentCol > 0) {
            for (int row = currentRow - 1; row >= 1; row--) {
                ChksHeaderData upperLeftCell = getCell(cellMap, currentCol - 1, row);
                if (upperLeftCell != null) {
                    return upperLeftCell;
                }
            }
        }

        return null;
    }

    // Helper method to safely get cell from map
    private ChksHeaderData getCell(Map<Integer, Map<Integer, ChksHeaderData>> cellMap, int col, int row) {
        Map<Integer, ChksHeaderData> columnMap = cellMap.get(col);
        return columnMap != null ? columnMap.get(row) : null;
    }

    @Override
    public ResponseDTO<?> getChecksheetData(Long checksheetId, boolean withResultData) throws CustomException {
        try {
            if (Objects.isNull(checksheetId)) {
                throw new CustomException("Please provide checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Get raw data from DAO
            List<Object[]> rawData = chksHeaderDataDAO.getChksHeaderDataByChecksheetId(checksheetId);
            List<ChksHeader> dbHeaders = chksHeaderRepository.findByChecksheet_IdAndIsResultColumnFalseOrderById(checksheetId);
            Optional<Checksheet> checksheet = checksheetRepository.findById(checksheetId);
            if(checksheet.isEmpty()) {
                throw new CustomException("Please provide valid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Get all question results for this checksheet and create a map
//            Map<String, ChksQuestionResult> questionResultMap = chksQuestionResultRepository
//                .findByChecksheet_IdOrderById(checksheetId)
//                .stream()
//                .collect(Collectors.toMap(
//                    result -> result.getChksHeader().getId() + "_" + result.getChksQuestion().getId(),
//                    result -> result,
//                    (existing, replacement) -> existing
//                ));


            Map<Long, List<ChksQuestionResultDTO>> questionResultMap = chksQuestionResultDAO
                    .getChksQuestionResultByChecksheetId(checksheetId)
                    .stream()
                    .collect(Collectors.groupingBy(ChksQuestionResultDTO::getChksQuestionId));

            // Get all header data files for this checksheet and create a map
            Map<Long, List<ChksHeaderDataFileDTO>> headerDataFilesMap = chksHeaderDataFileDAO
                    .getChksHeaderDataFileByChecksheetId(checksheetId)
                    .stream()
                    .collect(Collectors.groupingBy(ChksHeaderDataFileDTO::getChksHeaderDataId));


            // Get all question data files for this checksheet and create a map
            Map<Long, List<ChksQuestionFile>> questionDataFilesMap = chksQuestionFileRepository
                    .findByChecksheet_IdOrderById(checksheetId)
                    .stream()
                    .collect(Collectors.groupingBy(file -> file.getChksQuestion().getId()));

            // Process into hierarchical structure
            Map<Long, ChksHeaderDataDTO> dtoMap = new HashMap<>();
            Map<Long, List<ChksHeaderDataDTO>> childrenMap = new HashMap<>();
            
            for (Object[] row : rawData) {
                // Safe conversion methods for handling different types
                Long id = convertToLong(row[0]);
                String name = convertToString(row[1]);
                String description = convertToString(row[2]);
                Long headerId = convertToLong(row[3]);
                Long parentId = convertToLong(row[4]);
                Long level = convertToLong(row[5]);
                Integer orderNo = convertToInteger(row[6]);

                // Create DTO if not exists
                ChksHeaderDataDTO dto = dtoMap.computeIfAbsent(id, k -> {
                    ChksHeaderDataDTO newDto = new ChksHeaderDataDTO();
                    newDto.setId(id);
                    newDto.setName(name);
                    newDto.setDescription(description);
                    newDto.setChksHeaderId(headerId);
                    newDto.setChksHeaderDataId(parentId);
                    newDto.setLevel(level);
                    newDto.setOrderNo(orderNo);
                    newDto.setQuestions(new ArrayList<>());
                    
                    // Add header data files if they exist for this id
                    if (headerDataFilesMap.containsKey(id)) {
                        List<ChksHeaderDataFileDTO> filesDTOs = headerDataFilesMap.get(id)
                            .stream()
                            .map(file -> {
                                ChksHeaderDataFileDTO fileDTO = new ChksHeaderDataFileDTO();
                                fileDTO.setId(file.getId());
                                fileDTO.setPath(file.getPath());
                                fileDTO.setChksHeaderDataId(file.getChksHeaderDataId());
                                // Only set URL for the first file
                                if (headerDataFilesMap.get(id).indexOf(file) == 0) {
//                                    fileDTO.setUrl(awsS3Service.getDocs(file.getPath()).toString());
                                    fileDTO.setUrl(FileStorageUtil.getFileURL(file.getPath()));
                                }
                                return fileDTO;
                            })
                            .collect(Collectors.toList());
                        newDto.setChksHeaderDataFiles(filesDTOs);
                    }

                    if((dbHeaders.size() -1) == level) {
//                        List<ChksQuestionDTO> chksQuestionByChecksheetHeaderDataId = chksQuestionDAO.getChksQuestionByChecksheetHeaderDataId(id);
//                        newDto.setQuestions(chksQuestionByChecksheetHeaderDataId);
                        List<ChksQuestionDTO> questions = chksQuestionDAO.getChksQuestionByChecksheetHeaderDataId(id);
                        // Add question results to questions if they exist
                        for (ChksQuestionDTO question : questions) {
                            if (questionResultMap.containsKey(question.getId())) {
                                List<ChksQuestionResultDTO> resultDTOS = questionResultMap.get(question.getId())
                                        .stream()
                                        .map(questionResult -> {
                                            ChksQuestionResultDTO resultDTO = new ChksQuestionResultDTO();
                                            resultDTO.setId(questionResult.getId());
                                            resultDTO.setAnswerType(questionResult.getAnswerType());
                                            resultDTO.setChksHeaderId(questionResult.getChksHeaderId());
                                            resultDTO.setChksQuestionId(questionResult.getChksQuestionId());
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
                                            resultDTO.setChksQuestionResultObjectiveType(questionResult.getChksQuestionResultObjectiveType());
//                                            if(withResultData){
                                                if (List.of(ChksQuestionResultType.SUBJECTIVE_CONDITION,ChksQuestionResultType.SELECTIVE).contains(questionResult.getAnswerType())) {
                                                    List<ChksQuestionResultOptionDTO> options = chksQuestionResultOptionDAO
                                                            .getOptionData(questionResult.getId());
                                                    resultDTO.setChksQuestionResultOptions(options);
                                                } else if (questionResult.getAnswerType() == ChksQuestionResultType.MATRIX) {
                                                    resultDTO.setChksMatrixColNm(questionResult.getChksMatrixColNm());
                                                    resultDTO.setChksMatrixRowNm(questionResult.getChksMatrixRowNm());
                                                    List<ChksQuestionResultMatrixDTO> matrices = chksQuestionResultMatrixDAO
                                                            .getMatrixData(questionResult.getId());
                                                    resultDTO.setChksQuestionResultMatrices(matrices);
                                                }
//                                            }
                                            return resultDTO;
                                        })
                                        .collect(Collectors.toList());
                                question.setChksQuestionResults(resultDTOS);
                            }


//                            System.out.println("questionDataFilesMap : " + questionDataFilesMap);
                            // Add question data files if they exist for this id
                            if (questionDataFilesMap.containsKey(question.getId())) {
                                List<ChksQuestionFileDTO> filesDTOs = questionDataFilesMap.get(question.getId())
                                        .stream()
                                        .map(file -> {
                                            ChksQuestionFileDTO fileDTO = new ChksQuestionFileDTO();
                                            fileDTO.setId(file.getId());
                                            fileDTO.setPath(file.getPath());
                                            // Only set URL for the first file
                                            if (questionDataFilesMap.get(question.getId()).indexOf(file) == 0) {
//                                                fileDTO.setUrl(awsS3Service.getDocs(file.getPath()).toString());
                                                fileDTO.setUrl(FileStorageUtil.getFileURL(file.getPath()));
                                            }
                                            return fileDTO;
                                        })
                                        .collect(Collectors.toList());
                                question.setChksQuestionDataFiles(filesDTOs);
                            }
                        }
                        newDto.setQuestions(questions);
                    }
                    return newDto;
                });

                // Maintain parent-child relationships
                if (parentId != null) {
                    childrenMap.computeIfAbsent(parentId, k -> new ArrayList<>()).add(dto);
                }
            }

            // Build hierarchy
            for (Map.Entry<Long, List<ChksHeaderDataDTO>> entry : childrenMap.entrySet()) {
                ChksHeaderDataDTO parent = dtoMap.get(entry.getKey());
                if (parent != null) {
                    parent.setChildren(entry.getValue());
                }
            }

            // Get root level items
            List<ChksHeaderDataDTO> rootItems = dtoMap.values().stream()
                .filter(dto -> dto.getChksHeaderDataId() == null)
                .sorted(Comparator.comparing(ChksHeaderDataDTO::getOrderNo,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ChksHeaderDataDTO::getId))
                .collect(Collectors.toList());

            ChksHeaderDTO chksHeaderDTO = new ChksHeaderDTO();
            chksHeaderDTO.setChecksheetId(checksheetId);
            ResponseDTO<?> chksHeaderData = chksHeaderService.getChksHeaderData(chksHeaderDTO);

            ChksGeneralFieldDTO chksGeneralFieldDTO = new ChksGeneralFieldDTO();
            chksGeneralFieldDTO.setChecksheetId(checksheetId);
            ResponseDTO<?> chksGeneralFieldData = chksGeneralFieldService.getChksGeneralFieldData(chksGeneralFieldDTO);

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("chksGeneralColumn", chksGeneralFieldData.getData());
            responseData.put("chksHeaderColumn", chksHeaderData.getData());
            responseData.put("chksContentData", rootItems);
            responseData.put("checksheetStatus", checksheet.get().getStatus());

            return new ResponseDTO<>(true, "Data fetched successfully", responseData);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching data: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private List<ChksQuestionResultDTO> convertToQuestionResultDTO(List<ChksQuestionResult> results) {
        List<ChksQuestionResultDTO> chksQuestionResultDTOS = new ArrayList<>();
        for(ChksQuestionResultDTO chksQuestionResultDTO : chksQuestionResultDTOS) {
            ChksQuestionResultDTO dto = new ChksQuestionResultDTO();
            dto.setId(chksQuestionResultDTO.getId());
            dto.setAnswerType(chksQuestionResultDTO.getAnswerType());
            dto.setUnit(chksQuestionResultDTO.getUnit());
            dto.setChksMatrixColName(chksQuestionResultDTO.getChksMatrixColName());
        }
        // Set other necessary fields based on your DTO structure
        return chksQuestionResultDTOS;
    }

    // Add these helper methods to safely convert types
    private Long convertToLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String convertToString(Object value) {
        return value != null ? value.toString() : null;
    }

    private Integer convertToInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> updateDescription(ChksHeaderDataDTO chksHeaderDataDTO) throws CustomException {
        try {
            if (Objects.isNull(chksHeaderDataDTO) || Objects.isNull(chksHeaderDataDTO.getId())) {
                throw new CustomException("Please provide id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<ChksHeaderData> chksHeaderDataById = chksHeaderDataRepository.findById(chksHeaderDataDTO.getId());
            ChksHeaderData chksHeaderData = new ChksHeaderData();
            if(chksHeaderDataById.isPresent()) {
                chksHeaderData = chksHeaderDataById.get();
            }
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            Optional<Checksheet> checksheet = checksheetRepository.findById(chksHeaderData.getChecksheet().getId());
            if(!Objects.isNull(checksheet.get().getPreparerUser()) &&
                    !Objects.equals(checksheet.get().getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You have not access of this checksheet", HttpStatus.FORBIDDEN);
            }
            chksHeaderData.setDescription(chksHeaderDataDTO.getDescription());
            if (currentUser.isPresent()) {
                chksHeaderData.setUpdatedBy(currentUser.get());
                chksHeaderData.setUpdatedAt(new Date());
            }
            chksHeaderData = chksHeaderDataRepository.save(chksHeaderData);

            // Create response map
            Map<String, Object> response = new HashMap<>();
            response.put("description", chksHeaderData.getDescription());
            response.put("id", chksHeaderData.getId());
            response.put("level", chksHeaderData.getLevel());

            return new ResponseDTO<>(true, "Description updated successfully", response);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error updating description: " + e.getMessage(), 
                HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> updateName(ChksHeaderDataDTO chksHeaderDataDTO) throws CustomException {
        try {
            if (Objects.isNull(chksHeaderDataDTO) || Objects.isNull(chksHeaderDataDTO.getId()) || Objects.isNull(chksHeaderDataDTO.getName()) ) {
                throw new CustomException("Please provide id, name", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<ChksHeaderData> chksHeaderDataById = chksHeaderDataRepository.findById(chksHeaderDataDTO.getId());
            ChksHeaderData chksHeaderData = new ChksHeaderData();
            if(chksHeaderDataById.isPresent()) {
                chksHeaderData = chksHeaderDataById.get();
            }
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            Optional<Checksheet> checksheet = checksheetRepository.findById(chksHeaderData.getChecksheet().getId());
            if(!Objects.isNull(checksheet.get().getPreparerUser()) &&
                    !Objects.equals(checksheet.get().getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You have not access of this checksheet", HttpStatus.FORBIDDEN);
            }
            chksHeaderData.setName(chksHeaderDataDTO.getName());
            if (currentUser.isPresent()) {
                chksHeaderData.setUpdatedBy(currentUser.get());
                chksHeaderData.setUpdatedAt(new Date());
            }
            chksHeaderDataRepository.save(chksHeaderData);
            return new ResponseDTO<>(true, "Name updated successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error updating description: " + e.getMessage(),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> resetChecksheetData(ChksHeaderDataDTO chksHeaderDataDTO) throws CustomException {
        try {
            if (Objects.isNull(chksHeaderDataDTO) || Objects.isNull(chksHeaderDataDTO.getChecksheetId())) {
                throw new CustomException("Please provide checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Long checksheetId = chksHeaderDataDTO.getChecksheetId();
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            Optional<Checksheet> checksheet = checksheetRepository.findById(checksheetId);

            if (!checksheet.isPresent()) {
                throw new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            if (Objects.isNull(checksheet.get().getPreparerUser()) ||
                    !Objects.equals(checksheet.get().getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You do not have access to this checksheet", HttpStatus.FORBIDDEN);
            }
            deleteChksData(checksheetId);
            return new ResponseDTO<>(true, "Data deleted successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error deleting data: " + e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void deleteChksData(Long checksheetId) throws CustomException {
        try {
            // Delete from chks_question_result_matrices
            chksQuestionResultMatrixRepository.deleteByChecksheet_Id(checksheetId);

            chksQuestionResultOptionRepository.deleteByChecksheet_Id(checksheetId);

            // Delete from chks_question_results
            chksQuestionResultRepository.deleteByChecksheet_Id(checksheetId);
            List<ChksQuestionFile> checkSheetFiles = chksQuestionFileRepository.findByChecksheet_IdOrderById(checksheetId);
            for (ChksQuestionFile chksQuestionFile : checkSheetFiles) {
                FileStorageUtil.deleteFile(chksQuestionFile.getPath());
//                chksQuestionFileRepository.delete(chksQuestionFile);
            }
            // Delete from chks_question_files
            chksQuestionFileRepository.deleteByChecksheet_Id(checksheetId);

            // Delete from chks_questions
            chksQuestionRepository.deleteByChecksheet_Id(checksheetId);

            // Delete from chks_header_data_files
            List<ChksHeaderDataFile> checksheetHeaderFiles = chksHeaderDataFileRepository.findByChecksheet_IdOrderById(checksheetId);
            for (ChksHeaderDataFile chksHeaderDataFile : checksheetHeaderFiles) {
                FileStorageUtil.deleteFile(chksHeaderDataFile.getPath());
//                chksHeaderDataFileRepository.delete(chksHeaderDataFile);
            }
            chksHeaderDataFileRepository.deleteByChecksheet_Id(checksheetId);

            // Delete from chks_header_data
            chksHeaderDataRepository.deleteByChecksheet_Id(checksheetId);
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error deleting data: " + e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> uploadFiles(ChksHeaderDataDTO chksHeaderDataDTO) throws CustomException {
        try {
            if(Objects.isNull(chksHeaderDataDTO) || Objects.isNull(chksHeaderDataDTO.getId()) ||
                Objects.isNull(chksHeaderDataDTO.getChksHeaderDataImages()) ||
                    chksHeaderDataDTO.getChksHeaderDataImages().isEmpty()) {
                throw new CustomException("Please provide id, chksHeaderDataImages", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            ChksHeaderData chksHeaderData = chksHeaderDataRepository.findById(chksHeaderDataDTO.getId())
                    .orElseThrow(() -> new CustomException("Id not found", HttpStatus.UNPROCESSABLE_ENTITY));
            Checksheet checksheet = chksHeaderData.getChecksheet();
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();

            if (Objects.isNull(checksheet.getPreparerUser()) ||
                    !Objects.equals(checksheet.getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You do not have access to this checksheet", HttpStatus.FORBIDDEN);
            }

            List<ChksHeaderDataFileDTO> uploadedFiles = new ArrayList<>();
            
            for(MultipartFile image : chksHeaderDataDTO.getChksHeaderDataImages()) {
                ChksHeaderDataFile chksHeaderDataFile = new ChksHeaderDataFile();
                String truncatedFilename = utilityService.getTruncatedFileName(image.getOriginalFilename(), 50);
                chksHeaderDataFile.setChksHeaderData(chksHeaderData);
                chksHeaderDataFile.setChecksheet(checksheet);
                chksHeaderDataFile.setCreatedBy(currentUser.get());
                chksHeaderDataFileRepository.save(chksHeaderDataFile);
//                String fileName = "ChecksheetHeaderData/" + chksHeaderDataFile.getId() + "_" + truncatedFilename;
//                awsS3Service.uploadMultipartFile(image, fileName);
                String fileName = FileStorageUtil.storeFile(image,"ChecksheetHeaderData/", chksHeaderDataFile.getId() + "_" + truncatedFilename);

                chksHeaderDataFile.setPath(fileName);
                chksHeaderDataFile = chksHeaderDataFileRepository.save(chksHeaderDataFile);

                // Create DTO for response
                ChksHeaderDataFileDTO fileDTO = new ChksHeaderDataFileDTO();
                fileDTO.setId(chksHeaderDataFile.getId());
                fileDTO.setPath(chksHeaderDataFile.getPath());
//                fileDTO.setUrl(awsS3Service.getDocs(chksHeaderDataFile.getPath()).toString());
                fileDTO.setUrl(FileStorageUtil.getFileURL(chksHeaderDataFile.getPath()));
                fileDTO.setChksHeaderId(chksHeaderDataDTO.getId());
                uploadedFiles.add(fileDTO);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("chksHeaderDataId", chksHeaderDataDTO.getId());
            response.put("files", uploadedFiles);

            return new ResponseDTO<>(true, "File uploaded successfully", response);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Some thing went wrong: " + e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> deleteFile(ChksHeaderDataFileDTO chksHeaderDataFileDTO) throws CustomException {
        try {
            if(Objects.isNull(chksHeaderDataFileDTO) || Objects.isNull(chksHeaderDataFileDTO.getId())) {
                throw new CustomException("Please provide id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            ChksHeaderDataFile chksHeaderDataFile = chksHeaderDataFileRepository.findById(chksHeaderDataFileDTO.getId())
                    .orElseThrow(() -> new CustomException("Id not found", HttpStatus.UNPROCESSABLE_ENTITY));
            Checksheet checksheet = chksHeaderDataFile.getChecksheet();
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();

            if (Objects.isNull(checksheet.getPreparerUser()) ||
                    !Objects.equals(checksheet.getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You do not have access to this checksheet", HttpStatus.FORBIDDEN);
            }
            if(!Objects.equals(ChecksheetStatusType.NOT_APPROVED, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.INVALIDATED, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.CREATE_TEMPLATE, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.CREATE_CONTENT, checksheet.getStatus())) {
                throw new CustomException("You can not delete file", HttpStatus.UNPROCESSABLE_ENTITY);
            }
//            Path path = Paths.get(chksHeaderDataFile.getPath());
//            String directoryPath = path.getParent() != null ? path.getParent().toString() : "";
//            String fileName = path.getFileName().toString();
//            FileStorageUtil.deleteFile(directoryPath,fileName);
            FileStorageUtil.deleteFile(chksHeaderDataFile.getPath());
//            awsS3Service.deleteFile(chksHeaderDataFile.getPath());
            chksHeaderDataFileRepository.delete(chksHeaderDataFile);
            return new ResponseDTO<>(true, "File deleted successfully");
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Some thing went wrong: " + e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

//    @Transactional(rollbackFor = Exception.class)
    public void updateFileUploadStatus(Checksheet checksheet, boolean status) throws CustomException {
        checksheet.setIsFileUpload(status);
        checksheetRepository.save(checksheet);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> createHierarchicalChecksheetData(ChksHeaderDataDTO rootHeaderDataDTO) throws CustomException {
        try {
            // Validate input
            if (Objects.isNull(rootHeaderDataDTO) || Objects.isNull(rootHeaderDataDTO.getChecksheetId())) {
                throw new CustomException("Please provide checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            // Get checksheet and validate access
            Long checksheetId = rootHeaderDataDTO.getChecksheetId();
            Optional<Checksheet> checksheetOpt = checksheetRepository.findById(checksheetId);
            if (checksheetOpt.isEmpty()) {
                throw new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            Checksheet checksheet = checksheetOpt.get();
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            
            if (Objects.isNull(checksheet.getPreparerUser()) ||
                    !Objects.equals(checksheet.getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You do not have access to this checksheet", HttpStatus.FORBIDDEN);
            }
            
            // Check checksheet status
            if (!Objects.equals(ChecksheetStatusType.CREATE_TEMPLATE, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.INVALIDATED, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.NOT_APPROVED, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.CREATE_CONTENT, checksheet.getStatus())) {
                throw new CustomException("You cannot create content for this checksheet in its current state", 
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            // Get all header definitions
            List<ChksHeader> dbHeaders = chksHeaderRepository.findByChecksheet_IdAndIsResultColumnFalseOrderById(checksheetId);
            if (dbHeaders.isEmpty()) {
                throw new CustomException("No headers found for this checksheet", HttpStatus.UNPROCESSABLE_ENTITY);
            } else if (dbHeaders.size() < 2) {
                throw new CustomException("Minimum 2 headers must have in checksheet", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            // Reset existing data
            ChksHeaderDataDTO tempDTO = new ChksHeaderDataDTO();
            tempDTO.setChecksheetId(checksheetId);
            resetChecksheetData(tempDTO);
            
            // Process hierarchical data
            processHierarchicalData(rootHeaderDataDTO, null, dbHeaders, checksheet, currentUser.get(), 1L);
            
            // Update checksheet status if needed
            if (Objects.equals(ChecksheetStatusType.CREATE_TEMPLATE, checksheet.getStatus()) ||
                    Objects.equals(ChecksheetStatusType.INVALIDATED, checksheet.getStatus()) ||
                    Objects.equals(ChecksheetStatusType.NOT_APPROVED, checksheet.getStatus())) {
                checksheet.setStatus(ChecksheetStatusType.CREATE_CONTENT);
                checksheetRepository.save(checksheet);
            }
            
            return new ResponseDTO<>(true, "Hierarchical data created successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error creating hierarchical data: " + e.getMessage(), 
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // Helper method to process hierarchical data recursively
    private void processHierarchicalData(ChksHeaderDataDTO dto, ChksHeaderData parent, 
                                        List<ChksHeader> dbHeaders, Checksheet checksheet, 
                                        User currentUser, Long level) throws CustomException {
        
        // Validate level against available headers
        if (level > dbHeaders.size()) {
            throw new CustomException("Level exceeds available headers count", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        
        // Validate inputs
        if (dto.getName() == null || dto.getName().trim().isEmpty()) {
            throw new CustomException("Name is required for all header data", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        
        // Get the correct header for this level
        ChksHeader header = dbHeaders.get(level.intValue() - 1);
        
        // Create the header data entity
        ChksHeaderData headerData = new ChksHeaderData();
        headerData.setChksHeader(header);
        headerData.setName(dto.getName());
        headerData.setDescription(dto.getDescription());
        headerData.setChecksheet(checksheet);
        headerData.setCreatedBy(currentUser);
        headerData.setLevel(level);
        
        // Handle order_no logic
        if (dto.getOrderNo() != null) {
            // If orderNo is provided in DTO, use it and update subsequent records
            headerData.setOrderNo(dto.getOrderNo());
            
            // We'll need to update other records with the same parent after saving
        } else {
            // If orderNo is not provided, calculate the next available number
            Integer maxOrderNo = 0;
            if (parent != null) {
                // Find max order_no for the same parent
                List<ChksHeaderData> siblings = chksHeaderDataRepository.findByChksHeaderDataId(parent.getId());
                if(Objects.nonNull(siblings)) {
                    maxOrderNo=siblings.size();
                }
            } else {
                // Find max order_no for the same level with no parent
                List<ChksHeaderData> rootItems = chksHeaderDataRepository.findByChecksheet_IdAndChecksheetChksHeaderDataIsNullOrderById(checksheet.getId());
                if(Objects.nonNull(rootItems)) {
                    maxOrderNo=rootItems.size();
                }
            }
            // If no records with the same chks_header_id exist, start from 1
            headerData.setOrderNo(maxOrderNo + 1);
        }
        
        // Set parent if applicable
        if (parent != null) {
            headerData.setChksHeaderData(parent);
        }
        
        // Save header data
        ChksHeaderData savedHeaderData = chksHeaderDataRepository.save(headerData);
        
        // If orderNo was explicitly set, we need to update subsequent records
        if (dto.getOrderNo() != null) {
            // Find all siblings with the same parent and checksheet that have orderNo >= the one we just set
            // and are not the record we just created/updated
            List<ChksHeaderData> siblingsToUpdate = new ArrayList<>();
            List<ChksHeaderData> allSiblings = new ArrayList<>();
            
            if (parent != null) {
                // Get siblings with the same parent
                allSiblings = chksHeaderDataRepository.findByChecksheet_IdAndChecksheetChksHeaderDataOrderById(checksheet.getId(), parent.getId());
            } else {
                // Get root level items
                allSiblings = chksHeaderDataRepository.findByChecksheet_IdAndChecksheetChksHeaderDataIsNullOrderById(checksheet.getId());
            }
            
            // Check if the provided orderNo is greater than the maximum existing orderNo
            Integer maxOrderNo = 0;
            for (ChksHeaderData sibling : allSiblings) {
                if (sibling.getId() != savedHeaderData.getId() && sibling.getOrderNo() != null && sibling.getOrderNo() > maxOrderNo) {
                    maxOrderNo = sibling.getOrderNo();
                }
            }
            
            // If orderNo is greater than max, set it to max+1
            if (savedHeaderData.getOrderNo() > maxOrderNo + 1) {
                savedHeaderData.setOrderNo(maxOrderNo + 1);
                chksHeaderDataRepository.save(savedHeaderData);
            }
            
            // Find siblings that need to be updated (orderNo >= savedHeaderData.getOrderNo)
            for (ChksHeaderData sibling : allSiblings) {
                if (sibling.getId() != savedHeaderData.getId() && 
                    sibling.getOrderNo() != null && 
                    sibling.getOrderNo() >= savedHeaderData.getOrderNo()) {
                    siblingsToUpdate.add(sibling);
                }
            }
            
            // Sort by orderNo to ensure we update in the correct sequence
            siblingsToUpdate.sort(Comparator.comparing(ChksHeaderData::getOrderNo, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ChksHeaderData::getId));
            
            // Update orderNo for each sibling, incrementing by 1
            int newOrderNo = savedHeaderData.getOrderNo() + 1;
            for (ChksHeaderData sibling : siblingsToUpdate) {
                sibling.setOrderNo(newOrderNo++);
                chksHeaderDataRepository.save(sibling);
            }
        }
        
        // Process children recursively if this is not the last level
        if (dto.getChildren() != null && !dto.getChildren().isEmpty() && level < dbHeaders.size() - 1) {
            for (ChksHeaderDataDTO child : dto.getChildren()) {
                processHierarchicalData(child, savedHeaderData, dbHeaders, checksheet, currentUser, level + 1);
            }
        }
        
        // Process questions if this is the last level 
        if (level == dbHeaders.size() - 1 && dto.getQuestions() != null && !dto.getQuestions().isEmpty()) {
            for (ChksQuestionDTO questionDTO : dto.getQuestions()) {
                // Validate question data
                if (questionDTO.getName() == null || questionDTO.getName().trim().isEmpty()) {
                    throw new CustomException("Question name is required", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                
                ChksQuestion question = new ChksQuestion();
                question.setName(questionDTO.getName());
                question.setDescription(questionDTO.getDescription());
                question.setChecksheet(checksheet);
                question.setCreatedBy(currentUser);
                question.setChksHeader(dbHeaders.get(dbHeaders.size() - 1)); // Last header for questions
                question.setChksHeaderData(savedHeaderData);
                chksQuestionRepository.save(question);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> createPartialHierarchicalData(ChksHeaderDataDTO headerDataDTO) throws CustomException {
        try {
            // Validate input
            if (Objects.isNull(headerDataDTO) || Objects.isNull(headerDataDTO.getChecksheetId())) {
                throw new CustomException("Please provide checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            // Get checksheet and validate access
            Long checksheetId = headerDataDTO.getChecksheetId();
            Optional<Checksheet> checksheetOpt = checksheetRepository.findById(checksheetId);
            if (checksheetOpt.isEmpty()) {
                throw new CustomException("Invalid checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            Checksheet checksheet = checksheetOpt.get();
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            
            if (Objects.isNull(checksheet.getPreparerUser()) ||
                    !Objects.equals(checksheet.getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You do not have access to this checksheet", HttpStatus.FORBIDDEN);
            }
            
            // Check checksheet status
            if (!Objects.equals(ChecksheetStatusType.CREATE_TEMPLATE, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.INVALIDATED, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.NOT_APPROVED, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.CREATE_CONTENT, checksheet.getStatus())) {
                throw new CustomException("You cannot create content for this checksheet in its current state", 
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            // Get all header definitions
            List<ChksHeader> dbHeaders = chksHeaderRepository.findByChecksheet_IdAndIsResultColumnFalseOrderById(checksheetId);
            if (dbHeaders.isEmpty()) {
                throw new CustomException("No headers found for this checksheet", HttpStatus.UNPROCESSABLE_ENTITY);
            } else if (dbHeaders.size() < 2) {
                throw new CustomException("Minimum 2 headers must have in checksheet", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            // Determine starting level
            Long startLevel;
            ChksHeaderData parent = null;
            
            // If parent ID is provided, find parent and determine level
            if (headerDataDTO.getChksHeaderDataId() != null) {
                Optional<ChksHeaderData> parentOpt = chksHeaderDataRepository.findById(headerDataDTO.getChksHeaderDataId());
                if (parentOpt.isEmpty()) {
                    throw new CustomException("Invalid parent ID", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                
                parent = parentOpt.get();
                
                // Verify parent belongs to same checksheet
                if (!parent.getChecksheet().getId().equals(checksheetId)) {
                    throw new CustomException("Parent belongs to a different checksheet", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                
                startLevel = parent.getLevel() + 1;
                
                // Validate level is within range
                if (startLevel > dbHeaders.size()) {
                    throw new CustomException("Parent level exceeds available headers", HttpStatus.UNPROCESSABLE_ENTITY);
                }
            } else {
                // Start from level 1 if no parent provided
                startLevel = 1L;
            }
            
            // Process data starting from the specified level/parent
            processPartialHierarchicalData(headerDataDTO, parent, dbHeaders, checksheet, currentUser.get(), startLevel);
            
            // Update checksheet status if needed
            if (Objects.equals(ChecksheetStatusType.CREATE_TEMPLATE, checksheet.getStatus()) ||
                    Objects.equals(ChecksheetStatusType.INVALIDATED, checksheet.getStatus()) ||
                    Objects.equals(ChecksheetStatusType.NOT_APPROVED, checksheet.getStatus())) {
                checksheet.setStatus(ChecksheetStatusType.CREATE_CONTENT);
                checksheetRepository.save(checksheet);
            }
            
            return new ResponseDTO<>(true, "Partial hierarchical data created successfully");
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error creating hierarchical data: " + e.getMessage(), 
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // Helper method to process partial hierarchical data recursively
    private void processPartialHierarchicalData(ChksHeaderDataDTO dto, ChksHeaderData parent, 
                                   List<ChksHeader> dbHeaders, Checksheet checksheet, 
                                   User currentUser, Long level) throws CustomException {
        
        // Validate level against available headers
        if (level > dbHeaders.size()) {
            throw new CustomException("Level exceeds available headers count", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        
        // Validate inputs
        if (dto.getName() == null || dto.getName().trim().isEmpty()) {
            throw new CustomException("Name is required for all header data", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        
        // Get the correct header for this level
        ChksHeader header = dbHeaders.get(level.intValue() - 1);
        
        // Create the header data entity
        ChksHeaderData headerData = new ChksHeaderData();
        headerData.setChksHeader(header);
        headerData.setName(dto.getName());
        headerData.setDescription(dto.getDescription());
        headerData.setChecksheet(checksheet);
        headerData.setCreatedBy(currentUser);
        headerData.setLevel(level);
        
        // Handle order_no logic
        if (dto.getOrderNo() != null) {
            // If orderNo is provided in DTO, use it and update subsequent records
            headerData.setOrderNo(dto.getOrderNo());
            
            // We'll need to update other records with the same parent after saving
        } else {
            // If orderNo is not provided, calculate the next available number
            Integer maxOrderNo = 0;
//            boolean foundMatchingHeader = false;
            if (parent != null) {
                // Find max order_no for the same parent
                List<ChksHeaderData> siblings = chksHeaderDataRepository.findByChksHeaderDataId(parent.getId());
                if(Objects.nonNull(siblings)) {
                    maxOrderNo=siblings.size();
                }
            } else {
                // Find max order_no for the same level with no parent
                List<ChksHeaderData> rootItems = chksHeaderDataRepository.findByChecksheet_IdAndChecksheetChksHeaderDataIsNullOrderById(checksheet.getId());
                if(Objects.nonNull(rootItems)) {
                    maxOrderNo=rootItems.size();
                }
            }
            // If no records with the same chks_header_id exist, start from 1
            headerData.setOrderNo(maxOrderNo + 1);
        }
        
        // Set parent if applicable
        if (parent != null) {
            headerData.setChksHeaderData(parent);
        }
        
        // Save header data
        ChksHeaderData savedHeaderData = chksHeaderDataRepository.save(headerData);
        
        // If orderNo was explicitly set, we need to update subsequent records
        if (dto.getOrderNo() != null) {
            // Find all siblings with the same parent and checksheet that have orderNo >= the one we just set
            // and are not the record we just created/updated
            List<ChksHeaderData> siblingsToUpdate = new ArrayList<>();
            List<ChksHeaderData> allSiblings = new ArrayList<>();
            
            if (parent != null) {
                // Get siblings with the same parent
                allSiblings = chksHeaderDataRepository.findByChksHeaderDataId(parent.getId());
            } else {
                // Get root level items
                allSiblings = chksHeaderDataRepository.findByChecksheet_IdAndChecksheetChksHeaderDataIsNullOrderById(checksheet.getId());
            }
            
            // Check if the provided orderNo is greater than the maximum existing orderNo
            Integer maxOrderNo = 0;
            for (ChksHeaderData sibling : allSiblings) {
                if (sibling.getId() != savedHeaderData.getId() && sibling.getOrderNo() != null && sibling.getOrderNo() > maxOrderNo) {
                    maxOrderNo = sibling.getOrderNo();
                }
            }
            
            // If orderNo is greater than max, set it to max+1
            if (savedHeaderData.getOrderNo() > maxOrderNo + 1) {
                savedHeaderData.setOrderNo(maxOrderNo + 1);
                chksHeaderDataRepository.save(savedHeaderData);
            }
            
            // Find siblings that need to be updated (orderNo >= savedHeaderData.getOrderNo)
            for (ChksHeaderData sibling : allSiblings) {
                if (sibling.getId() != savedHeaderData.getId() && 
                    sibling.getOrderNo() != null && 
                    sibling.getOrderNo() >= savedHeaderData.getOrderNo()) {
                    siblingsToUpdate.add(sibling);
                }
            }
            
            // Sort by orderNo to ensure we update in the correct sequence
            siblingsToUpdate.sort(Comparator.comparing(ChksHeaderData::getOrderNo, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ChksHeaderData::getId));
            
            // Update orderNo for each sibling, incrementing by 1
            int newOrderNo = savedHeaderData.getOrderNo() + 1;
            for (ChksHeaderData sibling : siblingsToUpdate) {
                sibling.setOrderNo(newOrderNo++);
                chksHeaderDataRepository.save(sibling);
            }
        }
        
        // Process children recursively if this is not the last level
        if (level < dbHeaders.size() - 1) {
            if (dto.getChildren() == null || dto.getChildren().isEmpty()) {
                throw new CustomException("Children data is required for non-leaf level " + level, HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            for (ChksHeaderDataDTO child : dto.getChildren()) {
                processPartialHierarchicalData(child, savedHeaderData, dbHeaders, checksheet, currentUser, level + 1);
            }
        }
        // When at the last header level, questions are required
        else if (level == dbHeaders.size() - 1) {
            if (dto.getQuestions() == null || dto.getQuestions().isEmpty()) {
                throw new CustomException("Questions are required at the last level", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Integer questionOrderNo = 1;
            for (ChksQuestionDTO questionDTO : dto.getQuestions()) {
                // Validate question data
                if (questionDTO.getName() == null || questionDTO.getName().trim().isEmpty()) {
                    throw new CustomException("Question name is required", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                
                ChksQuestion question = new ChksQuestion();
                question.setName(questionDTO.getName());
                question.setDescription(questionDTO.getDescription());
                question.setChecksheet(checksheet);
                question.setCreatedBy(currentUser);
                question.setChksHeader(dbHeaders.get(dbHeaders.size() - 1)); // Last header for questions
                question.setChksHeaderData(savedHeaderData);
                question.setOrderNo(questionOrderNo++);
                chksQuestionRepository.save(question);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public ResponseDTO<?> deleteChildHeaderData(ChksHeaderDataDTO chksHeaderDataDTO) throws CustomException {
        try {
            if (Objects.isNull(chksHeaderDataDTO) || Objects.isNull(chksHeaderDataDTO.getChecksheetId())
                    || Objects.isNull(chksHeaderDataDTO.getChksChildHeaderDataIds()) 
                    || chksHeaderDataDTO.getChksChildHeaderDataIds().isEmpty()) {
                throw new CustomException("Please provide checksheetId and child ids to delete", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            List<Long> childIds = chksHeaderDataDTO.getChksChildHeaderDataIds();

            if(!Objects.isNull(chksHeaderDataDTO.getChksHeaderDataId())) {
                Optional<ChksHeaderData> parentOpt = chksHeaderDataRepository.findById(chksHeaderDataDTO.getChksHeaderDataId());
                if (parentOpt.isEmpty()) {
                    throw new CustomException("Invalid parent header data id", HttpStatus.UNPROCESSABLE_ENTITY);
                }
            }

            Optional<Checksheet> tempChecksheet = checksheetRepository.findById(chksHeaderDataDTO.getChecksheetId());
            if(!tempChecksheet.isPresent()) {
               throw new CustomException("Invalid checksheet id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            Checksheet checksheet = tempChecksheet.get();
            
            // Verify user has access
            if (Objects.isNull(checksheet.getPreparerUser()) ||
                    !Objects.equals(checksheet.getPreparerUser().getId(), currentUser.get().getId())) {
                throw new CustomException("You do not have access to this checksheet", HttpStatus.FORBIDDEN);
            }
            
            // Check if checksheet status allows deletion
            if (!Objects.equals(ChecksheetStatusType.CREATE_TEMPLATE, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.INVALIDATED, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.NOT_APPROVED, checksheet.getStatus()) &&
                    !Objects.equals(ChecksheetStatusType.CREATE_CONTENT, checksheet.getStatus())) {
                throw new CustomException("You cannot delete content for this checksheet in its current state", 
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }

            if(!Objects.isNull(chksHeaderDataDTO.getChksHeaderDataId())) {
                Long parentId = chksHeaderDataDTO.getChksHeaderDataId();
                // Get total child count and verify there will be at least one remaining after deletion
                long totalChildren = chksHeaderDataRepository.countChildrenByParentId(parentId);
                if (totalChildren <= childIds.size()) {
                    throw new CustomException("At least one header data must remain under the parent",
                            HttpStatus.UNPROCESSABLE_ENTITY);
                }

                // Verify all child ids belong to this parent
                List<ChksHeaderData> childrenToDelete = chksHeaderDataRepository.findByChksHeaderDataId(parentId);
                List<Long> validChildIds = childrenToDelete.stream()
                        .map(ChksHeaderData::getId)
                        .filter(childIds::contains)
                        .collect(Collectors.toList());


                if (validChildIds.size() != childIds.size()) {
                    throw new CustomException("Some of the provided child ids do not belong to the parent",
                            HttpStatus.UNPROCESSABLE_ENTITY);
                }
            }
            
            // Process each child to delete all related data
            for (Long childId : chksHeaderDataDTO.getChksChildHeaderDataIds()) {
                    deleteNestedHeaderData(childId, checksheet.getId());
            }
            
            // After deleting header data, update the orderNo of remaining siblings
            Long parentId = chksHeaderDataDTO.getChksHeaderDataId();

            // Get remaining header data items under the same parent
            List<ChksHeaderData> remainingHeaderData = new ArrayList<>();
            if(Objects.isNull(chksHeaderDataDTO.getChksHeaderDataId())) {
                remainingHeaderData = chksHeaderDataRepository.findByChecksheet_IdAndChecksheetChksHeaderDataIsNullOrderById(checksheet.getId());
            } else {
                remainingHeaderData = chksHeaderDataRepository.findByChksHeaderDataId(parentId);
            }
            // If we have remaining header data items, update their orderNo values
            if (!remainingHeaderData.isEmpty()) {
                // Sort remaining header data by orderNo
                remainingHeaderData.sort(Comparator.comparing(ChksHeaderData::getOrderNo,
                    Comparator.nullsLast(Comparator.naturalOrder())));

                // Update orderNo for remaining header data
                int newOrderNo = 1;
                for (ChksHeaderData remainingItem : remainingHeaderData) {
                    // Skip items with null orderNo
                    if (remainingItem.getOrderNo() == null) {
                        continue;
                    }

                    // Update orderNo if it's different
                    if (remainingItem.getOrderNo() != newOrderNo) {
                        remainingItem.setOrderNo(newOrderNo);
                        chksHeaderDataRepository.save(remainingItem);
                    }

                    newOrderNo++;
                }
            }
            
            return new ResponseDTO<>(true, "Checksheet header data deleted successfully");
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error deleting header data: " + e.getMessage(), 
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void deleteNestedHeaderData(Long headerDataId, Long checksheetId) throws CustomException {
        try {
            // Get the header data to be deleted to find its parent
            Optional<ChksHeaderData> headerDataToDelete = chksHeaderDataRepository.findById(headerDataId);
            Long parentId = null;
            if (headerDataToDelete.isPresent() && headerDataToDelete.get().getChksHeaderData() != null) {
                parentId = headerDataToDelete.get().getChksHeaderData().getId();
            }
            
            // Get all children of this header data
            List<ChksHeaderData> children = chksHeaderDataRepository.findByChksHeaderDataId(headerDataId);
            
            // Recursively delete all children first
            for (ChksHeaderData child : children) {
                deleteNestedHeaderData(child.getId(), checksheetId);
            }
            
            // Delete questions associated with this header data
            List<ChksQuestion> questions = chksQuestionRepository.findByChksHeaderData_Id(headerDataId);
            for (ChksQuestion question : questions) {
                
                // Delete question results
                List<ChksQuestionResult> results = chksQuestionResultRepository.findByChksQuestion_IdOrderById(question.getId());
                for (ChksQuestionResult result : results) {
                    chksQuestionResultMatrixRepository.deleteByChksQuestionResult_Id(result.getId());
                    chksQuestionResultOptionRepository.deleteByChksQuestionResult_Id(result.getId());
                    chksQuestionResultRepository.delete(result);
                }
                
                // Delete question files
                List<ChksQuestionFile> files = chksQuestionFileRepository.findByChksQuestion_IdOrderById(question.getId());
                for (ChksQuestionFile file : files) {
                    FileStorageUtil.deleteFile(file.getPath());
                    chksQuestionFileRepository.delete(file);
                }
                
                // Delete the question
                chksQuestionRepository.delete(question);
            }
            
            // Delete header data files
            List<ChksHeaderDataFile> files = chksHeaderDataFileRepository.findByChksHeaderData_IdOrderById(headerDataId);
            for (ChksHeaderDataFile file : files) {
                FileStorageUtil.deleteFile(file.getPath());
                chksHeaderDataFileRepository.delete(file);
            }
            
            // Finally delete the header data itself
            chksHeaderDataRepository.deleteById(headerDataId);
            
            // After deletion, update the orderNo of remaining siblings if we have a parent ID
            if (parentId != null) {
                System.out.println("Updating orderNo values for remaining header data under parent ID: " + parentId);
                
                // Get remaining header data items under the same parent
                List<ChksHeaderData> remainingHeaderData = chksHeaderDataRepository.findByChksHeaderDataId(parentId);
                
                // If we have remaining header data items, update their orderNo values
                if (!remainingHeaderData.isEmpty()) {
                    // Sort remaining header data by orderNo
                    remainingHeaderData.sort(Comparator.comparing(ChksHeaderData::getOrderNo, 
                        Comparator.nullsLast(Comparator.naturalOrder())));
                    
                    // Update orderNo for remaining header data
                    int newOrderNo = 1;
                    for (ChksHeaderData remainingItem : remainingHeaderData) {
                        // Skip items with null orderNo
                        if (remainingItem.getOrderNo() == null) {
                            continue;
                        }
                        
                        // Update orderNo if it's different
                        if (remainingItem.getOrderNo() != newOrderNo) {
                            System.out.println("Updating header data ID " + remainingItem.getId() + 
                                             " from orderNo " + remainingItem.getOrderNo() + 
                                             " to " + newOrderNo);
                            remainingItem.setOrderNo(newOrderNo);
                            chksHeaderDataRepository.save(remainingItem);
                        }
                        
                        newOrderNo++;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error deleting nested header data: " + e.getMessage(), 
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ─── Helper methods for Excel template upload with answer options ───

    private static String getCellSafe(Row row, int colIndex, UtilityService utilityService) {
        if (row == null) return "";
        Cell cell = row.getCell(colIndex);
        if (cell == null) return "";
        return utilityService.getCellValueAsString(cell).trim();
    }

    private static ChksQuestionResultType parseAnswerType(String answerType) {
        String normalized = answerType.toUpperCase().replace(" ", "_");
        return switch (normalized) {
            case "SUBJECTIVE_CONDITION", "SUBJECTIVE_COND", "SC" -> ChksQuestionResultType.SUBJECTIVE_CONDITION;
            case "OBJECTIVE", "OBJ" -> ChksQuestionResultType.OBJECTIVE;
            case "SUBJECTIVE", "SUB" -> ChksQuestionResultType.SUBJECTIVE;
            case "MATRIX", "MAT" -> ChksQuestionResultType.MATRIX;
            case "NA", "NOT_APPLICABLE" -> ChksQuestionResultType.NA;
            default -> ChksQuestionResultType.SUBJECTIVE_CONDITION;
        };
    }

    private static ChksQuestionResultObjectiveType parseObjectiveType(String objectiveType) {
        if (objectiveType == null || objectiveType.isEmpty()) {
            return ChksQuestionResultObjectiveType.EQUAL_TO;
        }
        String normalized = objectiveType.toUpperCase().replace(" ", "_");
        return switch (normalized) {
            case "EQUAL_TO", "EQUAL", "EQ", "=" -> ChksQuestionResultObjectiveType.EQUAL_TO;
            case "GREATER_THAN", "GT", ">" -> ChksQuestionResultObjectiveType.GREATER_THAN;
            case "GREATER_THAN_OR_EQUAL_TO", "GTE", ">=" -> ChksQuestionResultObjectiveType.GREATER_THAN_OR_EQUAL_TO;
            case "LESS_THAN", "LT", "<" -> ChksQuestionResultObjectiveType.LESS_THAN;
            case "LESS_THAN_OR_EQUAL_TO", "LTE", "<=" -> ChksQuestionResultObjectiveType.LESS_THAN_OR_EQUAL_TO;
            case "RANGE" -> ChksQuestionResultObjectiveType.RANGE;
            default -> ChksQuestionResultObjectiveType.EQUAL_TO;
        };
    }

    private void saveOption(ChksQuestionResult questionResult, Checksheet checksheet,
                            String optionText, String judgement, User createdBy) {
        com.checkSheet.entity.ChksQuestionResultOption option = new com.checkSheet.entity.ChksQuestionResultOption();
        option.setChksQuestionResult(questionResult);
        option.setChecksheet(checksheet);
        option.setOption(optionText);
        option.setJudgement(judgement);
        option.setCreatedBy(createdBy);
        chksQuestionResultOptionRepository.save(option);
    }
}
