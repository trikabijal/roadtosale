package com.auditpro.roadtosale.web;

/**
 * Standard envelope for all JSON responses (success and error): {@code {status, message, data}}.
 *
 * @param <T> payload type ({@code null} on error)
 */
public class ApiResponse<T> {

    private int status;
    private String message;
    private T data;

    public ApiResponse() {
    }

    public ApiResponse(int status, String message, T data) {
        this.status = status;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "OK", data);
    }

    public static <T> ApiResponse<T> of(int status, String message, T data) {
        return new ApiResponse<>(status, message, data);
    }

    public static ApiResponse<Object> error(int status, String message) {
        return new ApiResponse<>(status, message, null);
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
