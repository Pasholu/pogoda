package org.example.weatherapp.controller;

import java.util.Map;
import org.example.weatherapp.model.SiteReport;
import org.example.weatherapp.service.WeatherService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

@RestController
@RequestMapping("/api")
public class WeatherController {

    private final WeatherService weatherService;

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @GetMapping("/report/{site}")
    public SiteReport report(@PathVariable String site) {
        return weatherService.report(site);
    }

    @ExceptionHandler(WeatherService.SiteNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(WeatherService.SiteNotFoundException cause) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", cause.getMessage()));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Map<String, String>> handleUpstream(RestClientException cause) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Метеослужба недоступна"));
    }
}
