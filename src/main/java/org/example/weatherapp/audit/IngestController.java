package org.example.weatherapp.audit;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Состояние хранилища и запуск догрузки истории задним числом. */
@RestController
public class IngestController {

    public record IngestStatus(
            boolean backfillRunning, AuditStore.Coverage coverage, List<AuditStore.IngestRun> recentRuns) {}

    private final IngestService ingest;
    private final AuditStore store;

    IngestController(IngestService ingest, AuditStore store) {
        this.ingest = ingest;
        this.store = store;
    }

    @GetMapping("/api/admin/ingest")
    public IngestStatus status() {
        return new IngestStatus(ingest.backfillRunning(), store.coverage(), store.recentRuns(20));
    }

    /** Просмотр записей хранилища: только чтение, только перечисленные в хранилище таблицы. */
    @GetMapping("/api/admin/data/{table}")
    public ResponseEntity<?> data(@PathVariable String table,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size,
                                  @RequestParam Map<String, String> filters) {
        if (!AuditStore.browsable(table)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Таблицы " + table + " нет"));
        }
        if (page < 0 || size < 1 || size > 200) {
            return ResponseEntity.badRequest().body(Map.of("error", "Страница — от 0, размер — от 1 до 200 строк"));
        }
        try {
            return ResponseEntity.ok(store.browse(table, filters, page, size));
        } catch (NumberFormatException cause) {
            return ResponseEntity.badRequest().body(Map.of("error", "Горизонт задаётся числом суток"));
        }
    }

    /** Например, POST /api/admin/backfill?days=30 — догрузить месяц. */
    @PostMapping("/api/admin/backfill")
    public ResponseEntity<Map<String, String>> backfill(@RequestParam(defaultValue = "7") int days) {
        if (days < 1 || days > IngestService.MAX_BACKFILL_DAYS) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Глубина догрузки — от 1 до " + IngestService.MAX_BACKFILL_DAYS + " суток"));
        }
        if (!ingest.backfill(days)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Предыдущая догрузка ещё идёт"));
        }
        return ResponseEntity.accepted().body(Map.of("status", "Догрузка за " + days + " сут. запущена"));
    }
}
