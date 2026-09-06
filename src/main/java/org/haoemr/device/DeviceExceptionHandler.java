package org.haoemr.device;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {DeviceTelemetryController.class, DeviceCatalogController.class})
final class DeviceExceptionHandler {
    @ExceptionHandler(DeviceException.class)
    ResponseEntity<Map<String, Object>> domainFailure(DeviceException failure) {
        return ResponseEntity.status(failure.status()).body(Map.of(
                "code", failure.code(),
                "message", failure.getMessage()));
    }
}
