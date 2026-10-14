package org.example.weatherapp.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class PageControllerTests {

    private final PageController pages = new PageController();

    @Test
    void ownAssetsGetContentFingerprint() {
        ResponseEntity<String> response = pages.audit();
        String html = response.getBody();

        assertThat(html).containsPattern("href=\"/app\\.css\\?v=[0-9a-f]{10}\"");
        assertThat(html).containsPattern("src=\"/audit\\.js\\?v=[0-9a-f]{10}\"");
        // Сторонние адреса не трогаем.
        assertThat(html).contains("https://cdn.jsdelivr.net/npm/lucide@0.454.0/dist/umd/lucide.min.js\"");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
    }

    @Test
    void sameFileGetsSameFingerprintOnEveryPage() {
        String css = "href=\"/app.css?v=";
        String audit = pages.audit().getBody();
        String index = pages.index().getBody();
        int a = audit.indexOf(css) + css.length();
        int b = index.indexOf(css) + css.length();

        assertThat(audit.substring(a, a + 10)).isEqualTo(index.substring(b, b + 10));
    }
}
