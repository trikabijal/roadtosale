package com.checkSheet.service;

import com.checkSheet.DAO.LovDataDAO;
import com.checkSheet.DAO.NpdMasterDAO;
import com.checkSheet.DTO.LovDataDTO;
import com.checkSheet.DTO.NpdMasterDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.constant.ChecksheetFrequencyType;
import com.checkSheet.entity.Checksheet;
import com.checkSheet.entity.NpdMaster;
import com.checkSheet.entity.User;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.ChecksheetRepository;
import com.checkSheet.repository.NpdMasterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class NpdMasterServiceImpl implements NpdMasterService {

    @Autowired
    private NpdMasterRepository npdMasterRepository;

    @Autowired
    private ChecksheetRepository checksheetRepository;

    @Autowired
    private LovDataDAO lovDataDAO;

    @Autowired
    private NpdMasterDAO npdMasterDAO;

    @Autowired
    private UtilityService utilityService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> create(NpdMasterDTO dto) throws CustomException {
        try {
            if (Objects.isNull(dto) || Objects.isNull(dto.getChecksheetIds()) || dto.getChecksheetIds().isEmpty()) {
                throw new CustomException("Please provide non-empty checksheetIds", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Validate user            
            User currentUser = utilityService.getCurrentLoggedInUser().orElseThrow(() -> new CustomException("Current user not found", HttpStatus.UNAUTHORIZED));

            // Load checksheets and validate all exist
            List<Long> checksheetIds = dto.getChecksheetIds();
            List<Checksheet> checksheets = new ArrayList<>();
            for (Long csId : checksheetIds) {
                Checksheet cs = checksheetRepository.findById(csId)
                        .orElseThrow(() -> new CustomException("Invalid checksheet id: " + csId, HttpStatus.UNPROCESSABLE_ENTITY));
                // Authorization: current user must be a data validator of the checksheet
//                List<Long> dataValidatorUserIds = cs.getDataValidatorUserIds();
//                if (Objects.isNull(dataValidatorUserIds) || dataValidatorUserIds.isEmpty() || !dataValidatorUserIds.contains(currentUser.getId())) {
//                    throw new CustomException("You are not authorized to create NPD for checksheet ", HttpStatus.FORBIDDEN);
//                }
                checksheets.add(cs);
            }

            // Validate same frequency across all checksheets
            ChecksheetFrequencyType baseFreq = checksheets.get(0).getFrequencyOfCheck();
            for (Checksheet cs : checksheets) {
                if (!Objects.equals(baseFreq, cs.getFrequencyOfCheck())) {
                    throw new CustomException("All checksheets must have same frequency", HttpStatus.UNPROCESSABLE_ENTITY);
                }
            }

            // Validate and prepare date range [startDate, endDate] inclusive
            Date startDate = dto.getStartDate();
            Date endDate = dto.getEndDate();
            if (Objects.isNull(startDate) || Objects.isNull(endDate)) {
                throw new CustomException("Please provide startDate and endDate", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            ZoneId zone = ZoneId.systemDefault();
            LocalDate startLocal = startDate.toInstant().atZone(zone).toLocalDate();
            LocalDate endLocal = endDate.toInstant().atZone(zone).toLocalDate();
            if (endLocal.isBefore(startLocal)) {
                throw new CustomException("startDate must be before or equal to endDate", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // For SHIFT frequency, shifts list is required; otherwise shifts are ignored
            if (Objects.equals(baseFreq, ChecksheetFrequencyType.SHIFT)) {
                if (Objects.isNull(dto.getShifts()) || dto.getShifts().isEmpty()) {
                    throw new CustomException("Shifts are required for SHIFT frequency checksheets", HttpStatus.UNPROCESSABLE_ENTITY);
                }
            }

            int created = 0;
            for (Checksheet cs : checksheets) {
                for (LocalDate d = startLocal; !d.isAfter(endLocal); d = d.plusDays(1)) {
                    Date npdDate = Date.from(d.atStartOfDay(zone).toInstant());
                    if (Objects.equals(baseFreq, ChecksheetFrequencyType.SHIFT)) {
                        for (String s : dto.getShifts()) {
                            created += upsertNpd(cs, npdDate, s, currentUser, dto);
                        }
                    } else {
                        created += upsertNpd(cs, npdDate, null, currentUser, dto);
                    }
                }
            }

            return new ResponseDTO<>(true, "NPD created successfully", created);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> createBulk(NpdMasterDTO dto) throws CustomException {
        try {
            if (Objects.isNull(dto) || Objects.isNull(dto.getChecksheetIds()) || dto.getChecksheetIds().isEmpty() ||
                Objects.isNull(dto.getNpdDays()) || dto.getNpdDays().isEmpty()) {
                throw new CustomException("Please provide checksheetIds, npdDays", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            User user = utilityService.getCurrentLoggedInUser()
                    .orElseThrow(() -> new CustomException("Current user not found", HttpStatus.UNAUTHORIZED));

            int created = 0;

            // New payload path: iterate npdDays if provided
            for (Long checksheetId : dto.getChecksheetIds()) {
                Checksheet checksheet = checksheetRepository.findById(checksheetId)
                        .orElseThrow(() -> new CustomException("Invalid checksheet id: " + checksheetId, HttpStatus.UNPROCESSABLE_ENTITY));

                // Authorization: current user must be a data validator of the checksheet
                List<Long> dataValidatorUserIds = checksheet.getDataValidatorUserIds();
                if (Objects.isNull(dataValidatorUserIds) || dataValidatorUserIds.isEmpty() || !dataValidatorUserIds.contains(user.getId())) {
                    throw new CustomException("You are not authorized to create NPD for checksheet ", HttpStatus.FORBIDDEN);
                }

                for (NpdMasterDTO.NpdDayDTO day : dto.getNpdDays()) {
                    Date npdDateToUse = day.getNpdDate();
                    if (!Objects.isNull(checksheet.getFrequencyOfCheck()) && Objects.equals(checksheet.getFrequencyOfCheck(), ChecksheetFrequencyType.SHIFT)) {
                        if (Objects.isNull(day.getShifts()) || day.getShifts().isEmpty()) {
                            throw new CustomException("Shifts is empty", HttpStatus.UNPROCESSABLE_ENTITY);
                        }
                        for (String shift : day.getShifts()) {
                            created += upsertNpd(checksheet, npdDateToUse, shift, user, dto);
                        }
                    } else {
                        created += upsertNpd(checksheet, npdDateToUse, null, user, dto);
                    }
                }
            }

            return new ResponseDTO<>(true, "Bulk NPD created successfully", created);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> update(NpdMasterDTO dto) throws CustomException {
        try {
            if (Objects.isNull(dto) || Objects.isNull(dto.getId())) {
                throw new CustomException("Please provide id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            NpdMaster npd = npdMasterRepository.findById(dto.getId())
                    .orElseThrow(() -> new CustomException("Invalid id", HttpStatus.UNPROCESSABLE_ENTITY));
            if (Objects.nonNull(dto.getShift())) npd.setShift(dto.getShift());
            if (Objects.nonNull(dto.getIsException())) npd.setIsException(dto.getIsException());
            if (Objects.nonNull(dto.getNpdDate())) npd.setNpdDate(dto.getNpdDate());
            if (Objects.nonNull(dto.getRemarks())) npd.setRemarks(dto.getRemarks());
            npdMasterRepository.save(npd);
            return new ResponseDTO<>(true, "NPD updated successfully");
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> delete(NpdMasterDTO dto) throws CustomException {
        try {
            java.util.List<Long> ids = (dto != null) ? dto.getIds() : null;
            if (ids == null || ids.isEmpty()) {
                throw new CustomException("Please provide non-empty ids", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            List<NpdMaster> toDelete = new ArrayList<>();
            for (Long id : ids) {
                NpdMaster npd = npdMasterRepository.findById(id)
                        .orElseThrow(() -> new CustomException("Invalid id: " + id, HttpStatus.UNPROCESSABLE_ENTITY));
                toDelete.add(npd);
            }
            npdMasterRepository.deleteAll(toDelete);
            return new ResponseDTO<>(true, "NPD deleted successfully", toDelete.size());
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getById(Long id) throws CustomException {
        try {
            NpdMaster npd = npdMasterRepository.findById(id)
                    .orElseThrow(() -> new CustomException("Invalid id", HttpStatus.UNPROCESSABLE_ENTITY));
            return new ResponseDTO<>(true, "Fetched successfully", npd);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> search(NpdMasterDTO dto) throws CustomException {
        try {
            String name = dto != null ? dto.getChecksheetNameLike() : null;
            String freq = dto != null ? dto.getFrequencyOfCheck() : null;
            java.util.Date start = dto != null ? dto.getStartDate() : null;
            java.util.Date end = dto != null ? dto.getEndDate() : null;
            int page = (dto != null && dto.getPage() != null && dto.getPage() >= 0) ? dto.getPage() : 0;
            int size = (dto != null && dto.getSize() != null && dto.getSize() > 0 && dto.getSize() <= 1000) ? dto.getSize() : 20;

            java.util.Map<String, Object> raw = npdMasterDAO.aggregatedCounts(name, freq, start, end, page, size);
            @SuppressWarnings("unchecked")
            java.util.List<Object[]> rows = (java.util.List<Object[]>) raw.get("rows");
            long total = (long) raw.get("total");

            java.util.List<NpdMasterDTO> content = new java.util.ArrayList<>();
            if (rows != null) {
                for (Object[] r : rows) {
                    NpdMasterDTO item = new NpdMasterDTO();
                    item.setChecksheetId(r[0] != null ? ((Number) r[0]).longValue() : null);
                    item.setChecksheetName(r[1] != null ? r[1].toString() : null);
                    item.setFrequencyOfCheck(r[2] != null ? r[2].toString() : null);
                    item.setNpdCount(r[3] != null ? ((Number) r[3]).longValue() : 0L);
                    content.add(item);
                }
            }

            int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / (double) size);
            return new ResponseDTO<>( "Fetched successfully", content, total, totalPages, page, size);
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> detailByChecksheetId(NpdMasterDTO dto) throws CustomException {
        try {
            if (dto == null || dto.getChecksheetId() == null) {
                throw new CustomException("Please provide checksheetId", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            int page = (dto.getPage() != null && dto.getPage() >= 0) ? dto.getPage() : 0;
            int size = (dto.getSize() != null && dto.getSize() > 0 && dto.getSize() <= 1000) ? dto.getSize() : 20;

            java.util.Map<String, Object> res = npdMasterDAO.findByChecksheetPaginated(dto.getChecksheetId(), page, size, dto.getStartDate(), dto.getEndDate());
            @SuppressWarnings("unchecked")
            java.util.List<Object[]> rows = (java.util.List<Object[]>) res.get("rows");
            long total = (long) res.get("total");

            java.util.List<NpdMasterDTO> content = new java.util.ArrayList<>();
            ZoneId zone = ZoneId.systemDefault();
            LocalDate today = LocalDate.now(zone);
            if (rows != null) {
                for (Object[] r : rows) {
                    NpdMasterDTO item = new NpdMasterDTO();
                    item.setId(r[0] != null ? ((Number) r[0]).longValue() : null);
                    java.util.Date npdDateVal = (java.util.Date) r[1];
                    item.setNpdDate(npdDateVal);
                    item.setShift(r[2] != null ? r[2].toString() : null);
                    item.setChecksheetId(r[3] != null ? ((Number) r[3]).longValue() : null);
                    item.setRemarks(r[4] != null ? r[4].toString() : null);
                    item.setCreatedAt((java.util.Date) r[5]);
                    if (npdDateVal != null) {
                        LocalDate npdLocal;
                        if (npdDateVal instanceof java.sql.Date) {
                            npdLocal = ((java.sql.Date) npdDateVal).toLocalDate();
                        } else {
                            npdLocal = npdDateVal.toInstant().atZone(zone).toLocalDate();
                        }
                        item.setIsDeletable(!npdLocal.isBefore(today));
                    }
                    content.add(item);
                }
            }

            int totalPages = size == 0 ? 0 : (int) Math.ceil((double) total / (double) size);
            return new ResponseDTO<>( "Fetched successfully", content, total, totalPages, page, size);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> runDailyNpd() throws CustomException {
        try {
            ZoneId zone = ZoneId.systemDefault();
            LocalDate today = LocalDate.now(zone);
            DayOfWeek dow = today.getDayOfWeek();
            String dayLower = dow.name().toLowerCase();

            List<Checksheet> checksheets = checksheetRepository.findByNpdDayContains(dayLower);
            if (checksheets == null || checksheets.isEmpty()) {
                return new ResponseDTO<>(true, "No checksheets for today's NPD");
            }

            Date npdDate = Date.from(today.atStartOfDay(zone).toInstant());

            // Get shifts if needed
            Set<String> shifts = new LinkedHashSet<>();
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

            // Extract checksheet IDs
            List<Long> checksheetIds = checksheets.stream()
                    .map(Checksheet::getId)
                    .collect(java.util.stream.Collectors.toList());

            // Convert shifts set to list
            List<String> shiftsList = new ArrayList<>(shifts);

            // Create DTO for bulk creation with null createdBy (cron operation)
            NpdMasterDTO bulkDto = new NpdMasterDTO();
            bulkDto.setChecksheetIds(checksheetIds);
            bulkDto.setShifts(shiftsList);
            bulkDto.setNpdDate(npdDate);
            bulkDto.setCreatedBy(null); // null for cron operations

            // Use bulk create method
            return createBulk(bulkDto);
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private int upsertNpd(Checksheet c, Date npdDate, String shift, User createdBy, NpdMasterDTO dto) {
        NpdMaster npd = npdMasterDAO.findByChecksheetIdAndDateAndShift(c.getId(), npdDate, shift);
        if(Objects.isNull(npd)) {
            npd = new NpdMaster();
            if (createdBy != null) npd.setCreatedBy(createdBy);
        } else {
            npd.setUpdatedBy(createdBy);
        }
        npd.setChecksheet(c);
        npd.setNpdDate(npdDate);
        npd.setShift(shift);
        npd.setRemarks(dto.getRemarks());
        npd.setIsException(Objects.nonNull(dto)  && Objects.nonNull(dto.getIsException()) ? dto.getIsException() : Boolean.FALSE);
        npdMasterRepository.save(npd);
        return 1;
    }
}


