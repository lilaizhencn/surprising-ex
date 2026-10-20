package com.surprising.gateway.c2c;

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
    ResponseEntity<?> problem(C2cProblem problem) {
        return error(problem.status, problem.code, problem.getMessage());
    }
    @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception failure) {
        String message = failure instanceof ArithmeticException ? "金额或数量超出精度、范围限制"
                : failure instanceof IllegalArgumentException ? failure.getMessage() : "请求格式无效";
        return error(HttpStatus.BAD_REQUEST, "C2C_INVALID_REQUEST", message == null ? "请求参数无效" : message);
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(DataIntegrityViolationException failure) {
        return error(HttpStatus.CONFLICT, "C2C_CONFLICT", "资料已变更或与现有记录冲突，请刷新后重试");
    }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<?> conflict(IllegalStateException failure) {
        return error(HttpStatus.CONFLICT, "C2C_CONFLICT", failure.getMessage());
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> storage(DataAccessException failure) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "C2C_STORAGE_UNAVAILABLE", "C2C 数据暂不可用，请查询原请求，勿重复提交");
    }
    private ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store")
                .body(Map.of("code", code, "message", message == null ? "操作未完成" : message));
    }
}
