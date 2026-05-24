package com.checkSheet.service;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

import com.checkSheet.DAO.ChksHeaderDAO;
import com.checkSheet.DAO.LovDataDAO;
import com.checkSheet.DTO.*;
import com.checkSheet.constant.ChecksheetFrequencyType;
import com.checkSheet.constant.ChksQuestionResultObjectiveType;
import com.checkSheet.entity.*;
import com.checkSheet.helper.DateHelper;
import com.checkSheet.repository.*;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.checkSheet.DAO.ChksQuestionResultDAO;
import com.checkSheet.DAO.DashboardDAO;
import com.checkSheet.DAO.NpdMasterDAO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.constant.ChksQuestionResultType;
import com.checkSheet.exception.CustomException;

@Service
public class DashboardServiceImpl implements DashboardService {

    @Autowired
    private DashboardDAO dashboardDAO;

    @Autowired
    private ChksQuestionResultDAO chksQuestionResultDAO;
    @Autowired
    private ChksHeaderDAO chksHeaderDAO;

    @Autowired
    private UserChecksheetService userChecksheetService;
    @Autowired
    private UserChecksheetApprovalService userChecksheetApprovalService;
    @Autowired
    private UserChecksheetAnswerRepository userChecksheetAnswerRepository;
    @Autowired
    private ChksQuestionResultOptionRepository chksQuestionResultOptionRepository;
    @Autowired
    private UserRoleDepartmentRepository userRoleDepartmentRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private ChksQuestionRepository chksQuestionRepository;
    @Autowired
    private ChksQuestionResultRepository chksQuestionResultRepository;
    @Autowired
    private ChksQuestionResultMatrixRepository chksQuestionResultMatrixRepository;
    @Autowired
    private UserChecksheetMatrixAnswersRepository userChecksheetMatrixAnswersRepository;
    @Autowired
    private ChksHeaderRepository chksHeaderRepository;

    @Autowired
    private UserChecksheetTraceValueRepository userChecksheetTraceValueRepository;

    @Autowired
    private UtilityService utilityService;

    @Autowired
    private LovDataDAO lovDataDAO;
    @Autowired
    private NpdMasterDAO npdMasterDAO;
    
    @Autowired
    private PermissionService permissionService;
    
