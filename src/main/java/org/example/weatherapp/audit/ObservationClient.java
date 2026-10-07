package org.example.weatherapp.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.example.weatherapp.audit.AuditCatalog.Station;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Фактические наблюдения государственной сети Белгидромета из его открытого
 * узла данных ВМО (WIS2). Сводки приходят каждые 3 часа; здесь же они
 * приводятся к единому набору параметров и проходят проверку правдоподобности.
 */
@Component
class ObservationClient {

    private static final String SYNOP_URL =
            "https://wis2box.belgidromet.by/oapi/collections/urn:wmo:md:by-belgidromet:synop/items";

    /** С запасом на 7 суток: 28 станций × 8 сроков в сутки. */
    private static final int PAGE_LIMIT = 2000;

    private static final String TEMPERATURE = "air_temperature";
    private static final String DEW_POINT = "dewpoint_temperature";
    private static final String PRESSURE = "pressure_reduced_to_mean_sea_level";
    private static final String WIND = "wind_speed";

    record Observation(Double temperature, Double humidity, Double pressure, Double wind) {}

    record StationObservations(
            Station station,
            double latitude,
            double longitude,
            Map<Instant, Observation> byHour,
            int rejectedValues) {}

    /** Сырые значения одного срока одной станции до нормализации. */
    private static final class Report {
        Double temperature;
        Double dewPoint;
        Double pressure;
        Double wind;
    }

    private static final class StationData {
        double latitude;
        double longitude;
        final Map<Instant, Report> reports = new HashMap<>();
    }

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper json = new ObjectMapper();

    /** Наблюдения станций каталога за последние hours часов; станции без сводок не возвращаются. */
    List<StationObservations> fetchAll(int hours) {
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);

        // Каждая запись узла — один параметр одного срока, поэтому запрашиваем по параметрам.
        List<String> names = List.of(TEMPERATURE, DEW_POINT, PRESSURE, WIND);
        List<CompletableFuture<JsonNode>> requests = names.stream()
                .map(name -> CompletableFuture.supplyAsync(() -> fetchParameter(name)))
                .toList();

