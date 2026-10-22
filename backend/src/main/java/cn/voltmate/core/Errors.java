package cn.voltmate.core;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class Errors {
    private static final Logger log=LoggerFactory.getLogger(Errors.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException ex) {
        return ResponseEntity.status(ex.status).body(Map.of("code",ex.code,"message",ex.getMessage(),"time",Instant.now().toString()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(Map.of("code","VALIDATION","message","请求参数无效","time",Instant.now().toString()));
    }
    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<?> header(MissingRequestHeaderException ex) {
        return ResponseEntity.status(401).body(Map.of("code","UNAUTHORIZED","message","请登录","time",Instant.now().toString()));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unknown(Exception ex) {
        log.error("Unhandled API failure",ex);
        return ResponseEntity.internalServerError().body(Map.of("code","INTERNAL_ERROR","message","服务暂不可用","time",Instant.now().toString()));
    }
}
