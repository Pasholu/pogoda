package org.example.weatherapp.audit;

import java.util.List;

/** Станции Белгидромета, прогнозные модели и сравниваемые параметры. */
final class AuditCatalog {

    /** Станция государственной сети наблюдений; wmo — её международный индекс. */
    record Station(String wmo, String city, String region) {}

    record Model(String id, String name, String origin) {}

    /**
     * Сравниваемый параметр: имя у Open-Meteo, колонка факта в таблице observation
     * и физические пределы — значение за ними считается ошибкой передачи.
     */
    enum Variable {
        TEMPERATURE("temperature_2m", "temperature", "Температура", "°C", -80, 60),
        HUMIDITY("relative_humidity_2m", "humidity", "Влажность", "%", 1, 100),
        PRESSURE("pressure_msl", "pressure_sea", "Давление", "гПа", 870, 1085),
        WIND("wind_speed_10m", "wind_speed", "Ветер", "м/с", 0, 75);

        final String apiName;
        final String observationColumn;
        final String label;
        final String unit;
        final double min;
        final double max;

        Variable(String apiName, String observationColumn, String label, String unit, double min, double max) {
            this.apiName = apiName;
            this.observationColumn = observationColumn;
            this.label = label;
            this.unit = unit;
            this.min = min;
            this.max = max;
        }

        Double plausible(Double value) {
            return value != null && value >= min && value <= max ? value : null;
        }
    }

    /**
     * Горизонт — за сколько суток до целевого срока был выпущен прогноз.
     * 1–7 суток отдаёт архив прошлых прогнозов Open-Meteo, в том числе задним числом.
     */
    static final List<Integer> HORIZON_DAYS = List.of(1, 2, 3, 4, 5, 6, 7);

    /**
     * 8–16 суток архив не хранит: такие прогнозы приложение сохраняет само раз в сутки,
     * и для сравнения они становятся пригодны, когда наступят их целевые сроки.
     */
    static final int FIRST_AHEAD_HORIZON = 8;
    static final int LAST_AHEAD_HORIZON = 16;

    static final String COUNTRY = "Вся Беларусь";

    static final List<String> REGIONS = List.of(
            "Брестская", "Витебская", "Гомельская", "Гродненская", "Минская", "Могилёвская");

    /** Станции, которые Белгидромет публикует в открытом узле данных ВМО. */
    static final List<Station> STATIONS = List.of(
            new Station("33008", "Брест", "Брестская"),
            new Station("26941", "Барановичи", "Брестская"),
            new Station("33019", "Пинск", "Брестская"),
            new Station("26666", "Витебск", "Витебская"),
            new Station("26554", "Верхнедвинск", "Витебская"),
            new Station("26657", "Докшицы", "Витебская"),
            new Station("26659", "Лепель", "Витебская"),
            new Station("26645", "Лынтупы", "Витебская"),
            new Station("26763", "Орша", "Витебская"),
            new Station("26653", "Полоцк", "Витебская"),
            new Station("26668", "Сенно", "Витебская"),
            new Station("33041", "Гомель", "Гомельская"),
            new Station("33124", "Брагин", "Гомельская"),
            new Station("33038", "Василевичи", "Гомельская"),
            new Station("33027", "Житковичи", "Гомельская"),
            new Station("26966", "Жлобин", "Гомельская"),
            new Station("33036", "Мозырь", "Гомельская"),
            new Station("26832", "Лида", "Гродненская"),
            new Station("26850", "Минск", "Минская"),
            new Station("26853", "Березино", "Минская"),
            new Station("26759", "Борисов", "Минская"),
            new Station("26855", "Марьина Горка", "Минская"),
            new Station("26951", "Слуцк", "Минская"),
            new Station("26961", "Бобруйск", "Могилёвская"),
            new Station("26774", "Горки", "Могилёвская"),
            new Station("26864", "Кличев", "Могилёвская"),
            new Station("26887", "Костюковичи", "Могилёвская"),
            new Station("26878", "Славгород", "Могилёвская"));

    static final List<Model> MODELS = List.of(
            new Model("ecmwf_ifs025", "ECMWF IFS", "Европейский центр"),
            new Model("gfs_seamless", "GFS", "NOAA, США"),
            new Model("icon_seamless", "ICON", "DWD, Германия"),
            new Model("gem_seamless", "GEM", "Метеослужба Канады"),
            new Model("jma_seamless", "JMA", "Метеослужба Японии"),
            new Model("meteofrance_seamless", "ARPEGE", "Météo-France"),
            new Model("ukmo_seamless", "UKMO", "Met Office, Великобритания"));

    private AuditCatalog() {}
}
