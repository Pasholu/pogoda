package org.example.weatherapp.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.example.weatherapp.audit.AuditCatalog.Model;
import org.example.weatherapp.audit.AuditCatalog.Station;
import org.example.weatherapp.audit.AuditCatalog.Variable;
import org.example.weatherapp.audit.ForecastClient.ForecastGrid;
import org.example.weatherapp.audit.ForecastClient.SeriesKey;
import org.example.weatherapp.audit.ObservationClient.RawReport;
import org.example.weatherapp.audit.ObservationClient.StationFeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;

/** Выгрузка в хранилище и расчёт отчёта по нему — на встраиваемой базе, без обращения к источникам. */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AuditStorageTests {

    private static final Station BREST = AuditCatalog.STATIONS.get(0);

    @MockitoBean
    private ObservationClient observationClient;

    @MockitoBean
    private ForecastClient forecastClient;

    @Autowired
    private IngestService ingest;

    @Autowired
    private AuditService audit;

    @Autowired
    private AuditStore store;

    @Autowired
    private JdbcTemplate jdbc;

    /** Последний прошедший срок наблюдений: целый час, кратный трём. */
    private Instant term;

    @BeforeEach
    void cleanStore() {
        jdbc.update("delete from forecast");
        jdbc.update("delete from observation");
        jdbc.update("delete from ingest_run");
        Instant hour = Instant.now().minus(6, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);
        term = hour.minus(hour.atOffset(java.time.ZoneOffset.UTC).getHour() % 3, ChronoUnit.HOURS);
    }

    @Test
    void repeatedIngestDoesNotDuplicateRows() {
        sourcesAnswer(10.0, 12.0);

        ingest.loadMissing(168);
        ingest.loadMissing(168);

        assertThat(count("observation")).isEqualTo(1);
        assertThat(count("forecast")).isEqualTo(forecastSeriesCount());
    }

    @Test
    void reportIsComputedFromStore() {
        sourcesAnswer(10.0, 12.5);
        ingest.loadMissing(168);

        AuditReport report = audit.report();

        AuditReport.Cell cell = report.cells().stream()
                .filter(item -> item.region().equals(BREST.region())
                        && item.variable().equals("temperature") && item.horizon() == 24)
                .findFirst()
                .orElseThrow();
        assertThat(cell.mae()).isEqualTo(2.5);
        assertThat(cell.bias()).isEqualTo(2.5);
        assertThat(report.totals().stationsActive()).isEqualTo(1);
        assertThat(report.totals().observations()).isEqualTo(1);
    }

    @Test
    void storedDataSurvivesSourceOutage() {
        sourcesAnswer(10.0, 12.0);
        ingest.loadMissing(168);
        sourcesFail();

        assertThat(audit.report().totals().observations()).isEqualTo(1);
        assertThat(audit.station(BREST.wmo()).rows()).hasSize(1);
    }

    @Test
    void emptyStoreAndDeadSourceStillFail() {
        sourcesFail();

        assertThatThrownBy(() -> audit.report()).isInstanceOf(ResourceAccessException.class);
        assertThat(store.recentRuns(5)).anyMatch(run -> run.status().equals("FAILED"));
    }

    @Test
    void implausibleValueIsCountedAndExcluded() {
        sourcesAnswer(999.0, 12.0);
        ingest.loadMissing(168);

        AuditReport report = audit.report();

        assertThat(report.totals().rejectedValues()).isEqualTo(1);
        assertThat(report.cells()).noneMatch(cell -> cell.variable().equals("temperature"));
    }

    @Test
    void tablesAreBrowsableWithFilters() {
        sourcesAnswer(10.0, 12.0);
        ingest.loadMissing(168);

        AuditStore.TablePage observations = store.browse("observation", Map.of(), 0, 50);
        AuditStore.TablePage oneModel = store.browse(
                "forecast", Map.of("model", AuditCatalog.MODELS.get(0).id(), "variable", "TEMPERATURE"), 0, 2);

        assertThat(observations.total()).isEqualTo(1);
        assertThat(observations.rows().get(0)).containsEntry("station_wmo", BREST.wmo());
        assertThat(observations.rows().get(0).get("report_time")).isEqualTo(term.toString());
        assertThat(oneModel.total()).isEqualTo(AuditCatalog.HORIZON_DAYS.size());
        assertThat(oneModel.rows()).hasSize(2);
        assertThat(AuditStore.browsable("flyway_schema_history")).isFalse();
    }

    /** Брест передаёт одну сводку с заданной температурой; все модели дают один и тот же прогноз. */
    private void sourcesAnswer(double observedTemperature, double forecastTemperature) {
        when(observationClient.fetchReports(any(), any())).thenAnswer(call -> {
            Station station = call.getArgument(0);
            List<RawReport> reports = station.equals(BREST)
                    ? List.of(new RawReport(term, Map.of(ObservationClient.TEMPERATURE, observedTemperature)))
                    : List.of();
            return new StationFeed(52.1, 23.7, "https://example.test/" + station.wmo(), reports);
        });

        Map<SeriesKey, double[]> series = new HashMap<>();
        for (Model model : AuditCatalog.MODELS) {
            for (Variable variable : Variable.values()) {
                for (int days : AuditCatalog.HORIZON_DAYS) {
                    series.put(new SeriesKey(model.id(), variable, days), new double[] {forecastTemperature});
                }
            }
        }
        when(forecastClient.fetch(any(), anyInt()))
                .thenReturn(List.of(new ForecastGrid(Map.of(term, 0), series)));
    }

    private void sourcesFail() {
        // doThrow, а не when: иначе при повторной настройке сработал бы прежний ответ заглушки.
        doThrow(new ResourceAccessException("узел недоступен")).when(observationClient).fetchReports(any(), any());
        doThrow(new ResourceAccessException("архив недоступен")).when(forecastClient).fetch(any(), anyInt());
    }

    private int forecastSeriesCount() {
        return AuditCatalog.MODELS.size() * Variable.values().length * AuditCatalog.HORIZON_DAYS.size();
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}
