package org.example.weatherapp.audit;

import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

@RestController
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /** days — длина периода в сутках (1–366), 0 — вся накопленная история. */
    @GetMapping("/api/audit")
    public ResponseEntity<?> audit(@RequestParam(defaultValue = "" + AuditService.DEFAULT_PERIOD_DAYS) int days) {
        if (days < 0 || days > 366) {
            return ResponseEntity.badRequest().body(Map.of("error", "Период — от 1 до 366 суток или 0 для всей истории"));
        }
        return ResponseEntity.ok(auditService.report(days));
    }

    @GetMapping("/api/audit/stations/{wmo}")
    public StationDetail station(@PathVariable String wmo) {
        return auditService.station(wmo);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleUnknownStation(NoSuchElementException cause) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", cause.getMessage()));
    }

    @ExceptionHandler({RestClientException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> handleUpstream(RuntimeException cause) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Источники данных недоступны: " + cause.getMessage()));
    }
}
