package org.example.weatherapp.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.example.weatherapp.model.FieldConditions;
import org.example.weatherapp.model.ForecastResponse;
import org.example.weatherapp.model.GeoResponse;
import org.example.weatherapp.model.SiteReport;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class WeatherService {

    private static final String GEO_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private static final String CURRENT_FIELDS = String.join(",",
            "temperature_2m", "relative_humidity_2m", "apparent_temperature", "dew_point_2m",
            "precipitation", "cloud_cover", "surface_pressure", "wind_speed_10m",
            "wind_gusts_10m", "weather_code");
    private static final String HOURLY_FIELDS = String.join(",",
            "soil_temperature_0cm", "soil_temperature_6cm", "soil_moisture_0_1cm",
            "soil_moisture_3_9cm", "et0_fao_evapotranspiration", "vapour_pressure_deficit");
    private static final String DAILY_FIELDS = String.join(",",
            "temperature_2m_min", "temperature_2m_max", "precipitation_sum");

    // Коды интерпретации погоды WMO, которые отдаёт Open-Meteo.
    private static final Map<Integer, String> CONDITIONS = Map.ofEntries(
            Map.entry(0, "Ясно"),
            Map.entry(1, "Преимущественно ясно"),
            Map.entry(2, "Переменная облачность"),
            Map.entry(3, "Пасмурно"),
            Map.entry(45, "Туман"),
            Map.entry(48, "Изморозь"),
            Map.entry(51, "Слабая морось"),
            Map.entry(53, "Морось"),
            Map.entry(55, "Сильная морось"),
            Map.entry(56, "Ледяная морось"),
            Map.entry(57, "Сильная ледяная морось"),
            Map.entry(61, "Небольшой дождь"),
            Map.entry(63, "Дождь"),
            Map.entry(65, "Сильный дождь"),
            Map.entry(66, "Ледяной дождь"),
            Map.entry(67, "Сильный ледяной дождь"),
            Map.entry(71, "Небольшой снег"),
            Map.entry(73, "Снег"),
            Map.entry(75, "Сильный снег"),
            Map.entry(77, "Снежная крупа"),
            Map.entry(80, "Кратковременный дождь"),
            Map.entry(81, "Ливень"),
            Map.entry(82, "Сильный ливень"),
            Map.entry(85, "Снегопад"),
            Map.entry(86, "Сильный снегопад"),
            Map.entry(95, "Гроза"),
            Map.entry(96, "Гроза с градом"),
            Map.entry(99, "Сильная гроза с градом"));

    private final RestTemplate restTemplate = new RestTemplate();
    private final SensorSimulator sensorSimulator;
    private final AgronomyAdvisor agronomyAdvisor;

    public WeatherService(SensorSimulator sensorSimulator, AgronomyAdvisor agronomyAdvisor) {
        this.sensorSimulator = sensorSimulator;
        this.agronomyAdvisor = agronomyAdvisor;
    }

    public SiteReport report(String site) {
        GeoResponse.Place place = locate(site);
        FieldConditions field = conditions(place.latitude(), place.longitude());

        LocalDateTime now = LocalDateTime.now();
        SensorSimulator.Readings sensors =
                sensorSimulator.read(field, place.latitude(), place.longitude(), now.getHour());

        List<SiteReport.MetricGroup> groups = List.of(
                new SiteReport.MetricGroup("Воздух", "thermometer", List.of(
                        metric("air-temperature", "Температура", "°C",
                                field.airTemperature(), sensors.airTemperature(), 2.0, 1),
                        metric("humidity", "Влажность", "%",
                                field.humidity(), sensors.humidity(), 10.0, 0))),
                new SiteReport.MetricGroup("Почва", "sprout", List.of(
                        metric("soil-surface", "Температура, поверхность", "°C",
                                field.soilTemperatureSurface(), sensors.soilTemperatureSurface(), 2.5, 1),
                        metric("soil-seedbed", "Температура, глубина заделки", "°C",
                                field.soilTemperatureSeedbed(), sensors.soilTemperatureSeedbed(), 1.5, 1),
                        metric("moisture-surface", "Влага, верхний слой", "%",
                                field.soilMoistureSurface() * 100, sensors.soilMoistureSurface() * 100, 5.0, 0),
                        metric("moisture-root", "Влага, корневой слой", "%",
                                field.soilMoistureRoot() * 100, sensors.soilMoistureRoot() * 100, 5.0, 0))),
                new SiteReport.MetricGroup("Ветер и осадки", "wind", List.of(
                        metric("wind", "Ветер", "м/с",
                                field.wind(), sensors.wind(), 1.5, 1),
                        metric("precipitation", "Осадки за час", "мм",
                                field.precipitation(), sensors.precipitation(), 1.0, 1))));

        List<SiteReport.Observation> forecast = List.of(
                new SiteReport.Observation("Ощущается как", field.apparentTemperature(), "°C", 1),
                new SiteReport.Observation("Точка росы", field.dewPoint(), "°C", 1),
                new SiteReport.Observation("Порывы ветра", field.gusts(), "м/с", 1),
                new SiteReport.Observation("Облачность", field.cloudCover(), "%", 0),
                new SiteReport.Observation("Давление", field.pressure(), "гПа", 0),
                new SiteReport.Observation("Испаряемость", field.evapotranspiration(), "мм/ч", 2),
                new SiteReport.Observation("Дефицит влажности", field.vapourPressureDeficit(), "кПа", 2),
                new SiteReport.Observation("Минимум за сутки", field.temperatureMin(), "°C", 1),
                new SiteReport.Observation("Максимум за сутки", field.temperatureMax(), "°C", 1),
                new SiteReport.Observation("Осадки за сутки", field.precipitationSum(), "мм", 1));

        boolean drifted = groups.stream()
                .flatMap(group -> group.metrics().stream())
                .anyMatch(SiteReport.Metric::exceeded);

        String region = place.admin1() == null || place.admin1().isBlank()
                ? place.country()
                : place.admin1() + ", " + place.country();

        return new SiteReport(
                new SiteReport.Site(place.name(), region),
                now.format(TIME),
                new SiteReport.Condition(field.weatherCode(),
                        CONDITIONS.getOrDefault(field.weatherCode(), "Нет данных")),
                groups,
                forecast,
                agronomyAdvisor.adviseAll(field, sensors),
                drifted);
    }

    private GeoResponse.Place locate(String site) {
        URI uri = UriComponentsBuilder.fromUriString(GEO_URL)
                .queryParam("name", site)
                .queryParam("count", 1)
                .queryParam("language", "ru")
                .queryParam("format", "json")
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();

        GeoResponse response = restTemplate.getForObject(uri, GeoResponse.class);
        if (response == null || response.results() == null || response.results().isEmpty()) {
            throw new SiteNotFoundException("Площадка «" + site + "» не найдена");
        }
        return response.results().get(0);
    }

    private FieldConditions conditions(double latitude, double longitude) {
        URI uri = UriComponentsBuilder.fromUriString(FORECAST_URL)
                .queryParam("latitude", latitude)
                .queryParam("longitude", longitude)
                .queryParam("current", CURRENT_FIELDS)
                .queryParam("hourly", HOURLY_FIELDS)
                .queryParam("daily", DAILY_FIELDS)
                .queryParam("forecast_days", 1)
                .queryParam("wind_speed_unit", "ms")
                .queryParam("timezone", "auto")
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();

        ForecastResponse response = restTemplate.getForObject(uri, ForecastResponse.class);
        if (response == null || response.current() == null) {
            throw new IllegalStateException("Метеослужба не вернула данные");
        }

        ForecastResponse.Current current = response.current();
        int hour = hourIndex(response.hourly(), current.time());

        return new FieldConditions(
                current.temperature(),
                current.apparentTemperature(),
                current.humidity(),
                current.dewPoint(),
                current.precipitation(),
                current.wind(),
                current.gusts(),
                current.cloudCover(),
                current.pressure(),
                at(response.hourly() == null ? null : response.hourly().soilTemperatureSurface(), hour),
                at(response.hourly() == null ? null : response.hourly().soilTemperatureSeedbed(), hour),
                at(response.hourly() == null ? null : response.hourly().soilMoistureSurface(), hour),
                at(response.hourly() == null ? null : response.hourly().soilMoistureRoot(), hour),
                at(response.hourly() == null ? null : response.hourly().evapotranspiration(), hour),
                at(response.hourly() == null ? null : response.hourly().vapourPressureDeficit(), hour),
                at(response.daily() == null ? null : response.daily().temperatureMin(), 0),
                at(response.daily() == null ? null : response.daily().temperatureMax(), 0),
                at(response.daily() == null ? null : response.daily().precipitationSum(), 0),
                current.code());
    }

    /** Почасовой ряд начинается с полуночи, поэтому ищем строку текущего часа. */
    private static int hourIndex(ForecastResponse.Hourly hourly, String currentTime) {
        if (hourly == null || hourly.time() == null || currentTime == null) {
            return 0;
        }
        String prefix = currentTime.length() >= 13 ? currentTime.substring(0, 13) : currentTime;
        List<String> times = hourly.time();
        for (int i = 0; i < times.size(); i++) {
            if (times.get(i) != null && times.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return 0;
    }

    private static double at(List<Double> values, int index) {
        if (values == null || index < 0 || index >= values.size() || values.get(index) == null) {
            return 0;
        }
        return values.get(index);
    }

    private static SiteReport.Metric metric(
            String key, String label, String unit,
            double station, double sensor, double tolerance, int precision) {
        double delta = sensor - station;
        return new SiteReport.Metric(
                key, label, unit, station, sensor, delta, tolerance,
                Math.abs(delta) > tolerance, precision);
    }

    public static class SiteNotFoundException extends RuntimeException {
        public SiteNotFoundException(String message) {
            super(message);
        }
    }
}
