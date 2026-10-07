package org.example.weatherapp.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.example.weatherapp.model.CropProfile;
import org.example.weatherapp.model.FieldConditions;
import org.springframework.stereotype.Component;

/**
 * Рекомендации по каждой культуре хозяйства на текущие условия.
 * Считаем по показаниям своих датчиков: они стоят в поле, а не в городе.
 * Прогнозные величины (суточные минимум и максимум, испаряемость) берём
 * у метеослужбы — их датчики не измеряют.
 */
@Component
public class AgronomyAdvisor {

    public record Advice(String level, String icon, String title, String detail) {}

    public record CropAdvice(String id, String name, List<Advice> advice) {}

    private static final Map<String, Integer> ORDER =
            Map.of("critical", 0, "warning", 1, "good", 2, "info", 3);

    private final CropCatalog catalog;

    public AgronomyAdvisor(CropCatalog catalog) {
        this.catalog = catalog;
    }

    public List<CropAdvice> adviseAll(FieldConditions field, SensorSimulator.Readings sensors) {
        return catalog.all().stream()
                .map(crop -> new CropAdvice(crop.id(), crop.name(), advise(crop, field, sensors)))
                .toList();
    }

    private List<Advice> advise(CropProfile crop, FieldConditions field, SensorSimulator.Readings sensors) {
        List<Advice> advice = new ArrayList<>();

        frost(crop, field, advice);
        heat(crop, field, advice);
        sprayWindow(field, sensors, advice);
        disease(crop, field, sensors, advice);
        soilWater(crop, field, sensors, advice);
        sowing(crop, sensors, advice);
        harvest(crop, field, sensors, advice);

        if (advice.isEmpty()) {
            advice.add(new Advice("info", "circle-check", "Особых указаний нет",
                    "Показатели в рабочем диапазоне. Плановые наблюдения за посевом."));
        }

        advice.sort(Comparator.comparingInt(item -> ORDER.getOrDefault(item.level(), 9)));
        return advice;
    }

    private void frost(CropProfile crop, FieldConditions field, List<Advice> advice) {
        if (field.temperatureMin() < crop.frostLimit()) {
            advice.add(new Advice("critical", "snowflake", "Ожидается повреждение холодом",
                    "Ночной минимум %.1f°. %s — отложите подкормку и обработку до потепления."
                            .formatted(field.temperatureMin(), crop.frostNote())));
        } else if (field.temperatureMin() < crop.frostLimit() + 3) {
            advice.add(new Advice("warning", "snowflake", "Ночь на грани заморозка",
                    "Минимум %.1f° при пороге %.0f°. В низинах на поле бывает на 2–3° холоднее — проверьте посев утром."
                            .formatted(field.temperatureMin(), crop.frostLimit())));
        }
    }

    private void heat(CropProfile crop, FieldConditions field, List<Advice> advice) {
        if (field.temperatureMax() > crop.heatLimit()) {
            advice.add(new Advice("warning", "thermometer-sun", "Тепловой стресс",
                    "Днём до %.0f° при пороге %.0f°. %s — обработки переносите на утро или вечер."
                            .formatted(field.temperatureMax(), crop.heatLimit(), crop.heatNote())));
        }
    }

    /** Условия внесения зависят от техники, а не от культуры. */
    private void sprayWindow(FieldConditions field, SensorSimulator.Readings sensors, List<Advice> advice) {
        if (sensors.wind() > 5 || field.gusts() > 8) {
            advice.add(new Advice("critical", "spray-can", "Опрыскивание запрещено",
                    "Ветер %.1f м/с, порывы до %.1f м/с. Снос рабочего раствора на соседние поля."
                            .formatted(sensors.wind(), field.gusts())));
            return;
        }
        if (sensors.precipitation() > 0 || field.precipitationSum() > 2) {
            advice.add(new Advice("warning", "spray-can", "Опрыскивание под вопросом",
                    "Осадки за сутки %.1f мм. Препарат смоет до впитывания — нужно 4–6 часов без дождя."
                            .formatted(field.precipitationSum())));
            return;
        }
        if (sensors.wind() < 1) {
            advice.add(new Advice("warning", "spray-can", "Штиль — риск инверсии",
                    "Ветер %.1f м/с. В штиль облако мелких капель зависает и сносится непредсказуемо, дождитесь 1,5–4 м/с."
                            .formatted(sensors.wind())));
            return;
        }
        if (field.vapourPressureDeficit() > 1.5 || sensors.airTemperature() > 25) {
            advice.add(new Advice("warning", "spray-can", "Сухо для обработки",
                    "Температура %.1f°, дефицит влажности %.1f кПа. Капли испаряются не долетев — работайте после 18:00."
                            .formatted(sensors.airTemperature(), field.vapourPressureDeficit())));
            return;
        }
        advice.add(new Advice("good", "spray-can", "Окно для опрыскивания открыто",
                "Ветер %.1f м/с, температура %.1f°, осадков нет. Условия для фунгицида или гербицида рабочие."
                        .formatted(sensors.wind(), sensors.airTemperature())));
    }

