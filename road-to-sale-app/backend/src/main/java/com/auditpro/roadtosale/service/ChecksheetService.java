package com.auditpro.roadtosale.service;

import com.auditpro.roadtosale.dto.ChecksheetDTO;
import com.auditpro.roadtosale.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Serves the static NADA checksheet from {@code resources/checksheets/{code}.json}.
 * Code {@code RTS_HONDA_V1} maps to {@code rts_honda_v1.json}. Unknown code -> 404.
 */
@Service
public class ChecksheetService {

    private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9_]+");

    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, ChecksheetDTO> cache = new ConcurrentHashMap<>();

    public ChecksheetService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ChecksheetDTO getByCode(String code) {
        if (code == null || !SAFE_CODE.matcher(code).matches()) {
            throw new ApiException.NotFound("Unknown checksheet: " + code);
        }
        String key = code.toUpperCase(Locale.ROOT);
        ChecksheetDTO cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        ChecksheetDTO loaded = load(key);
        cache.put(key, loaded);
        return loaded;
    }

    private ChecksheetDTO load(String upperCode) {
        String fileName = "checksheets/" + upperCode.toLowerCase(Locale.ROOT) + ".json";
        ClassPathResource resource = new ClassPathResource(fileName);
        if (!resource.exists()) {
            throw new ApiException.NotFound("Unknown checksheet: " + upperCode);
        }
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, ChecksheetDTO.class);
        } catch (IOException e) {
            throw new ApiException.NotFound("Unknown checksheet: " + upperCode);
        }
    }

    /** Total number of questions across all steps (used for progress.total). */
    public int totalQuestions(String code) {
        ChecksheetDTO sheet = getByCode(code);
        return sheet.steps().stream()
                .mapToInt(s -> s.questions() == null ? 0 : s.questions().size())
                .sum();
    }
}
