package org.example.weatherapp.model;

import java.util.List;
import org.example.weatherapp.service.AgronomyAdvisor;

/** Сводка по площадке: метеослужба, показания датчиков, расхождение и агрономические выводы. */
public record SiteReport(
        Site site,
        String readAt,
        Condition condition,
        List<MetricGroup> groups,
        List<Observation> forecast,
        List<AgronomyAdvisor.CropAdvice> crops,
        boolean drifted) {

    public record Site(String name, String region) {}

    public record Condition(int code, String label) {}

    public record MetricGroup(String title, String icon, List<Metric> metrics) {}

    /** Показатель, который меряют оба источника — есть что сравнивать. */
    public record Metric(
            String key,
            String label,
            String unit,
            double station,
            double sensor,
            double delta,
            double tolerance,
            boolean exceeded,
            int precision) {}

    /** Величина, которой у датчиков нет: только расчёт или прогноз метеослужбы. */
    public record Observation(String label, double value, String unit, int precision) {}
}
