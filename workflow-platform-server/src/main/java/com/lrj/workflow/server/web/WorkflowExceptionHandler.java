package com.lrj.workflow.server.web;

import com.lrj.workflow.core.WorkflowConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** REST 错误映射:冲突→409,参数非法→400。 */
@RestControllerAdvice
public class WorkflowExceptionHandler {

    @ExceptionHandler(WorkflowConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(WorkflowConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "CONFLICT", "message", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    }
}
