package org.example.weatherapp.service;

import org.example.weatherapp.model.FieldConditions;
import org.springframework.stereotype.Component;

/**
 * Имитация полевой агростанции хозяйства. Реальных датчиков пока нет —
 * значения выводятся из данных метеослужбы со сдвигами, которые обычно
 * и разделяют опорную станцию в городе и приборы посреди пшеничного поля.
 */
@Component
public class SensorSimulator {

    public record Readings(
            double airTemperature,
            double humidity,
            double wind,
            double soilTemperatureSurface,
            double soilTemperatureSeedbed,
            double soilMoistureSurface,
            double soilMoistureRoot,
            double precipitation) {}

    public Readings read(FieldConditions field, double latitude, double longitude, int hour) {
        // Один и тот же час и одна и та же точка дают одни и те же показания,
        // иначе цифры менялись бы при каждом обновлении страницы.
        int seed = (int) Math.round(latitude * 1000) ^ (int) Math.round(longitude * 1000) ^ (hour * 31);
        Mulberry32 random = new Mulberry32(seed);
        boolean night = hour >= 21 || hour < 7;

        // Город, где стоит опорная станция, держит тепло круглосуточно,
        // а ночью разрыв с открытым полем становится заметно больше.
        double airDrift = night ? -(1.6 + random.next() * 2) : -(0.8 + random.next() * 1.4);
        // Испарение с почвы и посевов поднимает влажность над полем.
        double humidityDrift = 4 + random.next() * 9;
        // Лесополосы и рельеф гасят приземный ветер.
        double windDrift = -(0.3 + random.next() * 1.6);
        // Полог пшеницы затеняет почву, поэтому верхний слой прохладнее модельного.
        double surfaceDrift = -(0.5 + random.next() * 2);
        // На глубине заделки колебания сглажены тепловой инерцией почвы.
        double seedbedDrift = -(0.3 + random.next() * 0.9);
        // Под пологом верхний слой теряет меньше влаги на испарение.
        double moistureSurfaceDrift = 0.01 + random.next() * 0.04;
        double moistureRootDrift = -0.02 + random.next() * 0.05;

        return new Readings(
                field.airTemperature() + airDrift,
                clamp(field.humidity() + humidityDrift, 0, 100),
                Math.max(0, field.wind() + windDrift),
                field.soilTemperatureSurface() + surfaceDrift,
                field.soilTemperatureSeedbed() + seedbedDrift,
                clamp(field.soilMoistureSurface() + moistureSurfaceDrift, 0, 0.6),
                clamp(field.soilMoistureRoot() + moistureRootDrift, 0, 0.6),
                // Дождемер расходится с моделью только когда дождь действительно был.
                field.precipitation() == 0 ? 0 : field.precipitation() * (0.7 + random.next() * 0.6));
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private static final class Mulberry32 {
        private int state;

        Mulberry32(int seed) {
            this.state = seed;
        }

        double next() {
            state += 0x6D2B79F5;
            int t = (state ^ (state >>> 15)) * (1 | state);
            t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
            return Integer.toUnsignedLong(t ^ (t >>> 14)) / 4294967296.0;
        }
    }
}
