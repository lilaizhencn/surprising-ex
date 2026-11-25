package com.surprising.gateway.c2c.controller.advice;

import com.surprising.gateway.c2c.exception.C2cProblem;
import com.surprising.gateway.provider.exception.KycDocumentStorageException;
import com.surprising.gateway.provider.exception.ProductUnavailableException;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * C2C 模块 HTTP 错误状态分类与统一响应构造（由 {@code GlobalGatewayExceptionHandler} 统一挂载）。
 */
public final class C2cHttpErrors {

    private C2cHttpErrors() {
    }

    public static ResponseEntity<Map<String, String>> handle(RuntimeException failure) {
        if (failure instanceof ProductUnavailableException) {
            return error(HttpStatus.NOT_FOUND, "PRODUCT_NOT_ENABLED", failure.getMessage());
        }
        if (failure instanceof C2cProblem problem) {
            return error(status(problem), problem.code(), problem.getMessage());
        }
        if (failure instanceof IllegalArgumentException
                || failure instanceof ArithmeticException
                || failure instanceof HttpMessageNotReadableException
                || failure instanceof MethodArgumentTypeMismatchException) {
            String message = failure instanceof ArithmeticException ? "金额或数量超出精度、范围限制"
                    : failure instanceof IllegalArgumentException ? failure.getMessage() : "请求格式无效";
            return error(HttpStatus.BAD_REQUEST, "C2C_INVALID_REQUEST", message == null ? "请求参数无效" : message);
        }
        if (failure instanceof DataIntegrityViolationException) {
            return error(HttpStatus.CONFLICT, "C2C_CONFLICT", "资料已变更或与现有记录冲突，请刷新后重试");
        }
        if (failure instanceof ReadViewUnavailableException) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "C2C_UNAVAILABLE", "资金查询视图暂不可用，请查询原请求，勿重复提交");
        }
        if (failure instanceof DataAccessException) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "C2C_STORAGE_UNAVAILABLE", "C2C 数据暂不可用，请查询原请求，勿重复提交");
        }
        if (failure instanceof KycDocumentStorageException) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "C2C_ATTACHMENT_UNAVAILABLE", "文件存储暂不可用，请使用原上传请求查询或重试");
        }
        if (failure instanceof MaxUploadSizeExceededException) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "C2C_ATTACHMENT_TOO_LARGE", "文件超过上传限制");
        }
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "C2C_INTERNAL_ERROR", "C2C 状态暂不可确认，请查询原请求，勿重复提交");
    }

    /** HTTP响应和Controller失败审计使用相同分类；未知内部异常不暴露细节。 */
    public static HttpStatus status(RuntimeException failure) {
        if (failure instanceof ProductUnavailableException) return HttpStatus.NOT_FOUND;
        if (failure instanceof C2cProblem problem) return switch (problem.reason()) {
            case AUTHENTICATION_REQUIRED -> HttpStatus.UNAUTHORIZED;
            case ACCESS_DENIED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        if (failure instanceof ReadViewUnavailableException) return HttpStatus.SERVICE_UNAVAILABLE;
        if (failure instanceof DataIntegrityViolationException) return HttpStatus.CONFLICT;
        if (failure instanceof DataAccessException || failure instanceof KycDocumentStorageException) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (failure instanceof MaxUploadSizeExceededException) return HttpStatus.PAYLOAD_TOO_LARGE;
        if (failure instanceof IllegalArgumentException
                || failure instanceof ArithmeticException
                || failure instanceof HttpMessageNotReadableException
                || failure instanceof MethodArgumentTypeMismatchException) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    public static ResponseEntity<Map<String, String>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(Map.of("code", code, "message", message == null ? "操作未完成" : message));
    }
}
