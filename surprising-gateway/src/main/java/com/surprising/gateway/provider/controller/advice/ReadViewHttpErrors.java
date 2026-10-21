package com.surprising.gateway.provider.controller.advice;

import com.surprising.realtime.api.exception.ReadViewUnavailableException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 产品内部HTTP入口沿用Servlet错误响应；公共网关由LocalBusinessApi映射同一原生失败。 */
@RestControllerAdvice(basePackages = {"com.surprising.account.provider.controller", "com.surprising.trading.order.controller",
        "com.surprising.trading.trigger.controller", "com.surprising.trading.matching.controller"})
public class ReadViewHttpErrors {
    @ExceptionHandler(ReadViewUnavailableException.class)
    public void unavailable(ReadViewUnavailableException failure, HttpServletResponse response) throws IOException {
        response.sendError(503, failure.getMessage());
    }
}
