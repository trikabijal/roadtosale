package com.checkSheet.config;

import com.checkSheet.entity.APIHistory;
import com.checkSheet.repository.APIHistoryRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
class MyPayloadCapturingFilter extends OncePerRequestFilter {
    @Autowired
    private APIHistoryRepository apiHistoryRepository;

    @Value("${is_API_log_save:false}")
    private Boolean isAPILogSave = false;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if(isAPILogSave) {
            try {
            /*CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest((HttpServletRequest) request);
            CachedBodyHttpServletResponse wrappedResponse = new CachedBodyHttpServletResponse((HttpServletResponse) response);

            // Capture request details excluding files
            String method = wrappedRequest.getMethod();
            String requestUri = wrappedRequest.getRequestURI();
            String requestHeaders = getRequestHeaders(wrappedRequest);

            // Proceed with the next filter in the chain
            filterChain.doFilter(wrappedRequest, wrappedResponse);

            String requestBody = "";
            if (isFormData(wrappedRequest)) {
                requestBody = getFormDataWithoutFiles(wrappedRequest);
            } else {
                requestBody = wrappedRequest.getReader().lines().collect(Collectors.joining(System.lineSeparator()));
            }
            // Capture response details
            wrappedResponse.flushBuffer(); // Ensure all content is written to the output stream
            String responseBody = new String(wrappedResponse.getContentAsByteArray());
            APIHistory log = new APIHistory();
            log.setMethod(method);
            log.setRequestUri(requestUri);
            log.setRequest(requestBody);
            log.setResponse(responseBody);
            log.setRequestHeaders(requestHeaders);
            apiHistoryRepository.save(log);*/
                HttpServletRequest httpRequest = (HttpServletRequest) request;
                if(Objects.equals(httpRequest.getContentType(), null) || !httpRequest.getContentType().toLowerCase().contains("multipart/form-data")) {
//            if(Objects.equals(substring, "application/json")) {
                    CachedBodyHttpServletRequest requestWrapper = new CachedBodyHttpServletRequest((HttpServletRequest) request);
                    CachedBodyHttpServletResponse responseWrapper = new CachedBodyHttpServletResponse((HttpServletResponse) response);


                    filterChain.doFilter(requestWrapper, responseWrapper);
                    String method = requestWrapper.getMethod();
                    String requestUri = requestWrapper.getRequestURI();
                    String requestBody = requestWrapper.getReader().lines().collect(Collectors.joining(System.lineSeparator()));
                    String requestHeaders = getRequestHeaders(requestWrapper);

                    // Capture response details
                    responseWrapper.flushBuffer(); // Ensure all content is written to the output stream
                    String responseBody = "";
                    try {
                        responseBody = new String(responseWrapper.getContentAsByteArray());
                    } catch(Exception e) {
                        e.printStackTrace();
                    }

                    APIHistory log = new APIHistory();
                    log.setMethod(method);
                    log.setRequestUri(requestUri);
                    log.setRequest(redactSecretFields(requestBody));
                    log.setResponse(redactSecretFields(responseBody));
                    log.setRequestHeaders(requestHeaders);
                    apiHistoryRepository.save(log);
                } else {
                /*filterChain.doFilter(request, response);
                CachedBodyHttpServletRequest requestWrapper = new CachedBodyHttpServletRequest((HttpServletRequest) request);
                CachedBodyHttpServletResponse responseWrapper = new CachedBodyHttpServletResponse((HttpServletResponse) response);


                String method = requestWrapper.getMethod();
                String requestUri = requestWrapper.getRequestURI();
                String requestBody = getFormDataWithoutFiles(request);
                String requestHeaders = getRequestHeaders(requestWrapper);

                // Capture response details
                responseWrapper.flushBuffer(); // Ensure all content is written to the output stream
                String responseBody = new String(responseWrapper.getContentAsByteArray());*/
                    ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
                    ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

                    // Proceed with the next filter in the chain
                    filterChain.doFilter(wrappedRequest, wrappedResponse);
                    String method = wrappedRequest.getMethod();
                    String requestUri = wrappedRequest.getRequestURI();
                    String requestBody = getRequestPayload(wrappedRequest);
                    String requestHeaders = getRequestHeaders(wrappedRequest);
                    // Capture response details
                    String responseBody = getResponsePayload(wrappedResponse);
                    wrappedResponse.copyBodyToResponse();

                    APIHistory log = new APIHistory();
                    log.setMethod(method);
                    log.setRequestUri(requestUri);
                    log.setRequest(redactSecretFields(requestBody));
                    log.setResponse(redactSecretFields(responseBody));
                    log.setRequestHeaders(requestHeaders);
                    apiHistoryRepository.save(log);
//                filterChain.doFilter(request, response);
                }
            } catch(Exception e) {
                e.printStackTrace();
//            filterChain.doFilter(request, response);
            }
        } else {
            filterChain.doFilter(request, response);
        }
    }

    private String getRequestPayload(ContentCachingRequestWrapper request) {
        Map<String, String[]> parameterMap = request.getParameterMap();
        StringBuilder str = new StringBuilder();
        for(String key : parameterMap.keySet()) {
            str.append(key + " : ");
            str.append(Arrays.toString(parameterMap.get(key)) + "\n");
        }
        return str.toString();
        /*byte[] buf = request.getContentAsByteArray();
        if (buf.length > 0) {
            return new String(buf, 0, buf.length);
        }
        return "";*/
    }

    private String getResponsePayload(ContentCachingResponseWrapper response) throws IOException {
        byte[] buf = response.getContentAsByteArray();
        if (buf.length > 0) {
            return new String(buf, 0, buf.length);
        }
        return "";
    }

    private String getFormDataWithoutFiles(HttpServletRequest request) throws IOException {
        StringBuilder formData = new StringBuilder();
        BufferedReader reader = request.getReader();
        String line;
        boolean skipPart = false;

        while ((line = reader.readLine()) != null) {
            if (line.startsWith("Content-Disposition")) {
                if (line.contains("filename=\"")) {
                    skipPart = true;
                } else {
                    skipPart = false;
                }
            }

            if (!skipPart) {
                formData.append(line).append(System.lineSeparator());
            }
        }

        return formData.toString();
    }

    private String getRequestHeaders(HttpServletRequest request) {
        Enumeration<String> headerNames = request.getHeaderNames();
        StringBuilder headers = new StringBuilder();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            String headerValue = request.getHeader(headerName);
            // Redact bearer tokens, cookies, and any explicit secret-like headers.
            // The api_history table is intended for support / audit replay, not
            // for forensic credential extraction. See backlog #19.
            if ("authorization".equalsIgnoreCase(headerName)
                || "cookie".equalsIgnoreCase(headerName)
                || "x-api-key".equalsIgnoreCase(headerName)) {
                headerValue = "[REDACTED]";
            }
            headers.append(headerName).append(": ").append(headerValue).append("\n");
        }
        return headers.toString();
    }

    /** JSON field redaction. Crude regex — handles `"<key>" : "<value>"` with
     *  optional whitespace; doesn't try to be a real JSON parser. The cost
     *  of a false positive (extra masking) is negligible compared to the
     *  cost of a leaked password / token / API key. Keeps the same byte
     *  length using a fixed mask, so log truncation logic isn't affected.
     *  Backlog #19 — call from setRequest / setResponse paths. */
    private static final java.util.regex.Pattern SECRET_FIELD_REGEX =
        java.util.regex.Pattern.compile(
            "(\"(?:password|currentPassword|newPassword|oldPassword|accessToken|refreshToken|token|apiKey|api_key|secret|otp)\"\\s*:\\s*)\"[^\"]*\"",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    static String redactSecretFields(String body) {
        if (body == null || body.isEmpty()) return body;
        return SECRET_FIELD_REGEX.matcher(body).replaceAll("$1\"[REDACTED]\"");
    }
}