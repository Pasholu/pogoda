package org.example.weatherapp.audit;

import java.util.List;

/** Витрина точности: ошибки каждой модели по областям, параметрам и горизонтам за выбранный период. */
public record AuditReport(
        String generatedAt,
        /** Длина периода в сутках; 0 — вся накопленная история. */
        int periodDays,
        /** Самая ранняя и самая поздняя сводка, попавшие в период. */
        String periodFrom,
        String periodTo,
        List<ModelInfo> models,
        List<VariableInfo> variables,
        /** Горизонты в часах, для которых в периоде нашлись пары прогноз–факт. */
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
            double mae, double rmse, double bias, long samples) {}

    public record Totals(
            int stationsActive, int stationsTotal, long observations, long pairs, long rejectedValues) {}

    public record StationStatus(String wmo, String city, String region, int observations) {}
}
