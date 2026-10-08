package org.example.weatherapp.audit;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.example.weatherapp.audit.AuditCatalog.Station;
import org.example.weatherapp.audit.AuditStore.ForecastRow;
import org.example.weatherapp.audit.AuditStore.ObservationRow;
import org.example.weatherapp.audit.AuditStore.StationMeta;
import org.example.weatherapp.audit.ForecastClient.ForecastGrid;
import org.example.weatherapp.audit.ForecastClient.Point;
import org.example.weatherapp.audit.ForecastClient.SeriesKey;
import org.example.weatherapp.audit.ObservationClient.RawReport;
import org.example.weatherapp.audit.ObservationClient.StationFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Выгрузка данных в хранилище: сводки Белгидромета каждые 3 часа, прогнозы каждый час,
 * плюс догрузка истории задним числом. Все выгрузки идемпотентны и идут по одной.
 */
@Service
public class IngestService {

    /** Публикуется после каждой выгрузки, записавшей данные: по нему сбрасывается кэш отчёта. */
    public record DataIngested() {}

    static final String SOURCE = "belgidromet-wis2";

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private static final int ATTEMPTS = 3;
    /** Если подряд с самого начала не ответило столько станций, узел считается недоступным целиком. */
    private static final int DEAD_SOURCE_THRESHOLD = 3;
    /** Столько точек идёт в один запрос архива прогнозов: больше — ответ становится слишком тяжёлым. */
    private static final int FORECAST_CHUNK = 7;
    /** Архив прогнозов Open-Meteo отдаёт не больше 92 суток назад. */
    static final int MAX_BACKFILL_DAYS = 92;

    private final ObservationClient observationClient;
    private final ForecastClient forecastClient;
    private final AuditStore store;
    private final ApplicationEventPublisher events;
    private final boolean enabled;
    private final Duration retryPause;

