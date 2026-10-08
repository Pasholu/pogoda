package org.example.weatherapp.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import org.example.weatherapp.audit.AuditCatalog.Model;
import org.example.weatherapp.audit.AuditCatalog.Variable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Архив прогнозов Open-Meteo: для каждого часа хранится не только последний прогноз,
 * но и выпущенные за 1, 2 и 3 суток до него — это и есть горизонты аудита.
 */
@Component
class ForecastClient {

    private static final String PREVIOUS_RUNS_URL = "https://previous-runs-api.open-meteo.com/v1/forecast";

    record Point(double latitude, double longitude) {}

    record SeriesKey(String model, Variable variable, int horizonDays) {}

    /** Прогнозные ряды одной точки; пропуски провайдера хранятся как NaN. */
    record ForecastGrid(Map<Instant, Integer> hourIndex, Map<SeriesKey, double[]> series) {}

    private final RestTemplate rest;
    private final ObjectMapper json = new ObjectMapper();

    ForecastClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(15));
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.rest = new RestTemplate(factory);
    }

    /** Возвращает сетки в том же порядке, что и точки на входе. */
    List<ForecastGrid> fetch(List<Point> points, int pastDays) {
        List<String> hourly = new ArrayList<>();
        for (Variable variable : Variable.values()) {
            for (int days : AuditCatalog.HORIZON_DAYS) {
                hourly.add(variable.apiName + "_previous_day" + days);
            }
        }

        URI uri = UriComponentsBuilder.fromUriString(PREVIOUS_RUNS_URL)
                .queryParam("latitude", join(points, Point::latitude))
                .queryParam("longitude", join(points, Point::longitude))
                .queryParam("hourly", String.join(",", hourly))
                .queryParam("models", AuditCatalog.MODELS.stream().map(Model::id).collect(Collectors.joining(",")))
                .queryParam("past_days", pastDays)
                .queryParam("forecast_days", 1)
                .queryParam("wind_speed_unit", "ms")
                .queryParam("timezone", "GMT")
                .build()
                .toUri();

        JsonNode response;
        try {
            response = json.readTree(rest.getForObject(uri, byte[].class));
        } catch (IOException cause) {
            throw new IllegalStateException("Не удалось разобрать ответ архива прогнозов", cause);
        }

        // Для одной точки API отвечает объектом, для нескольких — массивом.
        List<JsonNode> locations = new ArrayList<>();
        if (response.isArray()) {
            response.forEach(locations::add);
        } else {
            locations.add(response);
        }

        List<ForecastGrid> grids = new ArrayList<>();
        for (JsonNode location : locations) {
            grids.add(grid(location.path("hourly")));
        }
        return grids;
    }

    private static ForecastGrid grid(JsonNode hourly) {
        Map<Instant, Integer> hourIndex = new HashMap<>();
        JsonNode times = hourly.path("time");
        for (int i = 0; i < times.size(); i++) {
            hourIndex.put(LocalDateTime.parse(times.get(i).asText()).toInstant(ZoneOffset.UTC), i);
        }

        Map<SeriesKey, double[]> series = new HashMap<>();
        for (Model model : AuditCatalog.MODELS) {
            for (Variable variable : Variable.values()) {
                for (int days : AuditCatalog.HORIZON_DAYS) {
                    JsonNode values = hourly.path(variable.apiName + "_previous_day" + days + "_" + model.id());
                    double[] parsed = new double[values.size()];
                    for (int i = 0; i < values.size(); i++) {
                        parsed[i] = values.get(i).isNumber() ? values.get(i).asDouble() : Double.NaN;
                    }
                    series.put(new SeriesKey(model.id(), variable, days), parsed);
                }
            }
        }
        return new ForecastGrid(hourIndex, series);
    }

    private static String join(List<Point> points, ToDoubleFunction<Point> coordinate) {
        return points.stream()
                .map(point -> String.valueOf(coordinate.applyAsDouble(point)))
                .collect(Collectors.joining(","));
    }
}
