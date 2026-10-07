package org.example.weatherapp.model;

/**
 * Агрономические пороги культуры. Значения — типовые для условий Беларуси;
 * если у хозяйства свои регламенты, правятся здесь.
 */
public record CropProfile(
        String id,
        String name,
        /** Ниже этой ночной температуры повреждение считается критическим. */
        double frostLimit,
        String frostNote,
        /** Выше этой дневной температуры начинается тепловой стресс. */
        double heatLimit,
        String heatNote,
        double sowingSoilMin,
        double sowingSoilMax,
        /** Условия, при которых начинается заражение листа. */
        double diseaseHumidity,
        double diseaseTempMin,
        double diseaseTempMax,
        String diseaseNames,
        /** Объёмная влажность корневого слоя, м³/м³. */
        double moistureDeficit,
        double moistureExcess,
        String harvestNote) {}
