package org.example.weatherapp.audit;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.example.weatherapp.audit.AuditCatalog.Station;
import org.example.weatherapp.audit.AuditCatalog.Variable;
import org.example.weatherapp.audit.AuditStore.ObservationRow;
import org.example.weatherapp.audit.AuditStore.StationMeta;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Витрина аудита: читает сводки и прогнозы из хранилища, склеивает их по станции и сроку
 * и агрегирует ошибки по областям. К источникам обращается, только если в хранилище пусто.
 */
@Service
public class AuditService {

    /** Неделя: сводки идут раз в 3 часа, на меньшем окне пар слишком мало. */
    private static final int WINDOW_HOURS = 168;
    /** Между выгрузками данные не меняются; после выгрузки кэш сбрасывается событием. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(60);

    private static final Map<String, Station> STATIONS = AuditCatalog.STATIONS.stream()
            .collect(Collectors.toMap(Station::wmo, Function.identity()));

    private final AuditStore store;
    private final IngestService ingest;

    private AuditReport cached;
    private Instant cachedAt;
    private long cachedVersion;
    /** Растёт с каждой выгрузкой; отчёт, посчитанный по более старым данным, пересчитывается. */
    private final AtomicLong dataVersion = new AtomicLong();

    public AuditService(AuditStore store, IngestService ingest) {
        this.store = store;
        this.ingest = ingest;
    }

    public synchronized AuditReport report() {
        long version = dataVersion.get();
        if (cached == null || cachedVersion != version || cachedAt.plus(CACHE_TTL).isBefore(Instant.now())) {
            cached = compute();
            cachedAt = Instant.now();
            cachedVersion = version;
        }
        return cached;
    }

    /** Без блокировки сервиса: выгрузка публикует событие, пока отчёт может ждать её же завершения. */
    @EventListener
    void onDataIngested(IngestService.DataIngested event) {
        dataVersion.incrementAndGet();
    }

    /** Сводки одной станции за окно аудита. */
    public StationDetail station(String wmo) {
        Station station = STATIONS.get(wmo);
        if (station == null) {
            throw new NoSuchElementException("Станции " + wmo + " нет в каталоге");
        }

        Instant since = Instant.now().minus(WINDOW_HOURS, ChronoUnit.HOURS);
        List<ObservationRow> rows = store.observations(wmo, since);
        if (rows.isEmpty() && store.observations(since).isEmpty()) {
            // В хранилище за окно пусто целиком: пробуем догрузить; отказ источника уйдёт вызывающему.
            ingest.loadMissing(WINDOW_HOURS);
            rows = store.observations(wmo, since);
        }

        StationMeta meta = store.stations().stream()
                .filter(item -> item.wmo().equals(wmo))
                .findFirst()
                .orElseThrow();
        return new StationDetail(
                station.wmo(), station.city(), station.region(),
                meta.latitude() == null ? 0 : meta.latitude(),
                meta.longitude() == null ? 0 : meta.longitude(),
                meta.sourceUrl(),
                WINDOW_HOURS,
                rows.stream()
                        .map(row -> new StationDetail.Row(
                                row.time().toString(), row.temperature(), row.dewPoint(), row.humidity(),
                                row.pressureStation(), row.pressureSea(), row.windDirection(), row.windSpeed(),
                                row.visibility(), row.cloudCover(), row.precipitation(),
                                row.minTemperature(), row.maxTemperature()))
                        .toList());
    }

