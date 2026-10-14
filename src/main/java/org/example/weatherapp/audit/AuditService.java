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
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.example.weatherapp.audit.AuditCatalog.Station;
import org.example.weatherapp.audit.AuditCatalog.Variable;
import org.example.weatherapp.audit.AuditStore.ErrorSums;
import org.example.weatherapp.audit.AuditStore.ObservationRow;
import org.example.weatherapp.audit.AuditStore.ObservationTotals;
import org.example.weatherapp.audit.AuditStore.StationMeta;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Витрина аудита за выбранный период: пары прогноз–факт склеиваются и суммируются
 * в базе, здесь суммы сводятся в MAE, RMSE и смещение по областям и по стране.
 * К источникам данных обращается, только если база совсем пуста.
 */
@Service
public class AuditService {

    /** Период по умолчанию — неделя; 0 означает всю накопленную историю. */
    public static final int DEFAULT_PERIOD_DAYS = 7;
    /** Окно сводок станции в её окне просмотра. */
    private static final int STATION_WINDOW_HOURS = 168;
    /** Между выгрузками данные не меняются; после выгрузки кэш сбрасывается событием. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(60);

    private static final Map<String, Station> STATIONS = AuditCatalog.STATIONS.stream()
            .collect(Collectors.toMap(Station::wmo, Function.identity()));

    private final AuditStore store;
    private final IngestService ingest;

    private record Cached(AuditReport report, Instant at, long version) {}

    private final Map<Integer, Cached> cache = new ConcurrentHashMap<>();
    /** Растёт с каждой выгрузкой; отчёт, посчитанный по более старым данным, пересчитывается. */
    private final AtomicLong dataVersion = new AtomicLong();

    public AuditService(AuditStore store, IngestService ingest) {
        this.store = store;
        this.ingest = ingest;
    }

    public AuditReport report() {
        return report(DEFAULT_PERIOD_DAYS);
    }

    /** Отчёт за последние periodDays суток; 0 — за всё время. */
    public synchronized AuditReport report(int periodDays) {
        long version = dataVersion.get();
        Cached hit = cache.get(periodDays);
        if (hit == null || hit.version() != version || hit.at().plus(CACHE_TTL).isBefore(Instant.now())) {
            hit = new Cached(compute(periodDays), Instant.now(), version);
            cache.put(periodDays, hit);
        }
        return hit.report();
    }

    /** Без блокировки сервиса: выгрузка публикует событие, пока отчёт может ждать её же завершения. */
    @EventListener
    void onDataIngested(IngestService.DataIngested event) {
        dataVersion.incrementAndGet();
    }

    /** Сводки одной станции за последнюю неделю. */
    public StationDetail station(String wmo) {
        Station station = STATIONS.get(wmo);
        if (station == null) {
            throw new NoSuchElementException("Станции " + wmo + " нет в каталоге");
        }

        Instant since = Instant.now().minus(STATION_WINDOW_HOURS, ChronoUnit.HOURS);
        List<ObservationRow> rows = store.observations(wmo, since);
        if (rows.isEmpty() && store.coverage().observations() == 0) {
            // База пуста целиком: пробуем догрузить; отказ источника уйдёт вызывающему.
            ingest.loadMissing(STATION_WINDOW_HOURS);
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
                STATION_WINDOW_HOURS,
                rows.stream()
                        .map(row -> new StationDetail.Row(
                                row.time().toString(), row.temperature(), row.dewPoint(), row.humidity(),
                                row.pressureStation(), row.pressureSea(), row.windDirection(), row.windSpeed(),
                                row.visibility(), row.cloudCover(), row.precipitation(),
                                row.minTemperature(), row.maxTemperature()))
                        .toList());
    }

    private AuditReport compute(int periodDays) {
        Instant since = periodDays > 0 ? Instant.now().minus(periodDays, ChronoUnit.DAYS) : Instant.EPOCH;
        ObservationTotals totals = store.observationTotals(since);
        if (totals.observations() == 0 && store.coverage().observations() == 0) {
            ingest.loadMissing(DEFAULT_PERIOD_DAYS * 24);
            totals = store.observationTotals(since);
        }
        if (totals.observations() == 0) {
            throw new IllegalStateException("В базе нет наблюдений за выбранный период");
        }

        // По областям суммы приходят из базы; по стране — складываются из областей.
        Map<CellKey, Accumulator> errors = new HashMap<>();
        long pairs = 0;
        TreeSet<Integer> horizons = new TreeSet<>();
        for (ErrorSums sums : store.errorSums(since)) {
            errors.computeIfAbsent(new CellKey(sums.region(), sums.model(), sums.variable(), sums.horizonDays()),
                    key -> new Accumulator()).add(sums);
            errors.computeIfAbsent(new CellKey(AuditCatalog.COUNTRY, sums.model(), sums.variable(), sums.horizonDays()),
                    key -> new Accumulator()).add(sums);
            pairs += sums.count();
            horizons.add(sums.horizonDays());
        }

        List<AuditReport.StationStatus> statuses = new ArrayList<>();
        for (Station station : AuditCatalog.STATIONS) {
            statuses.add(new AuditReport.StationStatus(
                    station.wmo(), station.city(), station.region(),
                    totals.byStation().getOrDefault(station.wmo(), 0)));
        }

        List<AuditReport.Cell> cells = errors.entrySet().stream()
                .map(entry -> entry.getValue().toCell(entry.getKey()))
                .toList();

        List<String> regions = new ArrayList<>(AuditCatalog.REGIONS);
        regions.add(AuditCatalog.COUNTRY);

        return new AuditReport(
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
                periodDays,
                totals.oldest().toString(),
                totals.newest().toString(),
                AuditCatalog.MODELS.stream()
                        .map(model -> new AuditReport.ModelInfo(model.id(), model.name(), model.origin()))
                        .toList(),
                List.of(Variable.values()).stream()
                        .map(variable -> new AuditReport.VariableInfo(
                                variable.name().toLowerCase(Locale.ROOT), variable.label, variable.unit))
                        .toList(),
                horizons.stream().map(days -> days * 24).toList(),
                regions,
                cells,
                new AuditReport.Totals(
                        totals.byStation().size(), AuditCatalog.STATIONS.size(),
                        totals.observations(), pairs, totals.rejectedValues()),
                statuses);
    }

    private record CellKey(String region, String model, Variable variable, int horizonDays) {}

    private static final class Accumulator {
        private double absolute;
        private double squared;
        private double signed;
        private long count;

        void add(ErrorSums sums) {
            absolute += sums.absolute();
            squared += sums.squared();
            signed += sums.signed();
            count += sums.count();
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
