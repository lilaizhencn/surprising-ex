package com.surprising.gateway.provider.controller.advice;

import com.surprising.gateway.provider.exception.ProductUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 普通HTTP入口的产品就绪失败；签名/代理等特殊响应在其实际入口按原契约映射。 */
@RestControllerAdvice(basePackages = "com.surprising.gateway.provider")
public class ProductAvailabilityHttpAdvice {
    @ExceptionHandler(ProductUnavailableException.class)
    public ProblemDetail unavailable(ProductUnavailableException failure) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, failure.getMessage());
    }
}