    private AuditReport compute() {
        Instant since = Instant.now().minus(WINDOW_HOURS, ChronoUnit.HOURS);
        List<ObservationRow> observations = store.observations(since);
        if (observations.isEmpty()) {
            ingest.loadMissing(WINDOW_HOURS);
            observations = store.observations(since);
        }
        if (observations.isEmpty()) {
            throw new IllegalStateException("Узел данных Белгидромета не вернул наблюдений");
        }

        Map<ObservationKey, ObservationRow> byStationAndHour = new HashMap<>();
        Map<String, Integer> reportsByStation = new HashMap<>();
        int rejected = 0;
        for (ObservationRow row : observations) {
            byStationAndHour.put(new ObservationKey(row.wmo(), row.time()), row);
            reportsByStation.merge(row.wmo(), 1, Integer::sum);
            rejected += row.rejectedValues();
        }

        Map<CellKey, Accumulator> errors = new HashMap<>();
        int[] pairs = {0};
        store.forEachForecast(since, forecast -> {
            ObservationRow observation = byStationAndHour.get(new ObservationKey(forecast.wmo(), forecast.target()));
            Station station = STATIONS.get(forecast.wmo());
            if (observation == null || station == null) {
                return;
            }
            Double actual = actual(observation, forecast.variable());
            if (actual == null) {
                return;
            }
            double error = forecast.value() - actual;
            errors.computeIfAbsent(
                    new CellKey(station.region(), forecast.model(), forecast.variable(), forecast.horizonDays()),
                    key -> new Accumulator()).add(error);
            errors.computeIfAbsent(
                    new CellKey(AuditCatalog.COUNTRY, forecast.model(), forecast.variable(), forecast.horizonDays()),
                    key -> new Accumulator()).add(error);
            pairs[0]++;
        });

        List<AuditReport.StationStatus> statuses = new ArrayList<>();
        for (Station station : AuditCatalog.STATIONS) {
            statuses.add(new AuditReport.StationStatus(
                    station.wmo(), station.city(), station.region(),
                    reportsByStation.getOrDefault(station.wmo(), 0)));
        }

        List<AuditReport.Cell> cells = errors.entrySet().stream()
                .map(entry -> entry.getValue().toCell(entry.getKey()))
                .toList();

        List<String> regions = new ArrayList<>(AuditCatalog.REGIONS);
        regions.add(AuditCatalog.COUNTRY);

        return new AuditReport(
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                WINDOW_HOURS,
                AuditCatalog.MODELS.stream()
                        .map(model -> new AuditReport.ModelInfo(model.id(), model.name(), model.origin()))
                        .toList(),
                List.of(Variable.values()).stream()
                        .map(variable -> new AuditReport.VariableInfo(
                                variable.name().toLowerCase(Locale.ROOT), variable.label, variable.unit))
                        .toList(),
                AuditCatalog.HORIZON_DAYS.stream().map(days -> days * 24).toList(),
                regions,
                cells,
                new AuditReport.Totals(
                        reportsByStation.size(), AuditCatalog.STATIONS.size(),
                        observations.size(), pairs[0], rejected),
                statuses);
    }

    /** Фактическое значение параметра, прошедшее проверку правдоподобности. */
    private static Double actual(ObservationRow observation, Variable variable) {
        Double raw = switch (variable) {
            case TEMPERATURE -> observation.temperature();
            case HUMIDITY -> observation.humidity();
            case PRESSURE -> observation.pressureSea();
            case WIND -> observation.windSpeed();
        };
        return plausible(variable, raw);
    }

    /** Значение вне физических пределов параметра считается ошибкой передачи и не сравнивается. */
    static Double plausible(Variable variable, Double value) {
        return switch (variable) {
            case TEMPERATURE -> ObservationClient.within(value, -80, 60);
            case HUMIDITY -> ObservationClient.within(value, 1, 100);
            case PRESSURE -> ObservationClient.within(value, 870, 1085);
            case WIND -> ObservationClient.within(value, 0, 75);
        };
    }

    private record ObservationKey(String wmo, Instant time) {}

    private record CellKey(String region, String model, Variable variable, int horizonDays) {}

    private static final class Accumulator {
        private double absolute;
        private double squared;
        private double signed;
        private int count;

        void add(double error) {
            absolute += Math.abs(error);
            squared += error * error;
            signed += error;
            count++;
        }

        AuditReport.Cell toCell(CellKey key) {
            return new AuditReport.Cell(
                    key.region(),
                    key.model(),
                    key.variable().name().toLowerCase(Locale.ROOT),
                    key.horizonDays() * 24,
                    absolute / count,
                    Math.sqrt(squared / count),
                    signed / count,
                    count);
        }
    }
}