        Map<String, StationData> byStation = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            JsonNode page;
            try {
                page = requests.get(i).join();
            } catch (CompletionException wrapped) {
                // Контроллер ждёт исходную ошибку сети, а не обёртку пула потоков.
                throw wrapped.getCause() instanceof RuntimeException cause ? cause : wrapped;
            }
            for (JsonNode feature : page.path("features")) {
                JsonNode properties = feature.path("properties");
                JsonNode value = properties.path("value");
                if (!value.isNumber()) {
                    continue;
                }
                Instant time = Instant.parse(properties.path("reportTime").asText());
                if (time.isBefore(since)) {
                    continue;
                }

                StationData station = byStation.computeIfAbsent(
                        wmoIndex(properties.path("wigos_station_identifier").asText()), key -> new StationData());
                JsonNode coordinates = feature.path("geometry").path("coordinates");
                station.longitude = coordinates.path(0).asDouble();
                station.latitude = coordinates.path(1).asDouble();

                Report report = station.reports.computeIfAbsent(time, key -> new Report());
                switch (name) {
                    case TEMPERATURE -> report.temperature = value.asDouble();
                    case DEW_POINT -> report.dewPoint = value.asDouble();
                    case PRESSURE -> report.pressure = value.asDouble();
                    default -> report.wind = value.asDouble();
                }
            }
        }

        List<StationObservations> result = new ArrayList<>();
        for (Station station : AuditCatalog.STATIONS) {
            StationData data = byStation.get(station.wmo());
            if (data == null || data.reports.isEmpty()) {
                continue;
            }
            Map<Instant, Observation> byHour = new HashMap<>();
            int rejected = 0;
            for (Map.Entry<Instant, Report> entry : data.reports.entrySet()) {
                Report raw = entry.getValue();
                Double humidity = raw.temperature != null && raw.dewPoint != null
                        ? relativeHumidity(raw.temperature, raw.dewPoint)
                        : null;
                Observation clean = new Observation(
                        within(raw.temperature, -80, 60),
                        within(humidity, 1, 100),
                        within(raw.pressure, 870, 1085),
                        within(raw.wind, 0, 75));
                rejected += countPresent(raw.temperature, humidity, raw.pressure, raw.wind)
                        - countPresent(clean.temperature(), clean.humidity(), clean.pressure(), clean.wind());
                byHour.put(entry.getKey(), clean);
            }
            result.add(new StationObservations(station, data.latitude, data.longitude, byHour, rejected));
        }
        return result;
    }

    /** Все параметры всех сводок одной станции — для просмотра исходных данных. */
    StationDetail fetchStation(Station station, int hours) {
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);

        // У станции бывает два идентификатора WIGOS, данные лежат под одним из них.
        URI uri = stationUri("0-20000-0-" + station.wmo());
        JsonNode features = readTree(uri).path("features");
        if (features.isEmpty()) {
            uri = stationUri("0-20001-0-" + station.wmo());
            features = readTree(uri).path("features");
        }

        Map<Instant, Map<String, Double>> reports = new HashMap<>();
        double latitude = 0;
        double longitude = 0;
        for (JsonNode feature : features) {
            JsonNode properties = feature.path("properties");
            Instant time = Instant.parse(properties.path("reportTime").asText());
            if (time.isBefore(since)) {
                continue;
            }
            longitude = feature.path("geometry").path("coordinates").path(0).asDouble();
            latitude = feature.path("geometry").path("coordinates").path(1).asDouble();
            Map<String, Double> values = reports.computeIfAbsent(time, key -> new HashMap<>());
            JsonNode value = properties.path("value");
            if (value.isNumber()) {
                // Осадки и экстремумы могут повторяться за разные периоды — берём первый.
                values.putIfAbsent(properties.path("name").asText(), value.asDouble());
            }
        }

        List<StationDetail.Row> rows = reports.entrySet().stream()
                .sorted(Map.Entry.<Instant, Map<String, Double>>comparingByKey().reversed())
                .map(entry -> {
                    Map<String, Double> v = entry.getValue();
                    Double temperature = v.get(TEMPERATURE);
                    Double dewPoint = v.get(DEW_POINT);
                    return new StationDetail.Row(
                            entry.getKey().toString(),
                            temperature,
                            dewPoint,
                            temperature != null && dewPoint != null ? relativeHumidity(temperature, dewPoint) : null,
                            v.get("non_coordinate_pressure"),
                            v.get(PRESSURE),
                            v.get("wind_direction"),
                            v.get(WIND),
                            v.get("horizontal_visibility"),
                            v.get("cloud_cover_total"),
                            v.get("total_precipitation_or_total_water_equivalent"),
                            v.get("minimum_temperature_at_height_and_over_period_specified"),
                            v.get("maximum_temperature_at_height_and_over_period_specified"));
                })
                .toList();

        return new StationDetail(
                station.wmo(), station.city(), station.region(), latitude, longitude, uri.toString(), hours, rows);
    }

    private URI stationUri(String wigosIdentifier) {
        return UriComponentsBuilder.fromUriString(SYNOP_URL)
                .queryParam("f", "json")
                .queryParam("wigos_station_identifier", wigosIdentifier)
                .queryParam("sortby", "-reportTime")
                .queryParam("limit", PAGE_LIMIT)
                .build()
                .toUri();
    }

    private JsonNode readTree(URI uri) {
        try {
            return json.readTree(rest.getForObject(uri, byte[].class));
        } catch (IOException cause) {
            throw new IllegalStateException("Не удалось разобрать ответ узла данных Белгидромета", cause);
        }
    }

    private JsonNode fetchParameter(String name) {
        URI uri = UriComponentsBuilder.fromUriString(SYNOP_URL)
                .queryParam("f", "json")
                .queryParam("name", name)
                .queryParam("sortby", "-reportTime")
                .queryParam("limit", PAGE_LIMIT)
                .build()
                .toUri();
        return readTree(uri);
    }

    /** «0-20000-0-26850» → «26850»; у одной станции бывает несколько идентификаторов WIGOS. */
    private static String wmoIndex(String wigosIdentifier) {
        return wigosIdentifier.substring(wigosIdentifier.lastIndexOf('-') + 1);
    }

    /** Формула Магнуса: относительная влажность по температуре и точке росы. */
    private static double relativeHumidity(double temperature, double dewPoint) {
        double saturated = Math.exp(17.625 * temperature / (243.04 + temperature));
        double actual = Math.exp(17.625 * dewPoint / (243.04 + dewPoint));
        return Math.min(100, 100 * actual / saturated);
    }

    private static Double within(Double value, double min, double max) {
        return value != null && value >= min && value <= max ? value : null;
    }

    private static int countPresent(Double... values) {
        int count = 0;
        for (Double value : values) {
            if (value != null) {
                count++;
            }
        }
        return count;
    }
}
