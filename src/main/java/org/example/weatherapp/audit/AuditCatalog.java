package org.example.weatherapp.audit;

import java.util.List;

/** Станции Белгидромета, прогнозные модели и сравниваемые параметры. */
final class AuditCatalog {

    /** Станция государственной сети наблюдений; wmo — её международный индекс. */
    record Station(String wmo, String city, String region) {}

    record Model(String id, String name, String origin) {}

    enum Variable {
        TEMPERATURE("temperature_2m", "Температура", "°C"),
        HUMIDITY("relative_humidity_2m", "Влажность", "%"),
        PRESSURE("pressure_msl", "Давление", "гПа"),
        WIND("wind_speed_10m", "Ветер", "м/с");

        final String apiName;
        final String label;
        final String unit;

        Variable(String apiName, String label, String unit) {
            this.apiName = apiName;
            this.label = label;
            this.unit = unit;
        }
    }

    /** Горизонт — за сколько суток до целевого срока был выпущен прогноз. */
    static final List<Integer> HORIZON_DAYS = List.of(1, 2, 3);

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
