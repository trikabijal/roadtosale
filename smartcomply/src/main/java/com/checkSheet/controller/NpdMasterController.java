package com.checkSheet.controller;

import com.checkSheet.DTO.NpdMasterDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.entity.User;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.UserRepository;
import com.checkSheet.service.NpdMasterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/npd")
public class NpdMasterController {

    @Autowired
    private NpdMasterService npdMasterService;

    @Autowired
    private UserRepository userRepository;

    @PostMapping("/create")
    public ResponseEntity<?> create(@RequestBody NpdMasterDTO dto) throws CustomException {
        try {
            return ResponseEntity.ok(npdMasterService.create(dto));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/update")
    public ResponseDTO<?> update(@RequestBody NpdMasterDTO dto) throws CustomException {
        return npdMasterService.update(dto);
    }

    @PostMapping("/delete")
    public ResponseEntity<?> delete(@RequestBody NpdMasterDTO dto) throws CustomException {
        try {
            return ResponseEntity.ok(npdMasterService.delete(dto));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/get/{id}")
    public ResponseDTO<?> getById(@PathVariable Long id) throws CustomException {
        return npdMasterService.getById(id);
    }

    @PostMapping("/search")
    public ResponseDTO<?> search(@RequestBody NpdMasterDTO dto) throws CustomException {
        return npdMasterService.search(dto);
    }

    // List NPD records by checksheetId with pagination
    @PostMapping("/detailByChecksheetId")
    public ResponseDTO<?> detailByChecksheetId(@RequestBody NpdMasterDTO dto) throws CustomException {
        return npdMasterService.detailByChecksheetId(dto);
    }

    // Bulk create NPD entries
    @PostMapping("/createBulk")
    public ResponseEntity<?> createBulk(@RequestBody NpdMasterDTO dto) throws CustomException {
        try {
            if (Objects.isNull(dto.getChecksheetIds()) || dto.getChecksheetIds().isEmpty()) {
                throw new CustomException("Please provide checksheetIds", HttpStatus.BAD_REQUEST);
            }
            
            // Get current logged-in user
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String username = authentication.getName();
            User currentUser = userRepository.findByUsernameIgnoreCase(username)
                    .orElseThrow(() -> new CustomException("Current user not found", HttpStatus.UNAUTHORIZED));
            
            // Set the current user as createdBy
            dto.setCreatedBy(currentUser.getId());
            
            return ResponseEntity.ok(npdMasterService.createBulk(dto));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        } catch (Exception e) {
            throw new CustomException("Invalid request format", HttpStatus.BAD_REQUEST);
        }
    }

    // Manual trigger for daily NPD run
    @PostMapping("/runDaily")
    public ResponseDTO<?> runDaily() throws CustomException {
        return npdMasterService.runDailyNpd();
    }

    // Cron inside controller as requested (runs daily at 00:00)
//    @Scheduled(cron = "0 0 0 * * *")
//    public void cronRunDaily() {
//        try {
//            npdMasterService.runDailyNpd();
//        } catch (Exception ignored) { }
//    }
}