    @Override
    public ResponseDTO<?> getRespectedChecksheet(DashboardDTO dashboardDTO) throws CustomException {
        try {
            User loginUser = utilityService.getCurrentLoggedInUser().orElseThrow(() -> new CustomException("Unauthorized user."));

            // Check user is Department admin or not -- start
            List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(loginUser.getId());
            if(Objects.equals(userRoleByUserIdDepartment, null) || userRoleByUserIdDepartment.isEmpty()) {
                throw new CustomException("Invalid role", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            // Use permission-based scope filtering (null = global access, empty = no access)
            List<Long> sectionIds = permissionService.getAllowedSectionIds(loginUser.getId());
            if (sectionIds != null && sectionIds.isEmpty()) {
                throw new CustomException("You have no permissions assigned. Please contact your administrator.", HttpStatus.FORBIDDEN);
            }
            List<DashboardDTO> cheatsheets = dashboardDAO.getRespectedChecksheet(dashboardDTO, sectionIds, loginUser);
            return new ResponseDTO<>(true, "Data fetched successfully", cheatsheets);
        } catch (Exception e) {
//            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ResponseDTO<?> getRespectedQuestions(DashboardDTO dashboardDTO) throws CustomException {
        try {
            if (Objects.isNull(dashboardDTO) || 
                Objects.isNull(dashboardDTO.getChecksheetIds()) || 
                dashboardDTO.getChecksheetIds().isEmpty()) {
                throw new CustomException("Please provide checksheet IDs", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            List<DashboardDTO> questions = dashboardDAO.getRespectedQuestions(dashboardDTO.getChecksheetIds());
            return new ResponseDTO<>(true, "Questions fetched successfully", questions);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ResponseDTO<?> getChecksheetSummaryData(DashboardDTO dashboardDTO) throws CustomException {
        try {
            if (Objects.isNull(dashboardDTO.getQuestionIds()) || dashboardDTO.getQuestionIds().isEmpty()) {
                throw new CustomException("Please provide question IDs", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            if (Objects.isNull(dashboardDTO.getStartDate()) || Objects.isNull(dashboardDTO.getEndDate())) {
                throw new CustomException("Please provide valid date range", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            if (Objects.isNull(dashboardDTO.getFrequencyOfCheck()) || dashboardDTO.getFrequencyOfCheck().trim().isEmpty()) {
                throw new CustomException("Please provide frequency of check", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            List<DashboardDTO> summaryData = dashboardDAO.getChecksheetSummaryData(dashboardDTO);
            return new ResponseDTO<>(true, "Summary data fetched successfully", summaryData);
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ResponseDTO<?> getPlanVsActualData(DashboardDTO dashboardDTO) throws CustomException {
        try {
            // Validation
            if (Objects.isNull(dashboardDTO.getStartDate()) || 
                Objects.isNull(dashboardDTO.getEndDate()) ||
                Objects.isNull(dashboardDTO.getFrequencyOfCheck()) ||
                Objects.isNull(dashboardDTO.getChecksheetIds()) ||
                dashboardDTO.getChecksheetIds().isEmpty()) {
                throw new CustomException("Please provide all required parameters", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            List<PlanVsActualDTO> result = new ArrayList<>();

            List<Object[]> rawData = dashboardDAO.getPlanVsActualData(
                dashboardDTO.getChecksheetIds(),
                dashboardDTO.getStartDate(),
                dashboardDTO.getEndDate()
            );

            // Process data by checksheet
            Map<Long, Map<String, List<Object[]>>> checksheetData = new HashMap<>();
            Map<Long, String> chksNames = new HashMap<>();
            Map<Long, String> chksFrequencyOfChecks = new HashMap<>();
            Map<Long, Short> chksFreqOfFreqCnts = new HashMap<>();
            for (Object[] row : rawData) {
                Long checksheetId = ((Number) row[0]).longValue();
                String checksheetName = (String) row[1];
                chksNames.put(checksheetId,checksheetName);
                chksFreqOfFreqCnts.put(checksheetId,(Short)row[5]);
                chksFrequencyOfChecks.put(checksheetId, (String) row[6]);
                Date checkDate = (Date) row[2];

                // Group by checksheetId
                Map<String, List<Object[]>> dateGroupedMap = checksheetData.computeIfAbsent(checksheetId, k -> new HashMap<>());

                // Group by date within that checksheetId
                if (checkDate != null) {
                    dateGroupedMap.computeIfAbsent(checkDate.toString(), k -> new ArrayList<>()).add(row);
                } else {
                    // Handle null checkDate case - use a default key or skip this row
                    dateGroupedMap.computeIfAbsent("Unknown Date", k -> new ArrayList<>()).add(row);
                }
            }

            // Create response for each checksheet
            for (Map.Entry<Long, Map<String, List<Object[]>>> entry : checksheetData.entrySet()) {
                PlanVsActualDTO dto = new PlanVsActualDTO();
                Short chksFreqOfFreqCnt = chksFreqOfFreqCnts.get(entry.getKey());
                String chksFrequencyOfCheck = chksFrequencyOfChecks.get(entry.getKey());
                dto.setChecksheetId(entry.getKey());
                dto.setChecksheetName(chksNames.get(entry.getKey()));

                // Generate chart data
                List<PlanVsActualDTO.DayData> chartData = generateChartData(
                    dashboardDTO.getStartDate(),
                    dashboardDTO.getEndDate(),
                    entry.getValue(),
                    chksFreqOfFreqCnt,
                    chksFrequencyOfCheck,
                    entry.getKey()
                );

                // Calculate counts
                int planned = 0, completed = 0, inProgress = 0, missed = 0, inComplete = 0, npdCnt = 0;
                for (PlanVsActualDTO.DayData day : chartData) {
                    if (day.getStatus() != null) {
                        switch (day.getStatus()) {
                            case "Completed" -> completed++;
                            case "In Progress" -> inProgress++;
                            case "Missed" -> missed++;
                            case "Incomplete" -> inComplete++;
                            case "NPD" -> npdCnt++;
                        }
                        planned++;
                    } else if (day.getShiftData() != null) {
                        for(PlanVsActualDTO.ShiftData shiftData : day.getShiftData()){
                            switch (shiftData.getStatus()) {
                                case "Completed" -> completed++;
                                case "In Progress" -> inProgress++;
                                case "Missed" -> missed++;
                                case "Incomplete" -> inComplete++;
                                case "NPD" -> npdCnt++;
                            }
                            planned++;
                        }
                    }
                }
                dto.setPlanned(planned);
                dto.setCompleted(completed);
                dto.setInProgress(inProgress);
                dto.setMissed(missed);
                dto.setInComplete(inComplete);
                dto.setNpd(npdCnt);
                dto.setChartData(chartData);

                result.add(dto);
            }

            return new ResponseDTO<>(true, "Data fetched successfully", result);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private List<PlanVsActualDTO.DayData> generateChartData(
        Date startDate,
        Date endDate,
        Map<String, List<Object[]>> checksheetData,
        Short chksFreqOfFreqCnt,
        String chksFrequencyOfCheck,
        Long checksheetId
    ) {
        List<PlanVsActualDTO.DayData> dayDataList = new ArrayList<>();
        // Preload NPD map for this checksheet and date range
        List<NpdMaster> npdList = npdMasterDAO.findByChecksheetIdAndDateRange(checksheetId, startDate, endDate);
        Map<String, Set<String>> npdByDateToShifts = new HashMap<>();
        Map<String, String> npdRemarksByDateShift = new HashMap<>();
        Map<String, String> npdRemarksByDateNonShift = new HashMap<>();
        npdList.forEach(npd -> {
            String d = DateHelper.getDateToStringFormat(npd.getNpdDate(), "yyyy-MM-dd");
            npdByDateToShifts.computeIfAbsent(d, k -> new HashSet<>());
            String shift = npd.getShift();
            String keyShift = shift == null ? "__NULL_SHIFT__" : shift;
            npdByDateToShifts.get(d).add(keyShift);
            npdRemarksByDateShift.put(d + "|" + keyShift, npd.getRemarks());
            if ("__NULL_SHIFT__".equals(keyShift)) {
                npdRemarksByDateNonShift.put(d, npd.getRemarks());
            }
        });
        
        // helper lambdas for lookup
        java.util.function.BiFunction<String, String, Boolean> isNpdShift = (dateStr, shift) -> {
            Set<String> shifts = npdByDateToShifts.get(dateStr);
            if (shifts == null) return false;
            return shifts.contains(shift);
        };
        java.util.function.BiFunction<String, String, String> getNpdRemarks = (dateStr, shift) -> npdRemarksByDateShift.get(dateStr + "|" + shift);
        java.util.function.Function<String, Boolean> isNpdNonShift = (dateStr) -> {
            Set<String> shifts = npdByDateToShifts.get(dateStr);
            if (shifts == null) return false;
            return shifts.contains("__NULL_SHIFT__");
        };
        java.util.function.Function<String, String> getNpdRemarksNonShift = (dateStr) -> npdRemarksByDateNonShift.get(dateStr);
        Calendar start = Calendar.getInstance();
        start.setTime(startDate);
        
        Calendar end = Calendar.getInstance();
        end.setTime(endDate);
        
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        // Get shifts if needed
        Set<String> shifts = new LinkedHashSet<>();
        if(chksFrequencyOfCheck.equals(ChecksheetFrequencyType.SHIFT.value)) {
            LovDataDTO shiftsLov = lovDataDAO.getLovData("shifts");
            if (shiftsLov != null && Objects.equals(shiftsLov.getValueType(), "JSON_Array")) {
                String raw = shiftsLov.getValue();
                if (raw != null) {
                    String cleaned = raw.replaceAll("[\\[\\]\"]", "");
                    for (String s : cleaned.split(",")) {
                        String val = s.trim();
                        if (!val.isEmpty()) shifts.add(val);
                    }
                }
            }
        }
        while (!start.after(end)) {
            PlanVsActualDTO.DayData day = new PlanVsActualDTO.DayData();
            day.setDate(dateFormat.format(start.getTime()));
            
            // Get status from checksheet data
            String dateKey = dateFormat.format(start.getTime());
            List<Object[]> dayData = checksheetData.get(dateKey);
            if (dayData != null) {
                Date implementationDate = (Date) dayData.get(0)[4];
                
                if (implementationDate != null && start.getTime().before(implementationDate)) {
                    day.setStatus(null);
                } else {
//                    String status = (String) dayData[3];
                    Set<String> status = dayData.stream()
                        .map(d -> (String) d[3])
                        .collect(Collectors.toSet());
                    if(chksFrequencyOfCheck.equals(ChecksheetFrequencyType.SHIFT.value)){
                        Map<String, List<Object[]>> grouped = dayData.stream()
                            .filter(arr -> arr.length > 7) // safeguard against IndexOutOfBounds
                            .collect(Collectors.groupingBy(arr -> (String)arr[7]));
                        List<PlanVsActualDTO.ShiftData> shiftData = new ArrayList<>();
                        shifts.forEach((shift) -> {
                            PlanVsActualDTO.ShiftData shiftDetails = new PlanVsActualDTO.ShiftData();
                            shiftDetails.setShift(shift);
                            if(grouped.containsKey(shift)){
                                Set<String> shiftStatus = grouped.get(shift).stream().map(d -> (String) d[3]).collect(Collectors.toSet());
                                if (shiftStatus.contains("APPROVED")) {
                                    if (shiftStatus.size() >= chksFreqOfFreqCnt) {
                                        shiftDetails.setStatus("Completed");
                                    } else {
                                        shiftDetails.setStatus("Incomplete");
                                    }
                                } else if (Set.of("SUBMITTED", "VALIDATED", "IN_PROGRESS", "DECLINED").stream().anyMatch(shiftStatus::contains)) {
                                    shiftDetails.setStatus("In Progress");
                                } else {
                                    // If no meaningful status, check NPD for this shift/date (preloaded)
                                    if (isNpdShift.apply(dateKey, shift)) {
                                        shiftDetails.setStatus("NPD");
                                        shiftDetails.setRemarks(getNpdRemarks.apply(dateKey, shift));
                                    } else {
                                        shiftDetails.setStatus("Missed");
                                    }
                                }
                            }else{
                                // No user data: check NPD for this shift/date (preloaded)
                                if (isNpdShift.apply(dateKey, shift)) {
                                    shiftDetails.setStatus("NPD");
                                    shiftDetails.setRemarks(getNpdRemarks.apply(dateKey, shift));
                                } else {
                                    shiftDetails.setStatus("Missed");
                                }
                            }
                            shiftData.add(shiftDetails);
                        });
                        day.setShiftData(shiftData);
                    }else {
                        if (status.contains("APPROVED")) {
                            if (status.size() >= chksFreqOfFreqCnt) {
                                day.setStatus("Completed");
                            } else {
                                day.setStatus("Incomplete");
                            }
                        } else if (Set.of("SUBMITTED", "VALIDATED", "IN_PROGRESS", "DECLINED").stream().anyMatch(status::contains)) {
                            day.setStatus("In Progress");
                        } else {
                            // No user status: check NPD for this date (non-shift) using preloaded map
                            if (isNpdNonShift.apply(dateKey)) {
                                day.setStatus("NPD");
                                day.setRemarks(getNpdRemarksNonShift.apply(dateKey));
                            } else {
                                day.setStatus("Missed");
                            }
                        }
                    }
                }
            } else {
                // Check implementation date from first available data
                List<Object[]> firstData = checksheetData.values().iterator().next();
                Date implementationDate = (Date) firstData.get(0)[4];
//                System.out.println("implementationDate : " + implementationDate);
//                System.out.println("start : " + start.getTime());
                Calendar currentDate = Calendar.getInstance();
                currentDate.set(Calendar.HOUR_OF_DAY, 0);
                currentDate.set(Calendar.MINUTE, 0);
                currentDate.set(Calendar.SECOND, 0);
                currentDate.set(Calendar.MILLISECOND, 0);

                if (implementationDate != null && start.getTime().before(implementationDate)) {
                    day.setStatus(null);
                } else if (start.getTime().after(currentDate.getTime())) {
                    if(chksFrequencyOfCheck.equals(ChecksheetFrequencyType.SHIFT.value)) {
                        List<PlanVsActualDTO.ShiftData> shiftData = new ArrayList<>();
                        shifts.forEach((shift) -> {
                            PlanVsActualDTO.ShiftData shiftDetails = new PlanVsActualDTO.ShiftData();
                            shiftDetails.setShift(shift);
                            // Future date: if NPD already declared for this shift/date, show NPD; otherwise Planned
                            if (isNpdShift.apply(dateKey, shift)) {
                                shiftDetails.setStatus("NPD");
                                shiftDetails.setRemarks(getNpdRemarks.apply(dateKey, shift));
                            } else {
                                shiftDetails.setStatus("Planned");
                            }
                            shiftData.add(shiftDetails);
                        });
                        day.setShiftData(shiftData);
                    }else{
                        // Future date: if NPD already declared, show NPD; otherwise Planned
                        if (isNpdNonShift.apply(dateKey)) {
                            day.setStatus("NPD");
                            day.setRemarks(getNpdRemarksNonShift.apply(dateKey));
                        } else {
                            day.setStatus("Planned");
                        }
                    }
                } else {
                    if(chksFrequencyOfCheck.equals(ChecksheetFrequencyType.SHIFT.value)) {
                        List<PlanVsActualDTO.ShiftData> shiftData = new ArrayList<>();
                        shifts.forEach((shift) -> {
                            PlanVsActualDTO.ShiftData shiftDetails = new PlanVsActualDTO.ShiftData();
                            shiftDetails.setShift(shift);
                            // Past date without data: check NPD for each shift via preloaded map
                            if (isNpdShift.apply(dateKey, shift)) {
                                shiftDetails.setStatus("NPD");
                                shiftDetails.setRemarks(getNpdRemarks.apply(dateKey, shift));
                            } else {
                                shiftDetails.setStatus("Missed");
                            }
                            shiftData.add(shiftDetails);
                        });
                        day.setShiftData(shiftData);
                    } else {
                        // Past date without data: check NPD for non-shift via preloaded map
                        if (isNpdNonShift.apply(dateKey)) {
                            day.setStatus("NPD");
                            day.setRemarks(getNpdRemarksNonShift.apply(dateKey));
                        } else {
                            day.setStatus("Missed");
                        }
                    }
                }
            }
            
            dayDataList.add(day);
            start.add(Calendar.DAY_OF_MONTH, 1);
        }
        
        return dayDataList;
    }

    @Override
    public ResponseDTO<?> getRespectedChecksheetHeaderData(DashboardDTO dashboardDTO) throws CustomException {
        try {
            if (Objects.isNull(dashboardDTO.getChecksheetId())) {
                throw new CustomException("Please provide checksheet ID", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            List<DashboardDTO> headerData = dashboardDAO.getRespectedChecksheetHeaderData(
                dashboardDTO.getChecksheetId(),
                dashboardDTO.getChksHeaderDataId()
            );

            return new ResponseDTO<>(true, "Data fetched successfully", headerData);
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ResponseDTO<?> getRespectedChecksheetQuestionsData(DashboardDTO dashboardDTO) throws CustomException {
        try {
            if (Objects.isNull(dashboardDTO.getChksHeaderDataId())) {
                throw new CustomException("Please provide chks_header_data_id", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            List<DashboardDTO> questions = dashboardDAO.getRespectedChecksheetQuestionsData(dashboardDTO.getChksHeaderDataId());
            return new ResponseDTO<>(true, "Questions data fetched successfully", questions);
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ResponseDTO<?> getTrendChartData(TrendChartDTO trendChartDTO) throws CustomException {
        try {
            // Validate input
            if (Objects.isNull(trendChartDTO.getStartDate()) || 
                Objects.isNull(trendChartDTO.getEndDate()) ||
                Objects.isNull(trendChartDTO.getChecksheetIds()) ||
                Objects.isNull(trendChartDTO.getChksQuestionIds()) ||
                trendChartDTO.getChecksheetIds().isEmpty() ||
                trendChartDTO.getChksQuestionIds().isEmpty()) {
                throw new CustomException("Please provide all required parameters", 
                    HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Get question result metadata
            List<ChksQuestionResultDTO> questionResults = chksQuestionResultDAO
                .getChksQuestionResultByChecksheetId(trendChartDTO.getChecksheetIds().get(0));
            
//            List<Long> questionResultIds = trendChartDTO.getChksQuestionResultIds();
            
//            trendChartDTO.setChksQuestionResultIds(questionResultIds);

            // Get trend data
            List<TrendChartDTO> trendData = dashboardDAO.getTrendChartData(trendChartDTO);

            // Group data by question ID instead of userChecksheetAnswerId
            Map<Long, List<TrendChartDTO>> groupedData = trendData.stream()
                .collect(Collectors.groupingBy(TrendChartDTO::getChksQuestionId));

            // Create response with statistics
            List<TrendChartGroupDTO> response = new ArrayList<>();
            
            groupedData.forEach((questionId, answers) -> {
                TrendChartGroupDTO groupDTO = new TrendChartGroupDTO();
                groupDTO.setQuestionId(questionId); // Set the question ID
                
                // Set metadata
                ChksQuestionResultDTO metadata = questionResults.stream()
                    .filter(qr -> questionId.equals(qr.getChksQuestionId()) && trendChartDTO.getChksQuestionResultIds().contains(qr.getChksHeaderId()))
                    .findFirst()
                    .orElse(null);

                if (metadata != null) {
                    groupDTO.setUnit(metadata.getUnit());
                    groupDTO.setUpperLimit(metadata.getUpperLimit());
                    groupDTO.setLowerLimit(metadata.getLowerLimit());
                    groupDTO.setAnswerType(metadata.getAnswerType().toString());
                    if(!Objects.isNull(metadata.getChksQuestionResultObjectiveType())) {
                        groupDTO.setObjectiveType(metadata.getChksQuestionResultObjectiveType().toString());
                    }
                }

                // Convert all answers for this question to DTOs
                List<TrendChartAnswerDTO> answerDTOs = answers.stream()
                    .map(a -> {
                        TrendChartAnswerDTO dto = new TrendChartAnswerDTO();
                        dto.setJudgement(a.getJudgement());
                        dto.setChksQuestionId(a.getChksQuestionId());
                        dto.setChksQuestionResultId(a.getUserChecksheetAnswerId());
                        dto.setSubmittedAt(a.getSubmittedAt());
                        dto.setAnswerDate(a.getAnswerDate());
                        dto.setCheckDate(a.getCheckDate());
                        dto.setStatus(a.getStatus());
                        dto.setUserChksId(a.getId());
                        assert metadata != null;
                        if(!Objects.equals(metadata.getAnswerType(), ChksQuestionResultType.SUBJECTIVE_CONDITION)) {
                            dto.setAnswer(a.getAnswer());
                        }
                        dto.setFirstName(a.getFirstName());
                        dto.setLastName(a.getLastName());
                        dto.setUsername(a.getUsername());

                        if(Objects.equals(metadata.getAnswerType(), ChksQuestionResultType.SUBJECTIVE_CONDITION)) {
                            Optional<UserChecksheetAnswer> userChecksheetApprovalById = userChecksheetAnswerRepository.findById(a.getUserChecksheetAnswerId());
                            if(userChecksheetApprovalById.isPresent() && !Objects.isNull(userChecksheetApprovalById.get().getChksQuestionRsltOption())) {
                                Optional<ChksQuestionResultOption> chksQuestionResultOptionById = chksQuestionResultOptionRepository.findById(userChecksheetApprovalById.get().getChksQuestionRsltOption().getId());
                                chksQuestionResultOptionById.ifPresent(chksQuestionResultOption -> dto.setAnswer(Objects.equals(chksQuestionResultOption.getJudgement(), "OK") ? "1" : "0"));
                            }
                        }
                        return dto;
                    })
                    .collect(Collectors.toList());

                groupDTO.setAnswers(answerDTOs);

                // Calculate statistics if numeric
                System.out.println("groupDTO.getAnswerType() : " + groupDTO.getAnswerType());
                if(Objects.equals(groupDTO.getAnswerType(), "OBJECTIVE")) {
                    LovDataDTO lovDataDTO= lovDataDAO.getLovData("multipleAnswerSeprator");
                    // Convert answers to double list for calculations
                    List<Double> numericAnswers = answers.stream()
                            .filter(a -> !a.getAnswer().contains(lovDataDTO.getValue()))
                            .map(a -> Double.parseDouble(a.getAnswer()))
                            .collect(Collectors.toList());
                    if(numericAnswers.isEmpty()){
                        List<String> stringAnswers = answers.stream()
                                .map(TrendChartDTO::getAnswer)
                                .toList();
                        numericAnswers = stringAnswers.stream().map(a -> Arrays.stream(a.split(lovDataDTO.getValue()))
                                    .mapToDouble(Double::parseDouble) // Convert each part to double
                                    .average() // Calculate average
                                    .orElse(0.0) // Default if empty
                            ).toList();
                    }
                    if(!numericAnswers.isEmpty()) {
                        // Calculate basic statistics
                        DoubleSummaryStatistics stats = numericAnswers.stream()
                                .collect(Collectors.summarizingDouble(Double::doubleValue));

                        groupDTO.setMin(stats.getMin());
                        groupDTO.setMax(stats.getMax());
                        groupDTO.setAvg(stats.getAverage());

                        // Calculate standard deviation (sigma)
                        double mean = stats.getAverage();
                        double sigma = Math.sqrt(
                                numericAnswers.stream()
                                        .mapToDouble(value -> Math.pow(value - mean, 2))
                                        .average()
                                        .orElse(0.0)
                        );

                        groupDTO.setSigma(sigma);

                        // Calculate CP (Process Capability)
                        Double upperLimit = groupDTO.getUpperLimit();
                        Double lowerLimit = groupDTO.getLowerLimit();

                        if (upperLimit != null && lowerLimit != null && sigma != 0) {
                            double cp = (upperLimit - lowerLimit) / (6 * sigma);
                            groupDTO.setCp(cp);

                            // Calculate CPL (Process Capability Lower)
                            double cpl = (mean - lowerLimit) / (3 * sigma);
                            groupDTO.setCpl(cpl);

                            // Calculate CPU (Process Capability Upper)
                            double cpu = (upperLimit - mean) / (3 * sigma);
                            groupDTO.setCpu(cpu);

                            // Calculate CPK based on conditions
                            double cpk;
                            if (lowerLimit == 0) {
                                cpk = cpu;
                            } else if (upperLimit == 0) {
                                cpk = cpl;
                            } else {
                                cpk = Math.min(cpl, cpu);
                            }
                            groupDTO.setCpk(cpk);
                        } else {
                            groupDTO.setCp(null);
                            groupDTO.setCpl(null);
                            groupDTO.setCpu(null);
                            groupDTO.setCpk(null);
                        }
                    }else{
                        groupDTO.setCp(null);
                        groupDTO.setCpl(null);
                        groupDTO.setCpu(null);
                        groupDTO.setCpk(null);
                    }
                }
                response.add(groupDTO);
            });

            return new ResponseDTO<>(true, "Data fetched successfully", response);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void downloadChecksheetSummaryData(DashboardDTO dashboardDTO, HttpServletResponse response) throws CustomException {
        try{
            if (Objects.isNull(dashboardDTO.getStartDate()) ||
                    Objects.isNull(dashboardDTO.getEndDate())) {
                throw new CustomException("Please provide all required parameters",
                        HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if(Objects.equals(dashboardDTO.getQuestionIds(),null) || dashboardDTO.getQuestionIds().isEmpty() ){
                throw new CustomException("Please, provide Question(s).", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            List<ChksQuestion> questions = chksQuestionRepository.findByIdIn(dashboardDTO.getQuestionIds());
            if(questions.isEmpty()){
                throw new CustomException("Invalid Question IDs.", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            SXSSFWorkbook workbook =  new SXSSFWorkbook(1000);
            SXSSFSheet sheet = workbook.createSheet("Checksheet Summary");
            sheet.trackAllColumnsForAutoSizing();
            int rowCount = 0, columnCount = 0;Row row;
            CellStyle headerStyle = setCellStyle(workbook, true);
            CellStyle hdrDataStyle = setCellStyle(workbook, false);
            row = sheet.createRow(rowCount++);
            createCell(row, columnCount++, "Date Rang", headerStyle);
            createCell(row, columnCount,DateHelper.getDateToStringFormat(dashboardDTO.getStartDate(),"dd/MM/yyyy") + " - " + DateHelper.getDateToStringFormat(dashboardDTO.getEndDate(),"dd/MM/yyyy") , hdrDataStyle);
            if(!dashboardDTO.getParameters().isEmpty()){
                for(ParamValDTO param : dashboardDTO.getParameters()){
                    row = sheet.createRow(rowCount++);
                    columnCount = 0;

                    createCell(row, columnCount++, param.getName(), headerStyle);
                    createCell(row, columnCount, param.getValue(), hdrDataStyle);
                }
            }
            String questionHdr = questions.get(0).getChksHeader().getName();
            List<ChksHeaderDTO> chksHeaderResultTrue = chksHeaderDAO.getChksHeaderByChecksheetId(dashboardDTO.getChecksheetId(), true);
            List<ChksQuestionResultDTO> chksQuestionResultDTOs = dashboardDAO.getQuestionResultsByQuestionIds(dashboardDTO.getQuestionIds());
            Map<Long, List<ChksQuestionResultDTO>> mapChksQuestionResultDTOs = chksQuestionResultDTOs.stream()
                                                                                                     .collect(Collectors.groupingBy(ChksQuestionResultDTO::getChksQuestionId));

            List<Long> subjectChksQueResIds = chksQuestionResultDTOs.stream().filter(cqr -> cqr.getAnswerType().equals(ChksQuestionResultType.SUBJECTIVE_CONDITION)).map(ChksQuestionResultDTO::getId).toList();
            Map<Long,List<ChksQuestionResultOption>> mapChksQuestionResultOptions = new HashMap<>();
            if(!subjectChksQueResIds.isEmpty()){
                mapChksQuestionResultOptions = chksQuestionResultOptionRepository.findByChksQuestionResult_IdIn(subjectChksQueResIds)
                        .stream().collect(Collectors.groupingBy(croup -> croup.getChksQuestionResult().getId()));
            }
            List<ChksQuestionResultOption> chksQuestionResultOptions;
            ChksQuestionResultDTO chksQuestionResultDTO;

            List<Long> matrixChksQueResIds = chksQuestionResultDTOs.stream().filter(cqr -> cqr.getAnswerType().equals(ChksQuestionResultType.MATRIX)).map(ChksQuestionResultDTO::getId).toList();
            Map<Long,List<ChksQuestionResultMatrix>> mapChksQuestionResultMatrix = new HashMap<>();

            Map<Long, List<UserChecksheetAnswerDTO>> mapUserChecksheetAnswerDTOs = dashboardDAO.getChecksheetSummaryAnswers(dashboardDTO).stream()
                                                                                                .collect(Collectors.groupingBy(UserChecksheetAnswerDTO::getChksQuestionId));

            List<UserChecksheetAnswerDTO> userChecksheetAnswerDTOs;
            UserChecksheetAnswerDTO userChecksheetAnswerDTO;
            List<UsrChecksheetAnsJudgementDTO> usrChecksheetAnsJudgementDTOs = dashboardDAO.getChecksheetSummaryQueJudgements(dashboardDTO);
            List<Long> userChksIds = usrChecksheetAnsJudgementDTOs.stream().map(UsrChecksheetAnsJudgementDTO::getInspectionId).toList();

            Map<Long, List<UserChecksheetMatrixAnswers>> mapUserChecksheetMatrixAnswers = new HashMap<>();
            if(!matrixChksQueResIds.isEmpty()){
                mapChksQuestionResultMatrix = chksQuestionResultMatrixRepository.findByChksQuestionResult_IdIn(matrixChksQueResIds).stream().collect(Collectors.groupingBy(corm -> corm.getChksQuestionResult().getId()));
                if(!userChksIds.isEmpty()){
                    mapUserChecksheetMatrixAnswers = userChecksheetMatrixAnswersRepository.findByInspection_IdInOrderByOrderNo(userChksIds).stream().collect(Collectors.groupingBy(corm -> corm.getChksQuestionResult().getId()));
                }
            }
            Map<Long,List<UserChecksheetTraceValue>> mapUserChecksheetTraceValues = new HashMap<>();
            if(!userChksIds.isEmpty()){
                mapUserChecksheetTraceValues = userChecksheetTraceValueRepository.findByInspection_IdIn(userChksIds).stream().collect(Collectors.groupingBy(uct -> uct.getInspection().getId()));
            }
            rowCount++;

            row = sheet.createRow(rowCount++);
            columnCount = 0;
            createCell(row, columnCount++, "Sr. No.", headerStyle);
            createCell(row, columnCount++, questionHdr, headerStyle);
            for(ChksHeaderDTO chksHeaderResult:chksHeaderResultTrue){
                createCell(row, columnCount, chksHeaderResult.getName() + " Specification", headerStyle);
                createCell(row, columnCount+chksHeaderResultTrue.size(), chksHeaderResult.getName() , headerStyle);
                columnCount++;
            }
            columnCount += chksHeaderResultTrue.size();
            createCell(row, columnCount++, "Traceability" , headerStyle);
            createCell(row, columnCount++, "Submission Date" , headerStyle);
            createCell(row, columnCount++, "Submission Time" , headerStyle);
            createCell(row, columnCount++, "Judgement" , headerStyle);
            createCell(row, columnCount, "Operator" , headerStyle);
            int questionCnt = 1;
            String traceability;
            for(UsrChecksheetAnsJudgementDTO usrChecksheetAnsJudgementDTO:usrChecksheetAnsJudgementDTOs){
                columnCount = 0;
                row = sheet.createRow(rowCount++);
                createCell(row, columnCount++, questionCnt, hdrDataStyle);
                ChksQuestion que = questions.stream().filter(q -> Objects.equals(q.getId(), usrChecksheetAnsJudgementDTO.getChksQuestionId())).findFirst().orElseThrow(() -> new CustomException("Question is not valid..", HttpStatus.UNPROCESSABLE_ENTITY));
                createCell(row, columnCount++, que.getName() , hdrDataStyle);

                chksQuestionResultDTOs = mapChksQuestionResultDTOs.get(que.getId());
                userChecksheetAnswerDTOs = mapUserChecksheetAnswerDTOs.get(que.getId());
                String answer ="";
                CreationHelper createHelper = workbook.getCreationHelper();

                for(ChksHeaderDTO chksHeaderResult:chksHeaderResultTrue){
                    Hyperlink hyperlink = null, hyperlink1 = null;
                    chksQuestionResultDTO = chksQuestionResultDTOs.stream().filter(chksQueRes -> Objects.equals(chksQueRes.getChksHeaderId(), chksHeaderResult.getId())).findFirst().orElseThrow(() -> new CustomException("Header Id is not valid..", HttpStatus.UNPROCESSABLE_ENTITY));
                    StringBuilder resultSpecs = new StringBuilder(chksQuestionResultDTO.getAnswerType().getText());

                    ChksQuestionResultDTO finalChksQuestionResultDTO = chksQuestionResultDTO;

                    if(chksQuestionResultDTO.getAnswerType().equals(ChksQuestionResultType.SUBJECTIVE_CONDITION)){
                        chksQuestionResultOptions = mapChksQuestionResultOptions.get(chksQuestionResultDTO.getId());
                        for(ChksQuestionResultOption cqro:chksQuestionResultOptions){
                            resultSpecs.append("\n").append(cqro.getOption()).append(":").append(cqro.getJudgement());
                        }
                        userChecksheetAnswerDTO = userChecksheetAnswerDTOs.stream()
                                .filter(userChksAns -> Objects.equals(userChksAns.getInspectionId(), usrChecksheetAnsJudgementDTO.getInspectionId())
                                        && Objects.equals(userChksAns.getChksQuestionResultId(), finalChksQuestionResultDTO.getId()))
                                .findFirst().orElseThrow(() -> new CustomException("User checksheet Answer Id is not valid..", HttpStatus.UNPROCESSABLE_ENTITY));
                        UserChecksheetAnswerDTO finalUserChecksheetAnswerDTO = userChecksheetAnswerDTO;
                        ChksQuestionResultOption selectedOpt = chksQuestionResultOptions.stream().filter(cqro -> Objects.equals(cqro.getId(), finalUserChecksheetAnswerDTO.getChksQuestionRsltOptionId())).findFirst().orElseThrow(() -> new CustomException("Answer is invalid",HttpStatus.UNPROCESSABLE_ENTITY));
//                        answer = selectedOpt.getOption() + ":" + selectedOpt.getJudgement();
                        answer = selectedOpt.getOption();
                    } else if (chksQuestionResultDTO.getAnswerType().equals(ChksQuestionResultType.OBJECTIVE)) {
                        resultSpecs.append(" - ").append(chksQuestionResultDTO.getChksQuestionResultObjectiveType().text).append("\n");
                        if(chksQuestionResultDTO.getChksQuestionResultObjectiveType().equals(ChksQuestionResultObjectiveType.RANGE)){
                            resultSpecs.append(chksQuestionResultDTO.getLowerLimit()).append(" < answer < ").append(chksQuestionResultDTO.getUpperLimit());
                        } else if (chksQuestionResultDTO.getChksQuestionResultObjectiveType().equals(ChksQuestionResultObjectiveType.EQUAL_TO)) {
                            resultSpecs.append(" answer = ").append(chksQuestionResultDTO.getUpperLimit());
                        } else if (chksQuestionResultDTO.getChksQuestionResultObjectiveType().equals(ChksQuestionResultObjectiveType.GREATER_THAN)) {
                            resultSpecs.append(" answer > ").append(chksQuestionResultDTO.getLowerLimit());
                        } else if (chksQuestionResultDTO.getChksQuestionResultObjectiveType().equals(ChksQuestionResultObjectiveType.GREATER_THAN_OR_EQUAL_TO)) {
                            resultSpecs.append(" answer >= ").append(chksQuestionResultDTO.getLowerLimit());
                        } else if (chksQuestionResultDTO.getChksQuestionResultObjectiveType().equals(ChksQuestionResultObjectiveType.LESS_THAN)) {
                            resultSpecs.append(" answer < ").append(chksQuestionResultDTO.getUpperLimit());
                        } else if (chksQuestionResultDTO.getChksQuestionResultObjectiveType().equals(ChksQuestionResultObjectiveType.LESS_THAN_OR_EQUAL_TO)) {
                            resultSpecs.append(" answer <= ").append(chksQuestionResultDTO.getUpperLimit());
                        }
                        userChecksheetAnswerDTO = userChecksheetAnswerDTOs.stream()
                                .filter(userChksAns -> Objects.equals(userChksAns.getInspectionId(), usrChecksheetAnsJudgementDTO.getInspectionId())
                                        && Objects.equals(userChksAns.getChksQuestionResultId(), finalChksQuestionResultDTO.getId()))
                                .findFirst().orElseThrow(() -> new CustomException("User checksheet Answer Id is not valid..", HttpStatus.UNPROCESSABLE_ENTITY));
                        answer = userChecksheetAnswerDTO.getAnswer();

                        resultSpecs.append("\n Unit : ").append(chksQuestionResultDTO.getUnit());
                        resultSpecs.append("\n Number of Results : ").append(chksQuestionResultDTO.getNoOfResults());
                    } else if (chksQuestionResultDTO.getAnswerType().equals(ChksQuestionResultType.SUBJECTIVE)){
                        userChecksheetAnswerDTO = userChecksheetAnswerDTOs.stream()
                                .filter(userChksAns -> Objects.equals(userChksAns.getInspectionId(), usrChecksheetAnsJudgementDTO.getInspectionId())
                                        && Objects.equals(userChksAns.getChksQuestionResultId(), finalChksQuestionResultDTO.getId()))
                                .findFirst().orElseThrow(() -> new CustomException("User checksheet Answer Id is not valid..", HttpStatus.UNPROCESSABLE_ENTITY));
                        answer = userChecksheetAnswerDTO.getAnswer();
                    } else if (chksQuestionResultDTO.getAnswerType().equals(ChksQuestionResultType.NA)) {
                        answer = "NA";
                    } else if (chksQuestionResultDTO.getAnswerType().equals(ChksQuestionResultType.MATRIX)) {
                        resultSpecs.setLength(0);
                        resultSpecs.append("Q").append(questionCnt).append("_MATRIX_").append(chksHeaderResult.getName()).append("_SPEC");
                        answer = resultSpecs.toString();
                        List<UserChecksheetMatrixAnswers> ucma= mapUserChecksheetMatrixAnswers.get(chksQuestionResultDTO.getId()).stream()
                                                                                                .filter(userChksMatrixAns -> Objects.equals(userChksMatrixAns.getInspection().getId(), usrChecksheetAnsJudgementDTO.getInspectionId()))
                                                                                                .toList();
                        hyperlink = createHelper.createHyperlink(HyperlinkType.DOCUMENT);
                        String sheetNm = resultSpecs.toString().replaceAll("[\\\\/\\[\\]\\*\\?]", "");
                        createMatrixSheet(workbook,sheetNm,chksQuestionResultDTO,mapChksQuestionResultMatrix.get(chksQuestionResultDTO.getId()), ucma);
                        hyperlink.setAddress("'"+sheetNm+"'!A1");
                        hyperlink1 = createHelper.createHyperlink(HyperlinkType.DOCUMENT);
                        hyperlink1.setAddress("'"+sheetNm+"'!A1");
                    }
                    if(hyperlink == null){
                        createCell(row, columnCount, resultSpecs.toString(), hdrDataStyle);
                        createCell(row, columnCount+chksHeaderResultTrue.size(), answer, hdrDataStyle);
                    }else{
                        createCell(row, columnCount, resultSpecs.toString(), hdrDataStyle, hyperlink);
                        createCell(row, columnCount + chksHeaderResultTrue.size(), answer, hdrDataStyle, hyperlink1);
                    }
                    columnCount++;
                }
                columnCount += chksHeaderResultTrue.size();
                if(mapUserChecksheetTraceValues.containsKey(usrChecksheetAnsJudgementDTO.getInspectionId())){
                    traceability = mapUserChecksheetTraceValues.get(usrChecksheetAnsJudgementDTO.getInspectionId()).stream().map(UserChecksheetTraceValue::getTraceValue) // Extract trace_value
                            .filter(Objects::nonNull) // Handle potential null values
                            .collect(Collectors.joining(","));
                }else{
                    traceability = "";
                }
                createCell(row, columnCount++, traceability, hdrDataStyle);
                createCell(row, columnCount++, DateHelper.getDateToStringFormat(usrChecksheetAnsJudgementDTO.getSubmittedAt(),"dd/MM/yyyy"), hdrDataStyle);
                createCell(row, columnCount++, DateHelper.getDateToStringFormat(usrChecksheetAnsJudgementDTO.getSubmittedAt(),"HH:mm:ss"), hdrDataStyle);
                createCell(row, columnCount++, usrChecksheetAnsJudgementDTO.getJudgement(), hdrDataStyle);
                createCell(row, columnCount,  Optional.ofNullable(usrChecksheetAnsJudgementDTO.getFirstName()).orElse("") + " " +
                    Optional.ofNullable(usrChecksheetAnsJudgementDTO.getLastName()).orElse("") +
                    " (" + usrChecksheetAnsJudgementDTO.getUsername() + ")", hdrDataStyle);
                questionCnt++;
            }
            // Auto-size columns
            columnCount++;
            for (int i = 0; i < columnCount; i++) {
                sheet.autoSizeColumn(i);
            }
            ServletOutputStream outputStream = response.getOutputStream();
            workbook.write(outputStream);
            workbook.close();
            outputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private CellStyle setCellStyle(SXSSFWorkbook workbook, boolean isHdr) {
        CellStyle headerStyle = workbook.createCellStyle();
        XSSFFont font = (XSSFFont) workbook.createFont();
        font.setFontHeight(11);
        font.setBold(isHdr);
        headerStyle.setFont(font);
//        if(isHdr){
//            // Set text alignment to center (both horizontally and vertically)
//            headerStyle.setAlignment(HorizontalAlignment.CENTER);
//            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
//        }
        return headerStyle;
    }

    @Override
    public void downloadTrendChartData(TrendChartDTO trendChartDTO, HttpServletResponse response) throws CustomException {
        try{
            List<?> dataList = (List<?>) getTrendChartData(trendChartDTO).getData();
            List<TrendChartGroupDTO> trendChartGroupDTOs = dataList.stream()
                    .filter(TrendChartGroupDTO.class::isInstance)
                    .map(TrendChartGroupDTO.class::cast)
                    .toList();

            if(Objects.equals(trendChartDTO.getChksQuestionResultIds(),null) || trendChartDTO.getChksQuestionResultIds().isEmpty()){
                throw new CustomException("Please, provide Header Id.", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            ChksQuestion question = chksQuestionRepository.findById(trendChartDTO.getChksQuestionIds().get(0)).orElseThrow(() -> new CustomException("Invalid Question ID.", HttpStatus.UNPROCESSABLE_ENTITY));
            ChksHeader chksHeader = chksHeaderRepository.findById(trendChartDTO.getChksQuestionResultIds().get(0)).orElseThrow(() -> new CustomException("Invalid Header ID.", HttpStatus.UNPROCESSABLE_ENTITY));

            SXSSFWorkbook workbook =  new SXSSFWorkbook(1000);
            SXSSFSheet sheet = workbook.createSheet("Checksheet Trend Data");
            sheet.trackAllColumnsForAutoSizing();

            int rowCount = 0;
            CellStyle headerStyle = setCellStyle(workbook,true);
            CellStyle hdrDataStyle =  setCellStyle(workbook,false);

            Row row; int columnCount = 0;
            row = sheet.createRow(rowCount++);
            createCell(row, columnCount++, "Date Rang", headerStyle);
            createCell(row, columnCount,DateHelper.getDateToStringFormat(trendChartDTO.getStartDate(),"dd/MM/yyyy") + " - " + DateHelper.getDateToStringFormat(trendChartDTO.getEndDate(),"dd/MM/yyyy") , hdrDataStyle);

            if(!trendChartDTO.getParameters().isEmpty()){
                for(ParamValDTO param : trendChartDTO.getParameters()){
                    row = sheet.createRow(rowCount++);
                    columnCount = 0;

                    createCell(row, columnCount++, param.getName(), headerStyle);
                    createCell(row, columnCount, param.getValue(), hdrDataStyle);
                }
            }
            row = sheet.createRow(rowCount++);
            columnCount = 0;
            createCell(row, columnCount++, question.getChksHeader().getName(), headerStyle);
            createCell(row, columnCount,question.getName(), hdrDataStyle);
            ChksQuestionResult chksQuestionResult = chksQuestionResultRepository.findByChksHeader_IdAndChksQuestion_IdAndChecksheet_Id(chksHeader.getId(), question.getId(), question.getChecksheet().getId()).orElseThrow(() -> new CustomException("Question Result is not set for the given header!", HttpStatus.UNPROCESSABLE_ENTITY));
            row = sheet.createRow(rowCount++);
            columnCount=0;
            createCell(row, columnCount++, chksHeader.getName(), headerStyle);
            StringBuilder resultSpecs = new StringBuilder(chksQuestionResult.getAnswerType().getText());
            if(chksQuestionResult.getAnswerType().equals(ChksQuestionResultType.SUBJECTIVE_CONDITION)) {
                List<ChksQuestionResultOption> chksQuestionResultOptions = chksQuestionResultOptionRepository.findByChksQuestionResult_Id(chksQuestionResult.getId());
                for(ChksQuestionResultOption cqro:chksQuestionResultOptions){
                    resultSpecs.append("\n").append(cqro.getOption()).append(":").append(cqro.getJudgement());
                }
            } else if (chksQuestionResult.getAnswerType().equals(ChksQuestionResultType.OBJECTIVE)) {
                resultSpecs.append(" - ").append(chksQuestionResult.getObjectiveType().text).append("\n");
                if(chksQuestionResult.getObjectiveType().equals(ChksQuestionResultObjectiveType.RANGE)){
                    resultSpecs.append(chksQuestionResult.getLowerLimit()).append(" < answer < ").append(chksQuestionResult.getUpperLimit());
                } else if (chksQuestionResult.getObjectiveType().equals(ChksQuestionResultObjectiveType.EQUAL_TO)) {
                    resultSpecs.append(" answer = ").append(chksQuestionResult.getUpperLimit());
                } else if (chksQuestionResult.getObjectiveType().equals(ChksQuestionResultObjectiveType.GREATER_THAN)) {
                    resultSpecs.append(" answer > ").append(chksQuestionResult.getLowerLimit());
                } else if (chksQuestionResult.getObjectiveType().equals(ChksQuestionResultObjectiveType.GREATER_THAN_OR_EQUAL_TO)) {
                    resultSpecs.append(" answer >= ").append(chksQuestionResult.getLowerLimit());
                } else if (chksQuestionResult.getObjectiveType().equals(ChksQuestionResultObjectiveType.LESS_THAN)) {
                    resultSpecs.append(" answer < ").append(chksQuestionResult.getUpperLimit());
                } else if (chksQuestionResult.getObjectiveType().equals(ChksQuestionResultObjectiveType.LESS_THAN_OR_EQUAL_TO)) {
                    resultSpecs.append(" answer <= ").append(chksQuestionResult.getUpperLimit());
                }

                resultSpecs.append("\n Unit : ").append(chksQuestionResult.getUnit());
                if(chksQuestionResult.getNoOfResults() != null) {
                    resultSpecs.append("\n Number of Results : ").append(chksQuestionResult.getNoOfResults());
                }
//            } else if (chksQuestionResult.getAnswerType().equals(ChksQuestionResultType.MATRIX)) {

            }
            createCell(row, columnCount, resultSpecs.toString(), hdrDataStyle);
            if (chksQuestionResult.getAnswerType().equals(ChksQuestionResultType.OBJECTIVE) && !trendChartGroupDTOs.isEmpty()){
                TrendChartGroupDTO trendChartGroupDTO = trendChartGroupDTOs.get(0);
                row = sheet.createRow(rowCount++);
                createCell(row, 0, "MIN Value", headerStyle);
                createCell(row, 1,  trendChartGroupDTO.getMin() == null ? "NA":trendChartGroupDTO.getMin(), hdrDataStyle);
                row = sheet.createRow(rowCount++);
                createCell(row, 0, "MAX Value", headerStyle);
                createCell(row, 1,  trendChartGroupDTO.getMax() == null ? "NA":trendChartGroupDTO.getMax(), hdrDataStyle);
                row = sheet.createRow(rowCount++);
                createCell(row, 0, "AVERAGE", headerStyle);
                createCell(row, 1,  trendChartGroupDTO.getAvg() == null ? "NA":trendChartGroupDTO.getAvg(), hdrDataStyle);
                row = sheet.createRow(rowCount++);
                createCell(row, 0, "SIGMA", headerStyle);
                createCell(row, 1,  trendChartGroupDTO.getSigma() == null ? "NA":trendChartGroupDTO.getSigma(), hdrDataStyle);
                row = sheet.createRow(rowCount++);
                createCell(row, 0, "Cp", headerStyle);
                createCell(row, 1, trendChartGroupDTO.getCp() == null ? "NA":trendChartGroupDTO.getCp(), hdrDataStyle);
                row = sheet.createRow(rowCount++);
                createCell(row, 0, "Cpl", headerStyle);
                createCell(row, 1,  trendChartGroupDTO.getCpl() == null ? "NA":trendChartGroupDTO.getCpl(), hdrDataStyle);
                row = sheet.createRow(rowCount++);
                createCell(row, 0, "Cpu", headerStyle);
                createCell(row, 1,  trendChartGroupDTO.getCpu() == null ? "NA":trendChartGroupDTO.getCpu(), hdrDataStyle);
                row = sheet.createRow(rowCount++);
                createCell(row, 0, "Cpk", headerStyle);
                createCell(row, 1,  trendChartGroupDTO.getCpk() == null ? "NA":trendChartGroupDTO.getCpk(), hdrDataStyle);
            }
            rowCount++;
            row = sheet.createRow(rowCount++);
            columnCount = 0;
            createCell(row, columnCount++, "Sr. No.", headerStyle);
            createCell(row, columnCount++, "Submission Date" , headerStyle);
            createCell(row, columnCount++, "Submission Time" , headerStyle);
            createCell(row, columnCount++, "Traceability" , headerStyle);

            if(chksQuestionResult.getNoOfResults() == null || chksQuestionResult.getNoOfResults() <= 1) {
                createCell(row, columnCount++, chksHeader.getName() , headerStyle);
            } else {
                for(int i = 1; i <= chksQuestionResult.getNoOfResults(); i++) {
                    createCell(row, columnCount++, chksHeader.getName() + " " + i , headerStyle);
                }
            }

            createCell(row, columnCount++, "Judgement" , headerStyle);
            createCell(row, columnCount, "Operator" , headerStyle);
            List<Long> userChksIds;
            Map<Long,List<UserChecksheetTraceValue>> mapUserChecksheetTraceValues = new HashMap<>();
            int answercount = 1;String traceability;
            LovDataDTO lovDataDTO = lovDataDAO.getLovData("multipleAnswerSeprator");
            for(TrendChartGroupDTO trendChartGroupDTO:trendChartGroupDTOs){
                userChksIds = trendChartGroupDTO.getAnswers().stream().map(TrendChartAnswerDTO::getUserChksId).toList();
                if(!userChksIds.isEmpty()){
                    mapUserChecksheetTraceValues = userChecksheetTraceValueRepository.findByInspection_IdIn(userChksIds).stream().collect(Collectors.groupingBy(uct -> uct.getInspection().getId()));
                }
                for(TrendChartAnswerDTO answer:trendChartGroupDTO.getAnswers()){
                    row = sheet.createRow(rowCount++);
                    columnCount = 0;
                    createCell(row, columnCount++, answercount++, hdrDataStyle);
                    createCell(row, columnCount++, DateHelper.getDateToStringFormat(answer.getSubmittedAt(),"dd/MM/yyyy"), hdrDataStyle);
                    createCell(row, columnCount++, DateHelper.getDateToStringFormat(answer.getSubmittedAt(),"HH:mm:ss"), hdrDataStyle);
                    if(mapUserChecksheetTraceValues.containsKey(answer.getUserChksId())){
                        traceability = mapUserChecksheetTraceValues.get(answer.getUserChksId()).stream().map(UserChecksheetTraceValue::getTraceValue) // Extract trace_value
                                .filter(Objects::nonNull) // Handle potential null values
                                .collect(Collectors.joining(","));
                    }else{
                        traceability = "";
                    }
                    createCell(row, columnCount++, traceability, hdrDataStyle);

                    // Handle multiple results based on no_of_results
                    if(chksQuestionResult.getNoOfResults() == null || chksQuestionResult.getNoOfResults() <= 1) {
                        createCell(row, columnCount++, answer.getAnswer(), hdrDataStyle);
                    } else {
                        String separator = lovDataDTO != null ? lovDataDTO.getValue() : ",";

                        String[] answerValues = answer.getAnswer().split(separator);

                        for(int i = 0; i < chksQuestionResult.getNoOfResults(); i++) {
                            if(i < answerValues.length) {
                                createCell(row, columnCount++, answerValues[i], hdrDataStyle);
                            } else {
                                createCell(row, columnCount++, "", hdrDataStyle);
                            }
                        }
                    }

                    createCell(row, columnCount++, answer.getJudgement() == 1? "OK":"NOT OK", hdrDataStyle);
                    createCell(row, columnCount, (answer.getFirstName() != null ? answer.getFirstName() : "")
                            + " " + (answer.getLastName() != null ? answer.getLastName() : "") + "("+answer.getUsername()+")", hdrDataStyle);
                }
            }
            // Auto-size columns
            columnCount++;
            for (int i = 0; i < columnCount; i++) {
                sheet.autoSizeColumn(i);
            }
            ServletOutputStream outputStream = response.getOutputStream();
            workbook.write(outputStream);
            workbook.close();
            outputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void downloadPlanVsActualData(DashboardDTO dashboardDTO, HttpServletResponse response) throws CustomException {
        try{
            List<?> dataList = (List<?>) getPlanVsActualData(dashboardDTO).getData();
            List<PlanVsActualDTO> planVsActualDTOs = dataList.stream()
                    .filter(PlanVsActualDTO.class::isInstance)
                    .map(PlanVsActualDTO.class::cast)
                    .toList();

            SXSSFWorkbook workbook =  new SXSSFWorkbook(1000);
            SXSSFSheet sheet = workbook.createSheet("Checksheet Trend Data");
            sheet.trackAllColumnsForAutoSizing();
            CellStyle headerStyle = setCellStyle(workbook,true);
            CellStyle hdrDataStyle =  setCellStyle(workbook,false);

            Row row; int rowCount = 0,columnCount = 0;
            row = sheet.createRow(rowCount++);
            createCell(row, columnCount++, "Date Rang", headerStyle);
            createCell(row, columnCount,DateHelper.getDateToStringFormat(dashboardDTO.getStartDate(),"dd/MM/yyyy") + " - " + DateHelper.getDateToStringFormat(dashboardDTO.getEndDate(),"dd/MM/yyyy") , hdrDataStyle);

            row = sheet.createRow(rowCount++);
            columnCount = 0;
            createCell(row, columnCount++, "Plan", headerStyle);
            createCell(row, columnCount,dashboardDTO.getFrequencyOfCheck() , hdrDataStyle);
            rowCount++;

            row = sheet.createRow(rowCount++);
            columnCount = 0;
            List<String> dates = planVsActualDTOs.get(0).getChartData().stream().map(PlanVsActualDTO.DayData::getDate).toList();
            createCell(row, columnCount++,"Checksheet Name" , headerStyle);
            createCell(row, columnCount++,"Planned" , headerStyle);
            createCell(row, columnCount++,"Missed" , headerStyle);
            createCell(row, columnCount++,"In-progress" , headerStyle);
            createCell(row, columnCount++,"Incomplete" , headerStyle);
            createCell(row, columnCount++,"Completed" , headerStyle);
            createCell(row, columnCount++,"NPD" , headerStyle);
            for(String date:dates){
                createCell(row, columnCount++, DateHelper.getDateToStringFormat(DateHelper.getDateFromStringNoTime(date),"dd/MM/yyyy"), headerStyle);
            }
            for(PlanVsActualDTO planVsActualDTO:planVsActualDTOs){
                row = sheet.createRow(rowCount++);
                columnCount = 0;
                createCell(row, columnCount++,planVsActualDTO.getChecksheetName() , hdrDataStyle);
                createCell(row, columnCount++,planVsActualDTO.getPlanned() , hdrDataStyle);
                createCell(row, columnCount++,planVsActualDTO.getMissed(), hdrDataStyle);
                createCell(row, columnCount++,planVsActualDTO.getInProgress() , hdrDataStyle);
                createCell(row, columnCount++,planVsActualDTO.getInComplete() , hdrDataStyle);
                createCell(row, columnCount++,planVsActualDTO.getCompleted() , hdrDataStyle);
                createCell(row, columnCount++,planVsActualDTO.getNpd() , hdrDataStyle);
                for(PlanVsActualDTO.DayData dayData:planVsActualDTO.getChartData()){
                    if(dayData.getShiftData() != null){
                        StringBuilder shiftDetails = new StringBuilder();
						for (PlanVsActualDTO.ShiftData shiftData : dayData.getShiftData()) {
							String statusText = shiftData.getStatus();
							if (statusText != null && statusText.equalsIgnoreCase("NPD")) {
								String npdRemarks = shiftData.getRemarks();
								if (npdRemarks != null && !npdRemarks.trim().isEmpty()) {
									statusText = statusText + " (" + npdRemarks + ")";
								}
							}
							shiftDetails.append(shiftData.getShift()).append(" : ").append(statusText).append("\n");
						}
                        createCell(row, columnCount++, shiftDetails.toString(), hdrDataStyle);
                    }else {
						String statusText = dayData.getStatus();
						if (statusText != null && statusText.equalsIgnoreCase("NPD")) {
							String npdRemarks = dayData.getRemarks();
							if (npdRemarks != null && !npdRemarks.trim().isEmpty()) {
								statusText = statusText + " (" + npdRemarks + ")";
							}
						}
						createCell(row, columnCount++, statusText, hdrDataStyle);
                    }
                }
            }
            // Auto-size columns
            for (int i = 0; i < columnCount; i++) {
                sheet.autoSizeColumn(i);
            }
            ServletOutputStream outputStream = response.getOutputStream();
            workbook.write(outputStream);
            workbook.close();
            outputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void createCell(Row row, int columnCount, String valueOfCell, CellStyle hdrDataStyle, Hyperlink hyperlink) {
        Cell cell = row.createCell(columnCount);
        cell.setCellValue(valueOfCell);
        hdrDataStyle.setWrapText(true);  // Enable text wrapping
        cell.setCellStyle(hdrDataStyle);
        cell.setHyperlink(hyperlink);
    }

    private void createMatrixSheet(
            SXSSFWorkbook workbook,
            String sheetName,
            ChksQuestionResultDTO chksQuestionResultDTO,
            List<ChksQuestionResultMatrix> chksQuestionResultMatrices,
            List<UserChecksheetMatrixAnswers> userChecksheetMatrixAnswers
    ) throws CustomException {
        Sheet sheet = workbook.createSheet(sheetName); //--> Must not contain \,/,[,],*,?
        CellStyle headerStyle = setCellStyle(workbook,true);
        // Set text alignment to center (both horizontally and vertically)
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle hdrDataStyle = setCellStyle(workbook,false);

        int rowCount = 0, columnCnt = 0;
        Row row = sheet.createRow(rowCount);
        //Set Matrix name
        createCell(row, columnCnt, chksQuestionResultDTO.getMatrixName(), headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 1, 0, 1));

        //Set Matrix Column Name
        createCell(row, 2, chksQuestionResultDTO.getChksMatrixColName() , headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 2,2+chksQuestionResultDTO.getChksMatrixColNm().size()-1));

        //Set Matrix Column Names
        row = sheet.createRow(1);
        columnCnt = 2;
        for (String colNm : chksQuestionResultDTO.getChksMatrixColNm()) {
            createCell(row, columnCnt++, colNm, headerStyle);
        }

        //Set Matrix Data
        rowCount = 2;
        Row firstDataRow = null;
        for (String rowNm : chksQuestionResultDTO.getChksMatrixRowNm()) {
            row = sheet.createRow(rowCount++);
            if(firstDataRow == null){
                firstDataRow = row;
            }
            columnCnt = 1;
            createCell(row, columnCnt++, rowNm, headerStyle); // --> Set Matrix Row Names
            for (String colNm : chksQuestionResultDTO.getChksMatrixColNm()) {
                ChksQuestionResultMatrix chksQueResMatrix = chksQuestionResultMatrices.stream().filter(corm -> corm.getChksMatrixRowHdr().equals(rowNm) && corm.getChksMatrixColHdr().equals(colNm)).findFirst().orElseThrow(() -> new CustomException("No Matrix data found",HttpStatus.UNPROCESSABLE_ENTITY));
                createCell(row, columnCnt++, chksQueResMatrix.getData(), hdrDataStyle);
            }
        }

        //Set Matrix Row Name
        assert firstDataRow != null;
        createCell(firstDataRow, 0, chksQuestionResultDTO.getChksMatrixRowName(), headerStyle);
        sheet.addMergedRegion(new CellRangeAddress(2, 2+chksQuestionResultDTO.getChksMatrixRowNm().size()-1, 0,0));

        rowCount++;columnCnt = 0;
        //Set Answers data
        row = sheet.createRow(rowCount++);
        createCell(row, columnCnt++, "Number of Result", headerStyle);
        createCell(row, columnCnt, chksQuestionResultDTO.getNoOfResults(), headerStyle);
        row = sheet.createRow(rowCount++);
        columnCnt = 0;
        createCell(row, columnCnt++, "#", headerStyle);
        createCell(row, columnCnt++, chksQuestionResultDTO.getChksMatrixRowName(), headerStyle);
        createCell(row, columnCnt++, chksQuestionResultDTO.getChksMatrixColName(), headerStyle);
        createCell(row, columnCnt++, "Result", headerStyle);
        createCell(row, columnCnt++, "M/C Result", headerStyle);
        createCell(row, columnCnt, "Judgement", headerStyle);
        ChksQuestionResultMatrix chksQuestionResultMatrix;
        for(UserChecksheetMatrixAnswers userChecksheetMatrixAnswer: userChecksheetMatrixAnswers){
            row = sheet.createRow(rowCount++);
            columnCnt = 0;
            chksQuestionResultMatrix = userChecksheetMatrixAnswer.getChksQuestionResultMatrix();
            createCell(row, columnCnt++, userChecksheetMatrixAnswer.getOrderNo(), hdrDataStyle);
            createCell(row, columnCnt++, chksQuestionResultMatrix.getChksMatrixRowHdr(), hdrDataStyle);
            createCell(row, columnCnt++, chksQuestionResultMatrix.getChksMatrixColHdr(), hdrDataStyle);
            createCell(row, columnCnt++, chksQuestionResultMatrix.getData(), hdrDataStyle);
            createCell(row, columnCnt++, userChecksheetMatrixAnswer.getMcResult(), hdrDataStyle);
            createCell(row, columnCnt, userChecksheetMatrixAnswer.getJudgement() == 1 ? "OK":"NOT OK", hdrDataStyle);
        }
    }

    private void createCell(Row row, int columnCount, Object valueOfCell, CellStyle style) {
//        sheet1.autoSizeColumn(columnCount);
        Cell cell = row.createCell(columnCount);

        if (valueOfCell instanceof Integer) {
            cell.setCellValue((Integer) valueOfCell);
        } else if (valueOfCell instanceof Long) {
            cell.setCellValue((Long) valueOfCell);
        } else if (valueOfCell instanceof Double) {
            cell.setCellValue((Double) valueOfCell);
        } else if (valueOfCell instanceof String) {
            cell.setCellValue((String) valueOfCell);
        } else if (valueOfCell instanceof Date) {
            DateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
            cell.setCellValue(df.format(valueOfCell));
        } else if(valueOfCell instanceof Boolean) {
            cell.setCellValue((Boolean) valueOfCell);
        } else if(Objects.equals(valueOfCell, null)) {
            cell.setCellValue("");
        }
        style.setWrapText(true);  // Enable text wrapping
        cell.setCellStyle(style);
    }

    @Override
    public ResponseDTO<?> getCompletionFunnelData(CompletionFunnelDTO completionFunnelDTO) throws CustomException {
        try {
            User loginUser = utilityService.getCurrentLoggedInUser().orElseThrow(() -> new CustomException("Unauthorized user."));

            if (Objects.isNull(completionFunnelDTO.getStartDate()) || Objects.isNull(completionFunnelDTO.getEndDate())) {
                throw new CustomException("Please provide valid date range", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Get user roles and sections
            List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(loginUser.getId());
            if (Objects.equals(userRoleByUserIdDepartment, null) || userRoleByUserIdDepartment.isEmpty()) {
                // Return empty data instead of throwing exception
                CompletionFunnelDTO emptyData = new CompletionFunnelDTO();
                emptyData.setPlanned(0L);
                emptyData.setInProgress(0L);
                emptyData.setSubmitted(0L);
                emptyData.setValidated(0L);
                emptyData.setApproved(0L);
                emptyData.setRejected(0L);
                emptyData.setBreakdown(new ArrayList<>());
                return new ResponseDTO<>(true, "No roles assigned to user", emptyData);
            }
            // Use permission-based scope filtering (null = global access, empty = no access)
            List<Long> sectionIds = permissionService.getAllowedSectionIds(loginUser.getId());
            if (sectionIds != null && sectionIds.isEmpty()) {
                throw new CustomException("You have no permissions assigned. Please contact your administrator.", HttpStatus.FORBIDDEN);
            }

            CompletionFunnelDTO data = dashboardDAO.getCompletionFunnelData(completionFunnelDTO, sectionIds, loginUser);
            
            // Calculate percentages and breakdown
            long total = data.getPlanned() + data.getInProgress() + data.getSubmitted() + 
                        data.getValidated() + data.getApproved() + data.getRejected();
            
            if (total > 0) {
                List<CompletionFunnelDTO.StatusBreakdown> breakdown = new ArrayList<>();
                if (data.getInProgress() > 0) {
                    breakdown.add(new CompletionFunnelDTO.StatusBreakdown("IN_PROGRESS", data.getInProgress(), 
                        (data.getInProgress().doubleValue() / total) * 100));
                }
                if (data.getSubmitted() > 0) {
                    breakdown.add(new CompletionFunnelDTO.StatusBreakdown("SUBMITTED", data.getSubmitted(), 
                        (data.getSubmitted().doubleValue() / total) * 100));
                }
                if (data.getValidated() > 0) {
                    breakdown.add(new CompletionFunnelDTO.StatusBreakdown("VALIDATED", data.getValidated(), 
                        (data.getValidated().doubleValue() / total) * 100));
                }
                if (data.getApproved() > 0) {
                    breakdown.add(new CompletionFunnelDTO.StatusBreakdown("APPROVED", data.getApproved(), 
                        (data.getApproved().doubleValue() / total) * 100));
                }
                if (data.getRejected() > 0) {
                    breakdown.add(new CompletionFunnelDTO.StatusBreakdown("REJECTED", data.getRejected(), 
                        (data.getRejected().doubleValue() / total) * 100));
                }
                data.setBreakdown(breakdown);
            }

            return new ResponseDTO<>(true, "Completion funnel data fetched successfully", data);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ResponseDTO<?> getComplianceHeatmapData(ComplianceHeatmapDTO complianceHeatmapDTO) throws CustomException {
        try {
            User loginUser = utilityService.getCurrentLoggedInUser().orElseThrow(() -> new CustomException("Unauthorized user."));

            if (Objects.isNull(complianceHeatmapDTO.getStartDate()) || Objects.isNull(complianceHeatmapDTO.getEndDate())) {
                throw new CustomException("Please provide valid date range", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            if (Objects.isNull(complianceHeatmapDTO.getGroupBy())) {
                complianceHeatmapDTO.setGroupBy("MONTH");
            }

            // Get user roles and sections
            List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(loginUser.getId());
            if (Objects.equals(userRoleByUserIdDepartment, null) || userRoleByUserIdDepartment.isEmpty()) {
                // Return empty data instead of throwing exception
                return new ResponseDTO<>(true, "No roles assigned to user", new ArrayList<ComplianceHeatmapDTO>());
            }
            // Use permission-based scope filtering (null = global access, empty = no access)
            List<Long> sectionIds = permissionService.getAllowedSectionIds(loginUser.getId());
            if (sectionIds != null && sectionIds.isEmpty()) {
                throw new CustomException("You have no permissions assigned. Please contact your administrator.", HttpStatus.FORBIDDEN);
            }

            List<ComplianceHeatmapDTO> data = dashboardDAO.getComplianceHeatmapData(complianceHeatmapDTO, sectionIds, loginUser);
            return new ResponseDTO<>(true, "Compliance heatmap data fetched successfully", data);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ResponseDTO<?> getRecentSubmissions(RecentSubmissionDTO recentSubmissionDTO) throws CustomException {
        try {
            User loginUser = utilityService.getCurrentLoggedInUser().orElseThrow(() -> new CustomException("Unauthorized user."));

            if (Objects.isNull(recentSubmissionDTO.getStartDate()) || Objects.isNull(recentSubmissionDTO.getEndDate())) {
                throw new CustomException("Please provide valid date range", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Get user roles and sections
            List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(loginUser.getId());
            if (Objects.equals(userRoleByUserIdDepartment, null) || userRoleByUserIdDepartment.isEmpty()) {
                // Return empty data instead of throwing exception
                return new ResponseDTO<>(true, "No roles assigned to user", new ArrayList<RecentSubmissionDTO>());
            }
            // Use permission-based scope filtering (null = global access, empty = no access)
            List<Long> sectionIds = permissionService.getAllowedSectionIds(loginUser.getId());
            if (sectionIds != null && sectionIds.isEmpty()) {
                throw new CustomException("You have no permissions assigned. Please contact your administrator.", HttpStatus.FORBIDDEN);
            }

            List<RecentSubmissionDTO> data = dashboardDAO.getRecentSubmissions(recentSubmissionDTO, sectionIds, loginUser);
            return new ResponseDTO<>(true, "Recent submissions fetched successfully", data);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ResponseDTO<?> getTopNonConformingQuestions(NonConformingQuestionDTO nonConformingQuestionDTO) throws CustomException {
        try {
            User loginUser = utilityService.getCurrentLoggedInUser().orElseThrow(() -> new CustomException("Unauthorized user."));

            if (Objects.isNull(nonConformingQuestionDTO.getStartDate()) || Objects.isNull(nonConformingQuestionDTO.getEndDate())) {
                throw new CustomException("Please provide valid date range", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Get user roles and sections
            List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(loginUser.getId());
            if (Objects.equals(userRoleByUserIdDepartment, null) || userRoleByUserIdDepartment.isEmpty()) {
                // Return empty data instead of throwing exception
                return new ResponseDTO<>(true, "No roles assigned to user", new ArrayList<NonConformingQuestionDTO>());
            }
            // Use permission-based scope filtering (null = global access, empty = no access)
            List<Long> sectionIds = permissionService.getAllowedSectionIds(loginUser.getId());
            if (sectionIds != null && sectionIds.isEmpty()) {
                throw new CustomException("You have no permissions assigned. Please contact your administrator.", HttpStatus.FORBIDDEN);
            }

            List<NonConformingQuestionDTO> data = dashboardDAO.getTopNonConformingQuestions(nonConformingQuestionDTO, sectionIds, loginUser);
            return new ResponseDTO<>(true, "Top non-conforming questions fetched successfully", data);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
