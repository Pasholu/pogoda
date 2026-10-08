package org.example.weatherapp.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.example.weatherapp.audit.AuditCatalog.Station;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Фактические наблюдения государственной сети Белгидромета из его открытого
 * узла данных ВМО (WIS2). Сводки приходят каждые 3 часа; каждая запись узла —
 * один параметр одного срока, здесь они собираются в сводки по срокам.
 */
@Component
class ObservationClient {

    private static final String SYNOP_URL =
            "https://wis2box.belgidromet.by/oapi/collections/urn:wmo:md:by-belgidromet:synop/items";

    /** Одна страница вмещает примерно две недели сводок станции. */
    private static final int PAGE_LIMIT = 2000;
    /** Предел на случай, если узел перестанет сообщать о конце выдачи. */
    private static final int MAX_PAGES = 12;

    static final String TEMPERATURE = "air_temperature";
    static final String DEW_POINT = "dewpoint_temperature";
    static final String PRESSURE_SEA = "pressure_reduced_to_mean_sea_level";
    static final String PRESSURE_STATION = "non_coordinate_pressure";
    static final String WIND_SPEED = "wind_speed";
    static final String WIND_DIRECTION = "wind_direction";
    static final String VISIBILITY = "horizontal_visibility";
    static final String CLOUD_COVER = "cloud_cover_total";
    static final String PRECIPITATION = "total_precipitation_or_total_water_equivalent";
    static final String MIN_TEMPERATURE = "minimum_temperature_at_height_and_over_period_specified";
    static final String MAX_TEMPERATURE = "maximum_temperature_at_height_and_over_period_specified";

    /** Значения одного срока станции в том виде, в каком их передал узел: имя параметра → значение. */
    record RawReport(Instant time, Map<String, Double> values) {}

    /** Сводки станции и то, что узел сообщает о ней самой. */
    record StationFeed(double latitude, double longitude, String sourceUrl, List<RawReport> reports) {}

    private final RestTemplate rest;
    private final ObjectMapper json = new ObjectMapper();

    ObservationClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofSeconds(90));
        this.rest = new RestTemplate(factory);
    }

    /** Все сводки станции не старше since, от новых к старым. */
    StationFeed fetchReports(Station station, Instant since) {
        // У станции бывает два идентификатора WIGOS, данные лежат под одним из них.
        String identifier = "0-20000-0-" + station.wmo();
        JsonNode features = readTree(stationUri(identifier, 0)).path("features");
        if (features.isEmpty()) {
            identifier = "0-20001-0-" + station.wmo();
            features = readTree(stationUri(identifier, 0)).path("features");
        }

        Map<Instant, Map<String, Double>> reports = new HashMap<>();
        double latitude = 0;
        double longitude = 0;
        for (int page = 0; page < MAX_PAGES && !features.isEmpty(); page++) {
            boolean reachedSince = false;
            for (JsonNode feature : features) {
                JsonNode properties = feature.path("properties");
                Instant time = Instant.parse(properties.path("reportTime").asText());
                if (time.isBefore(since)) {
                    reachedSince = true;
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
            if (reachedSince || features.size() < PAGE_LIMIT) {
                break;
            }
            features = readTree(stationUri(identifier, (page + 1) * PAGE_LIMIT)).path("features");
        }

        List<RawReport> rows = new ArrayList<>();
        reports.forEach((time, values) -> rows.add(new RawReport(time, values)));
        rows.sort((a, b) -> b.time().compareTo(a.time()));
        return new StationFeed(latitude, longitude, stationUri(identifier, 0).toString(), rows);
    }

    private URI stationUri(String wigosIdentifier, int offset) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(SYNOP_URL)
                .queryParam("f", "json")
                .queryParam("wigos_station_identifier", wigosIdentifier)
                .queryParam("sortby", "-reportTime")
                .queryParam("limit", PAGE_LIMIT);
        if (offset > 0) {
            builder.queryParam("offset", offset);
        }
        return builder.build().toUri();
    }

    private JsonNode readTree(URI uri) {
        try {
            return json.readTree(rest.getForObject(uri, byte[].class));
        } catch (IOException cause) {
            throw new IllegalStateException("Не удалось разобрать ответ узла данных Белгидромета", cause);
        }
    }

    /** Формула Магнуса: относительная влажность по температуре и точке росы. */
    static double relativeHumidity(double temperature, double dewPoint) {
        double saturated = Math.exp(17.625 * temperature / (243.04 + temperature));
        double actual = Math.exp(17.625 * dewPoint / (243.04 + dewPoint));
        return Math.min(100, 100 * actual / saturated);
    }

    /** Проверка правдоподобности: значение вне физических пределов отбрасывается. */
    static Double within(Double value, double min, double max) {
        return value != null && value >= min && value <= max ? value : null;
    }
}
