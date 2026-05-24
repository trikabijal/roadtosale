package com.checkSheet.exception;

import com.checkSheet.DTO.response.ResponseDTO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Issue #9: file-upload validation.
 *
 * Spring's multipart parser rejects oversized requests at the parser layer
 * (driven by spring.servlet.multipart.max-file-size) by throwing
 * MaxUploadSizeExceededException BEFORE the request reaches the controller.
 *
 * Without this handler the client would see an opaque HTTP 500. We map it to
 * 413 PAYLOAD_TOO_LARGE with the same {status,message} envelope every other
 * endpoint returns, so mobile clients can render a friendly error.
 *
 * NOTE: this is intentionally narrow (only MaxUploadSizeExceededException) —
 * a project-wide @ControllerAdvice that maps CustomException uniformly is
 * tracked separately ("Round 3" in audit-flow tests). Don't expand scope
 * here.
 */
@RestControllerAdvice
public class MultipartUploadExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ResponseDTO<?>> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        long maxBytes = ex.getMaxUploadSize();
        String detail = maxBytes > 0
                ? "Upload exceeds the configured limit of " + maxBytes + " bytes."
                : "Upload exceeds the configured size limit.";
        return ResponseEntity
                .status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ResponseDTO<>(false, detail));
    }
}
