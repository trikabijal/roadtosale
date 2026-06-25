package com.auditpro.roadtosale.controller;

import com.auditpro.roadtosale.dto.ChecksheetDTO;
import com.auditpro.roadtosale.service.ChecksheetService;
import com.auditpro.roadtosale.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Serves the static NADA checksheet by code. */
@RestController
@Tag(name = "checksheet")
public class ChecksheetController {

    private final ChecksheetService checksheetService;

    public ChecksheetController(ChecksheetService checksheetService) {
        this.checksheetService = checksheetService;
    }

    @Operation(summary = "Get a NADA checksheet by code (static reference data)")
    @GetMapping("/checksheet/{code}")
    public ApiResponse<ChecksheetDTO> get(@PathVariable String code) {
        return ApiResponse.ok(checksheetService.getByCode(code));
    }
}
