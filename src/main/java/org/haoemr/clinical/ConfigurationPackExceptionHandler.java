package org.haoemr.clinical;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes=ConfigurationPackController.class)
final class ConfigurationPackExceptionHandler {
    @ExceptionHandler(ConfigurationPackException.class)
    ResponseEntity<Map<String,Object>> handle(ConfigurationPackException error){return response(error.status(),error.code(),error.getMessage());}
    @ExceptionHandler(DocumentTemplateException.class)
    ResponseEntity<Map<String,Object>> template(DocumentTemplateException error){return response(error.status(),error.code(),error.getMessage());}
    private ResponseEntity<Map<String,Object>> response(int status,String code,String message){return ResponseEntity.status(status).body(Map.of("error",Map.of(
            "code",code,"message",message,"trace_id",UUID.randomUUID().toString(),"category",status==403?"AUTHORIZATION":"VALIDATION","retryable",false,"violations",List.of())));}
}
