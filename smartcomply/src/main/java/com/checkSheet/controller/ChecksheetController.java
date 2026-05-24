package com.checkSheet.controller;

import com.checkSheet.DTO.AppVersionDTO;
import com.checkSheet.DTO.ChecksheetDTO;
import com.checkSheet.DTO.DepartmentDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.helper.FileStorageUtil;
import com.checkSheet.service.AppVersionService;
import com.checkSheet.service.ChecksheetService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/checksheet")
public class ChecksheetController {
    @Autowired
    private ChecksheetService checksheetService;

    @Autowired
    private AppVersionService appVersionService;
    @PostMapping("/createChecksheet")
    public ResponseEntity<?> createChecksheet(@RequestBody ChecksheetDTO checksheetDTO) {
        try {
            return ResponseEntity.ok(checksheetService.createChecksheet(checksheetDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/checkCodeAvailability")
    public ResponseEntity<?> checkCodeAvailability(
            @RequestParam String modelNo,
            @RequestParam(required = false) Long excludeId) {
        try {
            return ResponseEntity.ok(checksheetService.checkCodeAvailability(modelNo, excludeId));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
    @GetMapping("getWaitingCount")
    public ResponseEntity<?> getWaitingCount(){
        try {
            return ResponseEntity.ok(checksheetService.getWaitingCount());
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @DeleteMapping("deleteChks/{chksId}")
    public ResponseEntity<?> deleteChks(@PathVariable Long chksId){
        try {
            return ResponseEntity.ok(checksheetService.deleteChks(chksId));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getRespectedChecksheet")
    public ResponseEntity<?> getRespectedChecksheet(@RequestBody ChecksheetDTO checksheetDTO) throws CustomException {
        try {
            return ResponseEntity.ok(checksheetService.getRespectedChecksheet(checksheetDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/getPublicChecksheet")
    public ResponseEntity<?> getPublicChecksheets() throws CustomException {
        try {
            return ResponseEntity.ok(checksheetService.getPublicChecksheets());
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getDepartmentChecksheets")
    public ResponseEntity<?> getDepartmentChecksheets(@RequestBody DepartmentDTO departmentDTO){
        try {
            return ResponseEntity.ok(checksheetService.getDepartmentChecksheets(departmentDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    /*
        Purpose: To get a file from S3 bucket
        Input:imageName
        Output: Receive file
     */
    @PostMapping("/downloadFile")
    public byte[] downloadFile(@RequestBody ChecksheetDTO checksheetDTO) throws CustomException {
        return checksheetService.downloadFile(checksheetDTO);
    }

    @PostMapping("/getS3FileURL")
    public ResponseEntity<?> getS3FileURL(@RequestBody ChecksheetDTO checksheetDTO) {
        try {
//            return ResponseEntity.ok(checksheetService.getS3FileURL(checksheetDTO));
            return ResponseEntity.ok(checksheetService.getFileURL(checksheetDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getS3FilesURL")
    public ResponseEntity<?> getS3FilesURL(@RequestBody ChecksheetDTO checksheetDTO) {
        try {
//            return ResponseEntity.ok(checksheetService.getS3FilesURL(checksheetDTO));
            return ResponseEntity.ok(checksheetService.getFilesURL(checksheetDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getChecksheetDetail")
    public ResponseEntity<?> getChecksheetDetail(@RequestBody ChecksheetDTO checksheetDTO) {
        try {
            return ResponseEntity.ok(checksheetService.getChecksheetDetail(checksheetDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("doc/{directoryPath}/{fileName:.+}")
    public ResponseEntity<Resource> getDocs(
            @PathVariable String directoryPath,
            @PathVariable String fileName,
            HttpServletRequest request
    ) throws CustomException {
        // Load file as Resource
        return FileStorageUtil.getDocs(directoryPath,fileName,request);
    }

    @PostMapping("/createChecksheetVersion")
    public ResponseEntity<?> createChecksheetVersion(@RequestBody ChecksheetDTO checksheetDTO) {
        try {
            return ResponseEntity.ok(checksheetService.createChecksheetVersion(checksheetDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/updateChecksheetStatus")
    public ResponseEntity<?> updateChecksheetStatus(@RequestBody ChecksheetDTO checksheetDTO) {
        try {
            return ResponseEntity.ok(checksheetService.updateChecksheetStatus(checksheetDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("getLovData")
    public ResponseEntity<?> getLovData() throws CustomException {
        try {
            return ResponseEntity.ok(checksheetService.getLovData());
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getVersionUpdates")
    public ResponseEntity<?> getVersionUpdates(@RequestBody AppVersionDTO appVersionDTO) {
        return ResponseEntity.ok(appVersionService.getAppVersionByOs(appVersionDTO));
    }

    @GetMapping("/getApprovedNotExpiredChecksheets")
    public ResponseEntity<?> getApprovedNotExpiredChecksheets() {
        try {
            return ResponseEntity.ok(checksheetService.getApprovedNotExpiredChecksheets());
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @GetMapping("/getActiveAuditors")
    public ResponseEntity<?> getActiveAuditors() {
        try {
            return ResponseEntity.ok(checksheetService.getActiveAuditors());
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getActiveAuditeeLocationsByChecksheet")
    public ResponseEntity<?> getActiveAuditeeLocationsByChecksheet(@RequestBody ChecksheetDTO checksheetDTO) {
        try {
            return ResponseEntity.ok(checksheetService.getActiveAuditeeLocationsByChecksheet(checksheetDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/createChecksheetAssignment")
    public ResponseEntity<?> createChecksheetAssignment(@RequestBody ChecksheetDTO checksheetDTO) {
        try {
            return ResponseEntity.ok(checksheetService.createChecksheetAssignment(checksheetDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
