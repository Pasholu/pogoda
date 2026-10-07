package org.example.weatherapp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeoResponse(List<Place> results) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Place(
            String name,
            String country,
            String admin1,
            double latitude,
            double longitude) {}
}
