package com.surprising.gateway.provider.announcement.exception;

import com.surprising.gateway.provider.announcement.controller.AdminAnnouncementController;
import com.surprising.gateway.provider.announcement.controller.ClientAnnouncementController;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes={AdminAnnouncementController.class,ClientAnnouncementController.class})
public class AnnouncementExceptionHandler {
    @ExceptionHandler(AnnouncementProblem.class)
    public ResponseEntity<Map<String,Object>> handle(AnnouncementProblem problem) {
        int status=switch(problem.kind()) {
            case INVALID_STATE -> 400;
            case MISSING -> 404;
            case CONFLICT -> 409;
            case AUTH_REQUIRED -> 401;
            case ACCESS_DENIED -> 403;
        };
        return ResponseEntity.status(status).body(Map.of("status",status,"message",problem.getMessage()));
    }
}
