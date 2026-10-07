package org.example.weatherapp.audit;

import java.util.List;

/** Витрина точности: ошибки каждой модели по областям, параметрам и горизонтам. */
public record AuditReport(
        String generatedAt,
        int windowHours,
        List<ModelInfo> models,
        List<VariableInfo> variables,
        List<Integer> horizons,
        List<String> regions,
        List<Cell> cells,
        Totals totals,
        List<StationStatus> stations) {

    public record ModelInfo(String id, String name, String origin) {}

    public record VariableInfo(String id, String label, String unit) {}

    /** Ошибка = прогноз − факт; bias показывает, в какую сторону модель ошибается. */
    public record Cell(
            String region, String model, String variable, int horizon,
            double mae, double rmse, double bias, int samples) {}

    public record Totals(
            int stationsActive, int stationsTotal, int observations, int pairs, int rejectedValues) {}

    public record StationStatus(String wmo, String city, String region, int observations) {}
}
