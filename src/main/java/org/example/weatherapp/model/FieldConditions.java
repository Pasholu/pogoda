package org.example.weatherapp.model;

/**
 * Сведённые к одному моменту условия на площадке.
 * Влажность почвы — объёмная доля (м³/м³), испаряемость — мм за час.
 */
public record FieldConditions(
        double airTemperature,
        double apparentTemperature,
        double humidity,
        double dewPoint,
        double precipitation,
        double wind,
        double gusts,
        int cloudCover,
        double pressure,
        double soilTemperatureSurface,
        double soilTemperatureSeedbed,
        double soilMoistureSurface,
        double soilMoistureRoot,
        double evapotranspiration,
        double vapourPressureDeficit,
        double temperatureMin,
        double temperatureMax,
        double precipitationSum,
        int weatherCode) {}
