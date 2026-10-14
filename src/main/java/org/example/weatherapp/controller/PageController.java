package org.example.weatherapp.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Отдаёт страницы сайта, дописывая к ссылкам на свои стили и скрипты отпечаток их содержимого:
 * {@code /app.css} → {@code /app.css?v=3f2a9c01b7}. Изменился файл — изменилась ссылка, и браузер
 * телефона не может подставить к новой странице старую копию из кэша.
 */
@RestController
public class PageController {

    /** Ссылки вида src="/имя.js" и href="/имя.css" — только на файлы самого сайта. */
    private static final Pattern ASSET = Pattern.compile("(src|href)=\"/([\\w.-]+\\.(?:css|js))\"");

    private final Map<String, String> pages = new ConcurrentHashMap<>();
    private final Map<String, String> fingerprints = new ConcurrentHashMap<>();

    @GetMapping({"/", "/index.html"})
    public ResponseEntity<String> index() {
        return page("index.html");
    }

    @GetMapping("/audit.html")
    public ResponseEntity<String> audit() {
        return page("audit.html");
    }

    @GetMapping("/data.html")
    public ResponseEntity<String> data() {
        return page("data.html");
    }

    private ResponseEntity<String> page(String name) {
        // Файлы лежат внутри собранного приложения и до перезапуска не меняются — достаточно прочитать раз.
        String html = pages.computeIfAbsent(name, this::render);
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noCache())
                .body(html);
    }

    private String render(String name) {
        Matcher matcher = ASSET.matcher(read("static/" + name));
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String asset = matcher.group(2);
            String version = fingerprints.computeIfAbsent(asset, key -> fingerprint(read("static/" + key)));
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(matcher.group(1) + "=\"/" + asset + "?v=" + version + "\""));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String fingerprint(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 5);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new IllegalStateException("Не найден файл сайта " + path, cause);
        }
    }
}
