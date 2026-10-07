package org.example.weatherapp.audit;

import java.util.List;

/** Сводки одной станции в том виде, в каком их отдаёт Белгидромет. */
public record StationDetail(
        String wmo,
        String city,
        String region,
        double latitude,
        double longitude,
        /** Запрос к узлу Белгидромета, которым получены эти данные. */
        String sourceUrl,
        int windowHours,
        List<Row> rows) {

    /** Один срок наблюдения; параметр равен null, если в этот срок он не передавался. */
    public record Row(
            String time,
            Double temperature,
            Double dewPoint,
            Double humidity,
            Double pressureStation,
            Double pressureSea,
            Double windDirection,
            Double windSpeed,
            Double visibility,
            Double cloudCover,
            Double precipitation,
            Double minTemperature,
            Double maxTemperature) {}
}
