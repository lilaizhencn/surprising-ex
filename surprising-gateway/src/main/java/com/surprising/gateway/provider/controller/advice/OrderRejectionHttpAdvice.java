package com.surprising.gateway.provider.controller.advice;

import com.surprising.trading.order.exception.OrderCommandRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 原生Core业务拒绝在实际HTTP入口转换；公共本地网关维持原JSON错误契约。 */
@RestControllerAdvice(basePackages={"com.surprising.trading.order.controller","com.surprising.trading.trigger.controller",
        "com.surprising.account.provider.controller","com.surprising.gateway.provider.controller"})
public class OrderRejectionHttpAdvice {
    @ExceptionHandler(OrderCommandRejectedException.class)
    public ProblemDetail rejected(OrderCommandRejectedException failure) {
        var body=ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,failure.getMessage());
        body.setProperty("code",failure.code().name());
        return body;
    }
}
