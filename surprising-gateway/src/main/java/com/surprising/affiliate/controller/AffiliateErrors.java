package com.surprising.affiliate.controller;

import java.util.Map;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackages = "com.surprising.affiliate.controller")
public class AffiliateErrors {

  @ExceptionHandler({
    IllegalStateException.class,
    QueryTimeoutException.class,
    DataAccessResourceFailureException.class,
  })
  @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
  public Map<String, String> unavailable(RuntimeException error) {
    return Map.of("message", "返佣资料暂不可用，请稍后刷新或联系平台核对");
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, String>> status(
    ResponseStatusException error
  ) {
    return ResponseEntity.status(error.getStatusCode()).body(
      Map.of(
        "message",
        error.getReason() == null
          ? "返佣请求暂不可用，请稍后重试"
          : error.getReason()
      )
    );
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public Map<String, String> invalid(IllegalArgumentException error) {
    return Map.of("message", error.getMessage());
  }
}
