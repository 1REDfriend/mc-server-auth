package dev.tongt.mcserverauth.audit;

import dev.tongt.mcserverauth.testutil.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SecretInputLogTest {
    @TempDir
    Path dir;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-23T08:00:00Z"));
    private SecretInputLog log;

    @AfterEach
    void close() {
        if (log != null) {
            log.close();
        }
    }

    private static String decode(String b64) {
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
    }

    /** Parses "key=value" fields after the timestamp. */
    private static Map<String, String> fields(String line) {
        return Stream.of(line.split(" ")).skip(1)
                .map(f -> f.split("=", 2))
                .collect(Collectors.toMap(f -> f[0], f -> f[1]));
    }

    private List<String> lines(String file) throws IOException {
        return Files.readAllLines(dir.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    void writesOneLinePerInputWithUserTextInBase64() throws IOException {
        log = new SecretInputLog(dir, clock, 7);
        log.record("configuration", "127.0.0.1", "Steve", "mcserverauth:submit.3",
                "{password:\"p@ss word ไทย\",email:\"a@example.com\"}");

        List<String> lines = lines("secret-input-2026-10-23.log");
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.startsWith("2026-10-23T08:00:00Z "), line);
        assertFalse(line.contains("p@ss"), "user text must not be readable without decoding");
        Map<String, String> f = fields(line);
        assertEquals("configuration", f.get("phase"));
        assertEquals("127.0.0.1", f.get("ip"));
        assertEquals("Steve", decode(f.get("name")));
        assertEquals("mcserverauth:submit.3", decode(f.get("id")));
        assertEquals("{password:\"p@ss word ไทย\",email:\"a@example.com\"}", decode(f.get("payload")));
    }

    @Test
    void lineBreaksInInputCannotForgeLines() throws IOException {
        log = new SecretInputLog(dir, clock, 7);
        log.record("game", "127.0.0.1", "Steve\nfake", "id", "a\r\nb");
        assertEquals(1, lines("secret-input-2026-10-23.log").size());
    }

    @Test
    void missingPayloadIsMarked() throws IOException {
        log = new SecretInputLog(dir, clock, 7);
        log.record("game", "127.0.0.1", "Steve", "id", null);
        assertEquals("-", fields(lines("secret-input-2026-10-23.log").get(0)).get("payload"));
    }

    @Test
    void startsANewFileEachUtcDay() throws IOException {
        log = new SecretInputLog(dir, clock, 7);
        log.record("game", "127.0.0.1", "A", "id", "1");
        clock.advance(Duration.ofDays(1));
        log.record("game", "127.0.0.1", "B", "id", "2");
        assertEquals(1, lines("secret-input-2026-10-23.log").size());
        assertEquals(1, lines("secret-input-2026-10-24.log").size());
    }

    @Test
    void deletesFilesOlderThanKeepDays() throws IOException {
        Files.writeString(dir.resolve("secret-input-2026-10-16.log"), "old\n");
        Files.writeString(dir.resolve("secret-input-2026-10-17.log"), "kept\n");
        Files.writeString(dir.resolve("unrelated.txt"), "kept\n");
        log = new SecretInputLog(dir, clock, 7);
        assertFalse(Files.exists(dir.resolve("secret-input-2026-10-16.log")));
        assertTrue(Files.exists(dir.resolve("secret-input-2026-10-17.log")));
        assertTrue(Files.exists(dir.resolve("unrelated.txt")));
    }
}
