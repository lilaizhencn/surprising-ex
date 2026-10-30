package com.surprising.gateway.provider.legal.exception;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class LegalExceptionHandler {
    @ExceptionHandler(LegalProblem.class)
    public ResponseEntity<Map<String,String>> handle(LegalProblem problem) {
        return ResponseEntity.status(problem.kind().status()).body(Map.of("code","LEGAL_"+problem.kind().name(),"message",problem.getMessage()));
    }
}
