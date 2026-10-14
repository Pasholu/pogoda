package org.example.weatherapp.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.example.weatherapp.audit.AuditCatalog.Station;
import org.example.weatherapp.audit.AuditCatalog.Variable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Доступ к хранилищу аудита. Запись идемпотентна: повторная выгрузка тех же сроков
 * заменяет строки, а не дублирует их — за этим следят первичные ключи таблиц.
 */
@Repository
class AuditStore {

    /** Сводка станции за один срок; параметр равен null, если в этот срок он не передавался. */
    record ObservationRow(
            String wmo,
            Instant time,
            Double temperature,
            Double dewPoint,
            Double humidity,
            Double pressureSea,
            Double pressureStation,
            Double windSpeed,
            Double windDirection,
            Double visibility,
            Double cloudCover,
            Double precipitation,
            Double minTemperature,
            Double maxTemperature,
            int rejectedValues) {}

    record ForecastRow(String wmo, String model, Variable variable, int horizonDays, Instant target, double value) {}

    record StationMeta(String wmo, Double latitude, Double longitude, String sourceUrl) {}

    record IngestRun(
            String job, String startedAt, String finishedAt, String status, int rowsWritten, String message) {}

    record Coverage(long observations, long forecasts, String oldestObservation, String newestObservation) {}

    /** Суммы ошибок одной клетки витрины: область × модель × параметр × горизонт. */
    record ErrorSums(
            String region, String model, Variable variable, int horizonDays,
            long count, double absolute, double squared, double signed) {}

    /** Сводки за период: сколько всего, сколько отброшено значений, сколько у каждой станции. */
    record ObservationTotals(long observations, long rejectedValues, Map<String, Integer> byStation,
                             Instant oldest, Instant newest) {}

    /** Страница таблицы для просмотра: имена колонок по порядку, строки и общее число строк под фильтром. */
    record TablePage(String table, List<String> columns, List<Map<String, Object>> rows, long total) {}

    /** Таблицы, открытые для просмотра, и порядок строк в каждой. Имя таблицы в запрос идёт только отсюда. */
    private static final Map<String, String> BROWSABLE = Map.of(
            "observation", "report_time desc, station_wmo",
            "forecast", "target_time desc, station_wmo, model, variable, horizon_days",
            "station", "wmo",
            "ingest_run", "id desc");

    /** Колонки, по которым разрешён отбор, для каждой таблицы. */
    private static final Map<String, List<String>> FILTERS = Map.of(
            "observation", List.of("station_wmo"),
            "forecast", List.of("station_wmo", "model", "variable", "horizon_days"),
            "station", List.of(),
            "ingest_run", List.of());

