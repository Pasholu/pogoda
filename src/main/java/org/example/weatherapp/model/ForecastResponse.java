package org.example.weatherapp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ForecastResponse(Current current, Hourly hourly, Daily daily) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Current(
            String time,
            @JsonProperty("temperature_2m") double temperature,
            @JsonProperty("relative_humidity_2m") int humidity,
            @JsonProperty("apparent_temperature") double apparentTemperature,
            @JsonProperty("dew_point_2m") double dewPoint,
            @JsonProperty("precipitation") double precipitation,
            @JsonProperty("cloud_cover") int cloudCover,
            @JsonProperty("surface_pressure") double pressure,
            @JsonProperty("wind_speed_10m") double wind,
            @JsonProperty("wind_gusts_10m") double gusts,
            @JsonProperty("weather_code") int code) {}

    /** Почвенные переменные Open-Meteo отдаёт только почасово, не в current. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hourly(
            List<String> time,
            @JsonProperty("soil_temperature_0cm") List<Double> soilTemperatureSurface,
            @JsonProperty("soil_temperature_6cm") List<Double> soilTemperatureSeedbed,
            @JsonProperty("soil_moisture_0_1cm") List<Double> soilMoistureSurface,
            @JsonProperty("soil_moisture_3_9cm") List<Double> soilMoistureRoot,
            @JsonProperty("et0_fao_evapotranspiration") List<Double> evapotranspiration,
            @JsonProperty("vapour_pressure_deficit") List<Double> vapourPressureDeficit) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Daily(
            @JsonProperty("temperature_2m_min") List<Double> temperatureMin,
            @JsonProperty("temperature_2m_max") List<Double> temperatureMax,
            @JsonProperty("precipitation_sum") List<Double> precipitationSum) {}
}