    private final ExecutorService background = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "audit-backfill");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean backfillRunning = new AtomicBoolean();

    IngestService(ObservationClient observationClient, ForecastClient forecastClient, AuditStore store,
                  ApplicationEventPublisher events,
                  @Value("${audit.ingest.enabled:true}") boolean enabled,
                  @Value("${audit.ingest.retry-pause-ms:2000}") long retryPauseMillis) {
        this.observationClient = observationClient;
        this.forecastClient = forecastClient;
        this.store = store;
        this.events = events;
        this.enabled = enabled;
        this.retryPause = Duration.ofMillis(retryPauseMillis);
    }

    /** При старте догружает последнюю неделю: на новой машине база наполняется сама. */
    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        store.seedStations(AuditCatalog.STATIONS);
        if (enabled) {
            backfill(7);
        }
    }

    @Scheduled(cron = "${audit.ingest.observations-cron:0 20 */3 * * *}", zone = "UTC")
    void scheduledObservations() {
        if (enabled) {
            quietly("observations", () -> ingestObservations(Instant.now().minus(2, ChronoUnit.DAYS)));
        }
    }

    @Scheduled(cron = "${audit.ingest.forecasts-cron:0 5 * * * *}", zone = "UTC")
    void scheduledForecasts() {
        if (enabled) {
            quietly("forecasts", () -> ingestForecasts(2));
        }
    }

    /** Догрузка истории за days суток в фоне; false — если предыдущая ещё идёт. */
    public boolean backfill(int days) {
        if (!backfillRunning.compareAndSet(false, true)) {
            return false;
        }
        background.execute(() -> {
            try {
                quietly("backfill observations", () -> ingestObservations(Instant.now().minus(days, ChronoUnit.DAYS)));
                quietly("backfill forecasts", () -> ingestForecasts(days + 1));
            } finally {
                backfillRunning.set(false);
            }
        });
        return true;
    }

    public boolean backfillRunning() {
        return backfillRunning.get();
    }

    /** Синхронная догрузка недели — когда отчёт запросили, а в хранилище за это окно пусто. */
    synchronized void loadMissing(int windowHours) {
        ingestObservations(Instant.now().minus(windowHours, ChronoUnit.HOURS));
        ingestForecasts(windowHours / 24 + 1);
    }

    /**
     * Сводки всех станций каталога не старше since. Отказ одной станции не срывает остальные;
     * исключение бросается, только если не ответила ни одна.
     */
    synchronized int ingestObservations(Instant since) {
        Instant startedAt = Instant.now();
        store.seedStations(AuditCatalog.STATIONS);
        int written = 0;
        int answered = 0;
        List<String> failed = new ArrayList<>();
        RuntimeException lastFailure = null;

        for (Station station : AuditCatalog.STATIONS) {
            try {
                StationFeed feed = withRetry(() -> observationClient.fetchReports(station, since));
                answered++;
                if (feed.reports().isEmpty()) {
                    continue;
                }
                List<ObservationRow> rows = feed.reports().stream().map(report -> row(station, report)).toList();
                store.saveStationMeta(station.wmo(), feed.latitude(), feed.longitude(), feed.sourceUrl());
                store.replaceObservations(rows, SOURCE);
                written += rows.size();
            } catch (RuntimeException cause) {
                failed.add(station.city());
                lastFailure = cause;
                log.warn("Сводки станции {} не получены: {}", station.city(), cause.toString());
                if (answered == 0 && failed.size() >= DEAD_SOURCE_THRESHOLD) {
                    break;
                }
            }
        }

        if (answered == 0 && lastFailure != null) {
            store.logRun("observations", startedAt, "FAILED", 0, lastFailure.toString());
            throw lastFailure;
        }
        store.logRun("observations", startedAt, failed.isEmpty() ? "OK" : "PARTIAL", written,
                failed.isEmpty() ? null : "Не ответили: " + String.join(", ", failed));
        if (written > 0) {
            events.publishEvent(new DataIngested());
        }
        return written;
    }

    /** Прогнозы всех моделей для станций с известными координатами за pastDays суток. */
    synchronized int ingestForecasts(int pastDays) {
        Instant startedAt = Instant.now();
        List<StationMeta> located = store.stations().stream()
                .filter(station -> station.latitude() != null && station.longitude() != null)
                .toList();
        int written = 0;
        try {
            for (int from = 0; from < located.size(); from += FORECAST_CHUNK) {
                List<StationMeta> chunk = located.subList(from, Math.min(from + FORECAST_CHUNK, located.size()));
                List<Point> points = chunk.stream()
                        .map(station -> new Point(station.latitude(), station.longitude()))
                        .toList();
                List<ForecastGrid> grids = withRetry(() -> forecastClient.fetch(points, pastDays));
                for (int i = 0; i < chunk.size() && i < grids.size(); i++) {
                    List<ForecastRow> rows = rows(chunk.get(i).wmo(), grids.get(i));
                    store.replaceForecasts(chunk.get(i).wmo(), rows);
                    written += rows.size();
                }
            }
        } catch (RuntimeException cause) {
            store.logRun("forecasts", startedAt, "FAILED", written, cause.toString());
            throw cause;
        }
        store.logRun("forecasts", startedAt, "OK", written, null);
        if (written > 0) {
            events.publishEvent(new DataIngested());
        }
        return written;
    }

    private static ObservationRow row(Station station, RawReport report) {
        Map<String, Double> v = report.values();
        Double temperature = v.get(ObservationClient.TEMPERATURE);
        Double dewPoint = v.get(ObservationClient.DEW_POINT);
        Double humidity = temperature != null && dewPoint != null
                ? ObservationClient.relativeHumidity(temperature, dewPoint)
                : null;
        Double pressureSea = v.get(ObservationClient.PRESSURE_SEA);
        Double windSpeed = v.get(ObservationClient.WIND_SPEED);
        int rejected = 0;
        for (AuditCatalog.Variable variable : AuditCatalog.Variable.values()) {
            Double raw = switch (variable) {
                case TEMPERATURE -> temperature;
                case HUMIDITY -> humidity;
                case PRESSURE -> pressureSea;
                case WIND -> windSpeed;
            };
            if (raw != null && AuditService.plausible(variable, raw) == null) {
                rejected++;
            }
        }
        return new ObservationRow(
                station.wmo(), report.time(), temperature, dewPoint, humidity, pressureSea,
                v.get(ObservationClient.PRESSURE_STATION), windSpeed,
                v.get(ObservationClient.WIND_DIRECTION), v.get(ObservationClient.VISIBILITY),
                v.get(ObservationClient.CLOUD_COVER), v.get(ObservationClient.PRECIPITATION),
                v.get(ObservationClient.MIN_TEMPERATURE), v.get(ObservationClient.MAX_TEMPERATURE),
                rejected);
    }

    /** В хранилище идут только сроки наблюдений (каждые 3 часа) — с остальными часами сравнивать нечего. */
    private static List<ForecastRow> rows(String wmo, ForecastGrid grid) {
        List<ForecastRow> rows = new ArrayList<>();
        Instant now = Instant.now();
        for (Map.Entry<Instant, Integer> hour : grid.hourIndex().entrySet()) {
            Instant target = hour.getKey();
            if (target.isAfter(now) || target.atOffset(ZoneOffset.UTC).getHour() % 3 != 0) {
                continue;
            }
            int index = hour.getValue();
            for (Map.Entry<SeriesKey, double[]> series : grid.series().entrySet()) {
                double[] values = series.getValue();
                if (index < values.length && !Double.isNaN(values[index])) {
                    SeriesKey key = series.getKey();
                    rows.add(new ForecastRow(
                            wmo, key.model(), key.variable(), key.horizonDays(), target, values[index]));
                }
            }
        }
        return rows;
    }

    /** Повторяет обращение к источнику при сбое; после последней попытки отдаёт ошибку вызывающему. */
    private <T> T withRetry(Supplier<T> call) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (RuntimeException cause) {
                last = cause;
                if (attempt < ATTEMPTS) {
                    pause(retryPause.multipliedBy(attempt));
                }
            }
        }
        throw last;
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Фоновая выгрузка не должна ронять планировщик: отказ источника остаётся в журнале. */
    private static void quietly(String job, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException cause) {
            log.warn("Выгрузка «{}» не удалась: {}", job, cause.toString());
        }
    }
}
