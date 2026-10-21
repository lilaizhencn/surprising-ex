package com.surprising.gateway.c2c.controller.advice;

import com.surprising.gateway.c2c.exception.C2cProblem;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(basePackages = "com.surprising.gateway.c2c")
public class C2cHttpErrors {
    @ModelAttribute
    public void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
    }

    @ExceptionHandler(C2cProblem.class)
    public ResponseEntity<?> problem(C2cProblem problem) {
        return error(status(problem), problem.code(), problem.getMessage());
    }
    @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalid(Exception failure) {
        String message = failure instanceof ArithmeticException ? "金额或数量超出精度、范围限制"
                : failure instanceof IllegalArgumentException ? failure.getMessage() : "请求格式无效";
        return error(HttpStatus.BAD_REQUEST, "C2C_INVALID_REQUEST", message == null ? "请求参数无效" : message);
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> conflict(DataIntegrityViolationException failure) {
        return error(HttpStatus.CONFLICT, "C2C_CONFLICT", "资料已变更或与现有记录冲突，请刷新后重试");
    }
    @ExceptionHandler(com.surprising.realtime.api.exception.ReadViewUnavailableException.class)
    public ResponseEntity<?> readView(com.surprising.realtime.api.exception.ReadViewUnavailableException failure) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "C2C_UNAVAILABLE", "资金查询视图暂不可用，请查询原请求，勿重复提交");
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> internal(RuntimeException failure) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "C2C_INTERNAL_ERROR", "C2C 状态暂不可确认，请查询原请求，勿重复提交");
    }
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> storage(DataAccessException failure) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "C2C_STORAGE_UNAVAILABLE", "C2C 数据暂不可用，请查询原请求，勿重复提交");
    }
    @ExceptionHandler(com.surprising.gateway.provider.service.KycDocumentStorageException.class)
    public ResponseEntity<?> attachmentStorage(RuntimeException failure) {
        return error(HttpStatus.SERVICE_UNAVAILABLE,"C2C_ATTACHMENT_UNAVAILABLE","文件存储暂不可用，请使用原上传请求查询或重试");
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<?> tooLarge(RuntimeException failure) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE,"C2C_ATTACHMENT_TOO_LARGE","文件超过上传限制");
    }
    /** HTTP响应和Controller失败审计使用相同分类；未知内部异常不暴露细节。 */
    public static HttpStatus status(RuntimeException failure) {
        if (failure instanceof C2cProblem problem) return switch (problem.reason()) {
            case AUTHENTICATION_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        if (failure instanceof com.surprising.realtime.api.exception.ReadViewUnavailableException) return HttpStatus.SERVICE_UNAVAILABLE;
        if (failure instanceof DataIntegrityViolationException) return HttpStatus.CONFLICT;
        if (failure instanceof DataAccessException || failure instanceof com.surprising.gateway.provider.service.KycDocumentStorageException)
            return HttpStatus.SERVICE_UNAVAILABLE;
        if (failure instanceof org.springframework.web.multipart.MaxUploadSizeExceededException) return HttpStatus.PAYLOAD_TOO_LARGE;
        if (failure instanceof IllegalArgumentException || failure instanceof ArithmeticException || failure instanceof HttpMessageNotReadableException)
            return HttpStatus.BAD_REQUEST;
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(Map.of("code", code, "message", message == null ? "操作未完成" : message));
    }
}
