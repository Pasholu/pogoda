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
import java.util.concurrent.ConcurrentHashMap;
import org.example.weatherapp.audit.AuditCatalog.Model;
import org.example.weatherapp.audit.AuditCatalog.Station;
import org.example.weatherapp.audit.AuditCatalog.Variable;
import org.example.weatherapp.audit.ForecastClient.ForecastGrid;
import org.example.weatherapp.audit.ForecastClient.SeriesKey;
import org.example.weatherapp.audit.ObservationClient.Observation;
import org.example.weatherapp.audit.ObservationClient.StationObservations;
import org.springframework.stereotype.Service;

/**
 * Пайплайн аудита в миниатюре: извлечение наблюдений и прогнозов,
 * склейка по станции и сроку, агрегация ошибок в витрину по областям.
 */
@Service
public class AuditService {

    /** Неделя: сводки идут раз в 3 часа, на меньшем окне пар слишком мало. */
    private static final int WINDOW_HOURS = 168;
    /** Прогнозы обновляются раз в час, сводки — раз в три; чаще пересчитывать незачем. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(60);

    private final ObservationClient observationClient;
    private final ForecastClient forecastClient;

    private AuditReport cached;
    private Instant cachedAt;
    private final Map<String, CachedDetail> details = new ConcurrentHashMap<>();

    public AuditService(ObservationClient observationClient, ForecastClient forecastClient) {
        this.observationClient = observationClient;
        this.forecastClient = forecastClient;
    }

    public synchronized AuditReport report() {
        if (cached == null || cachedAt.plus(CACHE_TTL).isBefore(Instant.now())) {
            cached = compute();
            cachedAt = Instant.now();
        }
        return cached;
    }

    /** Исходные сводки одной станции; кэшируются так же, как отчёт. */
    public StationDetail station(String wmo) {
        Station station = AuditCatalog.STATIONS.stream()
                .filter(item -> item.wmo().equals(wmo))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Станции " + wmo + " нет в каталоге"));

        CachedDetail hit = details.get(wmo);
        if (hit == null || hit.loadedAt().plus(CACHE_TTL).isBefore(Instant.now())) {
            hit = new CachedDetail(observationClient.fetchStation(station, WINDOW_HOURS), Instant.now());
            details.put(wmo, hit);
        }
        return hit.detail();
    }

    private record CachedDetail(StationDetail detail, Instant loadedAt) {}

    private AuditReport compute() {
        List<StationObservations> active = observationClient.fetchAll(WINDOW_HOURS);
        if (active.isEmpty()) {
            throw new IllegalStateException("Узел данных Белгидромета не вернул наблюдений");
        }

        Map<String, Integer> reportsByStation = new HashMap<>();
        for (StationObservations station : active) {
            reportsByStation.put(station.station().wmo(), station.byHour().size());
        }
        List<AuditReport.StationStatus> statuses = new ArrayList<>();
        for (Station station : AuditCatalog.STATIONS) {
            statuses.add(new AuditReport.StationStatus(
                    station.wmo(), station.city(), station.region(),
                    reportsByStation.getOrDefault(station.wmo(), 0)));
        }

        List<ForecastGrid> grids = forecastClient.fetch(active, WINDOW_HOURS / 24 + 1);

        Map<CellKey, Accumulator> errors = new HashMap<>();
        int observations = 0;
        int pairs = 0;
        int rejected = 0;

        for (int i = 0; i < active.size(); i++) {
            StationObservations station = active.get(i);
            ForecastGrid grid = grids.get(i);
            String region = station.station().region();
            rejected += station.rejectedValues();

            for (Map.Entry<Instant, Observation> entry : station.byHour().entrySet()) {
                observations++;
                Integer index = grid.hourIndex().get(entry.getKey());
                if (index == null) {
                    continue;
                }
                for (Model model : AuditCatalog.MODELS) {
                    for (Variable variable : Variable.values()) {
                        Double actual = actual(entry.getValue(), variable);
                        if (actual == null) {
                            continue;
                        }
                        for (int days : AuditCatalog.HORIZON_DAYS) {
                            double[] series = grid.series().get(new SeriesKey(model.id(), variable, days));
                            if (series == null || index >= series.length || Double.isNaN(series[index])) {
                                continue;
                            }
                            double error = series[index] - actual;
                            errors.computeIfAbsent(new CellKey(region, model.id(), variable, days),
                                    key -> new Accumulator()).add(error);
                            errors.computeIfAbsent(new CellKey(AuditCatalog.COUNTRY, model.id(), variable, days),
                                    key -> new Accumulator()).add(error);
                            pairs++;
                        }
                    }
                }
            }
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
                new AuditReport.Totals(active.size(), AuditCatalog.STATIONS.size(), observations, pairs, rejected),
                statuses);
    }

    private static Double actual(Observation observation, Variable variable) {
        return switch (variable) {
            case TEMPERATURE -> observation.temperature();
            case HUMIDITY -> observation.humidity();
            case PRESSURE -> observation.pressure();
            case WIND -> observation.wind();
        };
    }

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