    private void disease(CropProfile crop, FieldConditions field,
                         SensorSimulator.Readings sensors, List<Advice> advice) {
        boolean infectious = sensors.humidity() > crop.diseaseHumidity()
                && sensors.airTemperature() >= crop.diseaseTempMin()
                && sensors.airTemperature() <= crop.diseaseTempMax();
        if (infectious) {
            advice.add(new Advice("critical", "bug", "Инфекционный период",
                    "Влажность %.0f%% при %.1f° — %s заражают лист за 6–8 часов. Планируйте фунгицид."
                            .formatted(sensors.humidity(), sensors.airTemperature(), crop.diseaseNames())));
            return;
        }
        double spread = sensors.airTemperature() - field.dewPoint();
        if (spread < 2) {
            advice.add(new Advice("warning", "droplets", "Роса держится на листе",
                    "До точки росы %.1f°. Лист долго остаётся влажным — это открывает ворота грибным болезням."
                            .formatted(spread)));
        }
    }

    private void soilWater(CropProfile crop, FieldConditions field,
                           SensorSimulator.Readings sensors, List<Advice> advice) {
        double rootPercent = sensors.soilMoistureRoot() * 100;
        if (sensors.soilMoistureRoot() < crop.moistureDeficit() && field.evapotranspiration() > 0.25) {
            advice.add(new Advice("warning", "droplets", "Дефицит влаги в корневом слое",
                    "Влажность почвы %.0f%% при испаряемости %.2f мм/ч — ниже порога %.0f%% для этой культуры."
                            .formatted(rootPercent, field.evapotranspiration(), crop.moistureDeficit() * 100)));
        } else if (sensors.soilMoistureRoot() > crop.moistureExcess()) {
            advice.add(new Advice("warning", "droplets", "Переувлажнение почвы",
                    "Влажность корневого слоя %.0f%%. Техника завязнет, а на переувлажнённом поле поднимаются корневые гнили."
                            .formatted(rootPercent)));
        }
    }

    private void sowing(CropProfile crop, SensorSimulator.Readings sensors, List<Advice> advice) {
        double seedbed = sensors.soilTemperatureSeedbed();
        if (seedbed >= crop.sowingSoilMin() && seedbed <= crop.sowingSoilMax()
                && sensors.soilMoistureSurface() > 0.18) {
            advice.add(new Advice("good", "sprout", "Окно для сева",
                    "Почва на глубине заделки %.1f° (норма %.0f–%.0f°), влаги в верхнем слое %.0f%% — всходы получатся дружными."
                            .formatted(seedbed, crop.sowingSoilMin(), crop.sowingSoilMax(),
                                    sensors.soilMoistureSurface() * 100)));
        } else if (seedbed < crop.sowingSoilMin() - 3) {
            advice.add(new Advice("info", "sprout", "Почва холодная для сева",
                    "На глубине заделки %.1f°, культуре нужно от %.0f°. Прорастание будет затянутым."
                            .formatted(seedbed, crop.sowingSoilMin())));
        }
    }

    private void harvest(CropProfile crop, FieldConditions field,
                         SensorSimulator.Readings sensors, List<Advice> advice) {
        if (sensors.humidity() < 60 && field.precipitationSum() == 0 && sensors.airTemperature() > 18) {
            advice.add(new Advice("good", "wheat", "Условия для уборки",
                    "Влажность воздуха %.0f%%, сухо. %s."
                            .formatted(sensors.humidity(), crop.harvestNote())));
        }
    }
}
