package com.lrj.workflow.server.web;

import com.lrj.workflow.core.WorkflowConflictException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** REST 错误映射:冲突→409,参数非法→400,Flowable 对象不存在→404(如终止已结束实例/操作不存在的作业)。 */
@RestControllerAdvice
public class WorkflowExceptionHandler {

    @ExceptionHandler(WorkflowConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(WorkflowConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "CONFLICT", "message", e.getMessage()));
    }

    @ExceptionHandler(FlowableObjectNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(FlowableObjectNotFoundException e) {
        String msg = e.getMessage() == null ? "目标对象不存在(可能已结束或已删除)" : e.getMessage();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND", "message", msg));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    }
}
