package com.checkSheet.controller;

import com.checkSheet.DTO.ChksGeneralFieldDTO;
import com.checkSheet.DTO.DepartmentDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.ChksGeneralFieldService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/chksGeneralField")
public class ChksGeneralFieldController {

    @Autowired
    private ChksGeneralFieldService chksGeneralFieldService;

    @PostMapping("/createChksGeneralField")
    public ResponseEntity<?> createChksGeneralField(@RequestBody ChksGeneralFieldDTO chksGeneralFieldDTO) throws CustomException {
        try {
            return ResponseEntity.ok(chksGeneralFieldService.createChksGeneralField(chksGeneralFieldDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getChksGeneralFieldData")
    public ResponseEntity<?> getChksGeneralFieldData(@RequestBody ChksGeneralFieldDTO chksGeneralFieldDTO) throws CustomException {
        try {
            return ResponseEntity.ok(chksGeneralFieldService.getChksGeneralFieldData(chksGeneralFieldDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/deleteChksGeneralFieldData")
    public ResponseEntity<?> deleteChksGeneralFieldData(@RequestBody ChksGeneralFieldDTO chksGeneralFieldDTO) throws CustomException {
        try {
            return ResponseEntity.ok(chksGeneralFieldService.deleteChksGeneralFieldData(chksGeneralFieldDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }
}