    private static final int BATCH_SIZE = 5000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    AuditStore(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    /** Заносит станции каталога, которых в таблице ещё нет. */
    void seedStations(List<Station> stations) {
        for (Station station : stations) {
            Integer known = jdbc.queryForObject(
                    "select count(*) from station where wmo = ?", Integer.class, station.wmo());
            if (known == null || known == 0) {
                jdbc.update("insert into station (wmo, city, region) values (?, ?, ?)",
                        station.wmo(), station.city(), station.region());
            }
        }
    }

    void saveStationMeta(String wmo, double latitude, double longitude, String sourceUrl) {
        jdbc.update("update station set latitude = ?, longitude = ?, source_url = ? where wmo = ?",
                latitude, longitude, sourceUrl, wmo);
    }

    List<StationMeta> stations() {
        return jdbc.query("select wmo, latitude, longitude, source_url from station order by wmo",
                (rs, row) -> new StationMeta(
                        rs.getString("wmo"),
                        nullableDouble(rs, "latitude"),
                        nullableDouble(rs, "longitude"),
                        rs.getString("source_url")));
    }

    /** Заменяет сводки станции за пришедшие сроки; остальные сроки не трогает. */
    void replaceObservations(List<ObservationRow> rows, String source) {
        if (rows.isEmpty()) {
            return;
        }
        OffsetDateTime loadedAt = OffsetDateTime.now(ZoneOffset.UTC);
        transaction.executeWithoutResult(status -> {
            jdbc.batchUpdate("delete from observation where station_wmo = ? and report_time = ?",
                    rows, BATCH_SIZE, (statement, row) -> {
                        statement.setString(1, row.wmo());
                        statement.setObject(2, utc(row.time()));
                    });
            jdbc.batchUpdate("""
                    insert into observation (station_wmo, report_time, temperature, dew_point, humidity,
                        pressure_sea, pressure_station, wind_speed, wind_direction, visibility, cloud_cover,
                        precipitation, min_temperature, max_temperature, rejected_values, source, loaded_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    rows, BATCH_SIZE, (statement, row) -> {
                        statement.setString(1, row.wmo());
                        statement.setObject(2, utc(row.time()));
                        statement.setObject(3, row.temperature());
                        statement.setObject(4, row.dewPoint());
                        statement.setObject(5, row.humidity());
                        statement.setObject(6, row.pressureSea());
                        statement.setObject(7, row.pressureStation());
                        statement.setObject(8, row.windSpeed());
                        statement.setObject(9, row.windDirection());
                        statement.setObject(10, row.visibility());
                        statement.setObject(11, row.cloudCover());
                        statement.setObject(12, row.precipitation());
                        statement.setObject(13, row.minTemperature());
                        statement.setObject(14, row.maxTemperature());
                        statement.setInt(15, row.rejectedValues());
                        statement.setString(16, source);
                        statement.setObject(17, loadedAt);
                    });
        });
    }

    /** Заменяет прогнозы станции в промежутке, который покрывает пришедшая выгрузка. */
    void replaceForecasts(String wmo, List<ForecastRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Instant from = rows.stream().map(ForecastRow::target).min(Instant::compareTo).orElseThrow();
        Instant to = rows.stream().map(ForecastRow::target).max(Instant::compareTo).orElseThrow();
        OffsetDateTime loadedAt = OffsetDateTime.now(ZoneOffset.UTC);
        transaction.executeWithoutResult(status -> {
            // Только горизонты архива: прогнозы на 8–16 суток в те же сроки сохранены отдельно.
            jdbc.update("""
                    delete from forecast where station_wmo = ? and target_time >= ? and target_time <= ?
                    and horizon_days <= ?""",
                    wmo, utc(from), utc(to), AuditCatalog.HORIZON_DAYS.get(AuditCatalog.HORIZON_DAYS.size() - 1));
            jdbc.batchUpdate("""
                    insert into forecast (station_wmo, model, variable, horizon_days, target_time,
                        issued_time, forecast_value, loaded_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)""",
                    rows, BATCH_SIZE, (statement, row) -> {
                        statement.setString(1, row.wmo());
                        statement.setString(2, row.model());
                        statement.setString(3, row.variable().name());
                        statement.setInt(4, row.horizonDays());
                        statement.setObject(5, utc(row.target()));
                        statement.setObject(6, utc(row.target().minusSeconds(row.horizonDays() * 86_400L)));
                        statement.setDouble(7, row.value());
                        statement.setObject(8, loadedAt);
                    });
        });
    }

    /**
     * Сохраняет прогноз станции на 8–16 суток, выпущенный в issued. Повторное сохранение того же
     * выпуска заменяет его строки; выпуски других дней не трогает.
     */
    void replaceAheadForecasts(String wmo, Instant issued, List<ForecastRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        OffsetDateTime loadedAt = OffsetDateTime.now(ZoneOffset.UTC);
        transaction.executeWithoutResult(status -> {
            jdbc.update("delete from forecast where station_wmo = ? and issued_time = ? and horizon_days >= ?",
                    wmo, utc(issued), AuditCatalog.FIRST_AHEAD_HORIZON);
            jdbc.batchUpdate("""
                    insert into forecast (station_wmo, model, variable, horizon_days, target_time,
                        issued_time, forecast_value, loaded_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)""",
                    rows, BATCH_SIZE, (statement, row) -> {
                        statement.setString(1, row.wmo());
                        statement.setString(2, row.model());
                        statement.setString(3, row.variable().name());
                        statement.setInt(4, row.horizonDays());
                        statement.setObject(5, utc(row.target()));
                        statement.setObject(6, utc(issued));
                        statement.setDouble(7, row.value());
                        statement.setObject(8, loadedAt);
                    });
        });
    }

    /** Есть ли уже сохранённый выпуск прогнозов на 8–16 суток за этот момент. */
    boolean hasAheadIssue(Instant issued) {
        Integer rows = jdbc.queryForObject(
                "select count(*) from forecast where issued_time = ? and horizon_days >= ?",
                Integer.class, utc(issued), AuditCatalog.FIRST_AHEAD_HORIZON);
        return rows != null && rows > 0;
    }

    /**
     * Ошибки прогнозов по областям, моделям, параметрам и горизонтам за сроки не раньше since.
     * Пары прогноз–факт склеиваются и суммируются в самой базе; значения факта за физическими
     * пределами параметра в расчёт не идут.
     */
    List<ErrorSums> errorSums(Instant since) {
        StringBuilder facts = new StringBuilder();
        for (Variable variable : Variable.values()) {
            if (!facts.isEmpty()) {
                facts.append(" union all ");
            }
            // Имена колонок и пределы берутся из перечисления, а не из запроса посетителя.
            facts.append("select station_wmo, report_time, '").append(variable.name()).append("' as variable, ")
                    .append(variable.observationColumn).append(" as actual from observation where report_time >= ? and ")
                    .append(variable.observationColumn).append(" between ").append(variable.min)
                    .append(" and ").append(variable.max);
        }
        String sql = """
                select s.region, f.model, f.variable, f.horizon_days, count(*) as n,
                       sum(abs(f.forecast_value - x.actual)) as absolute,
                       sum((f.forecast_value - x.actual) * (f.forecast_value - x.actual)) as squared,
                       sum(f.forecast_value - x.actual) as signed
                from forecast f
                join (%s) x on x.station_wmo = f.station_wmo and x.report_time = f.target_time
                           and x.variable = f.variable
                join station s on s.wmo = f.station_wmo
                where f.target_time >= ?
                group by s.region, f.model, f.variable, f.horizon_days""".formatted(facts);
        Object[] arguments = new Object[Variable.values().length + 1];
        java.util.Arrays.fill(arguments, utc(since));
        return jdbc.query(sql, (rs, row) -> new ErrorSums(
                rs.getString("region"),
                rs.getString("model"),
                Variable.valueOf(rs.getString("variable")),
                rs.getInt("horizon_days"),
                rs.getLong("n"),
                rs.getDouble("absolute"),
                rs.getDouble("squared"),
                rs.getDouble("signed")), arguments);
    }

    ObservationTotals observationTotals(Instant since) {
        Map<String, Integer> byStation = new LinkedHashMap<>();
        long[] totals = {0, 0};
        Instant[] range = {null, null};
        jdbc.query("""
                select station_wmo, count(*) as n, sum(rejected_values) as rejected,
                       min(report_time) as oldest, max(report_time) as newest
                from observation where report_time >= ? group by station_wmo""",
                rs -> {
                    int n = rs.getInt("n");
                    byStation.put(rs.getString("station_wmo"), n);
                    totals[0] += n;
                    totals[1] += rs.getLong("rejected");
                    Instant oldest = instant(rs, "oldest");
                    Instant newest = instant(rs, "newest");
                    if (range[0] == null || oldest.isBefore(range[0])) {
                        range[0] = oldest;
                    }
                    if (range[1] == null || newest.isAfter(range[1])) {
                        range[1] = newest;
                    }
                },
                utc(since));
        return new ObservationTotals(totals[0], totals[1], byStation, range[0], range[1]);
    }

    /** Сводки одной станции не старше since, от новых к старым. */
    List<ObservationRow> observations(String wmo, Instant since) {
        return jdbc.query(
                "select * from observation where station_wmo = ? and report_time >= ? order by report_time desc",
                (rs, row) -> observationRow(rs), wmo, utc(since));
    }

    static boolean browsable(String table) {
        return BROWSABLE.containsKey(table);
    }

    /** Строки таблицы для просмотра; filters — «колонка → значение», лишние колонки игнорируются. */
    TablePage browse(String table, Map<String, String> filters, int page, int size) {
        String order = BROWSABLE.get(table);
        StringBuilder where = new StringBuilder();
        List<Object> arguments = new ArrayList<>();
        for (String column : FILTERS.get(table)) {
            String value = filters.get(column);
            if (value == null || value.isBlank()) {
                continue;
            }
            where.append(where.isEmpty() ? " where " : " and ").append(column).append(" = ?");
            arguments.add(column.equals("horizon_days") ? Integer.valueOf(value) : value);
        }

        Long total = jdbc.queryForObject("select count(*) from " + table + where, Long.class, arguments.toArray());
        List<Object> paged = new ArrayList<>(arguments);
        paged.add((long) page * size);
        paged.add(size);
        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.query(
                "select * from " + table + where + " order by " + order + " offset ? rows fetch next ? rows only",
                (rs, index) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        String column = rs.getMetaData().getColumnLabel(i).toLowerCase(java.util.Locale.ROOT);
                        Object value = rs.getObject(i);
                        // Время отдаём строкой ISO в UTC: драйверы баз возвращают его разными типами.
                        if (value instanceof java.sql.Timestamp || value instanceof OffsetDateTime) {
                            value = instant(rs, column).toString();
                        }
                        row.put(column, value);
                    }
                    return row;
                },
                paged.toArray());
        if (!rows.isEmpty()) {
            columns.addAll(rows.get(0).keySet());
        }
        return new TablePage(table, columns, rows, total == null ? 0 : total);
    }

    void logRun(String job, Instant startedAt, String status, int rowsWritten, String message) {
        String trimmed = message != null && message.length() > 1000 ? message.substring(0, 1000) : message;
        jdbc.update("""
                insert into ingest_run (job, started_at, finished_at, status, rows_written, message)
                values (?, ?, ?, ?, ?, ?)""",
                job, utc(startedAt), OffsetDateTime.now(ZoneOffset.UTC), status, rowsWritten, trimmed);
    }

    List<IngestRun> recentRuns(int limit) {
        return jdbc.query("select * from ingest_run order by id desc fetch first ? rows only",
                (rs, row) -> new IngestRun(
                        rs.getString("job"),
                        instant(rs, "started_at").toString(),
                        instant(rs, "finished_at").toString(),
                        rs.getString("status"),
                        rs.getInt("rows_written"),
                        rs.getString("message")),
                limit);
    }

    Coverage coverage() {
        return jdbc.queryForObject("""
                select (select count(*) from observation) as observations,
                       (select count(*) from forecast) as forecasts,
                       (select min(report_time) from observation) as oldest,
                       (select max(report_time) from observation) as newest""",
                (rs, row) -> new Coverage(
                        rs.getLong("observations"),
                        rs.getLong("forecasts"),
                        rs.getObject("oldest") == null ? null : instant(rs, "oldest").toString(),
                        rs.getObject("newest") == null ? null : instant(rs, "newest").toString()));
    }

    private static ObservationRow observationRow(ResultSet rs) throws SQLException {
        return new ObservationRow(
                rs.getString("station_wmo"),
                instant(rs, "report_time"),
                nullableDouble(rs, "temperature"),
                nullableDouble(rs, "dew_point"),
                nullableDouble(rs, "humidity"),
                nullableDouble(rs, "pressure_sea"),
                nullableDouble(rs, "pressure_station"),
                nullableDouble(rs, "wind_speed"),
                nullableDouble(rs, "wind_direction"),
                nullableDouble(rs, "visibility"),
                nullableDouble(rs, "cloud_cover"),
                nullableDouble(rs, "precipitation"),
                nullableDouble(rs, "min_temperature"),
                nullableDouble(rs, "max_temperature"),
                rs.getInt("rejected_values"));
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
