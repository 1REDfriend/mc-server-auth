package dev.tongt.mcserverauth.config;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {
    private static final Pattern KEY_LITERAL =
            Pattern.compile("\"((?:dialog|input|button|error|info|kick|command|email)\\.[a-z0-9.-]+)\"");

    private static YamlConfiguration bundled() {
        var stream = Objects.requireNonNull(MessagesTest.class.getResourceAsStream("/messages.yml"));
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    @Test
    void rendersPlaceholders() {
        Messages messages = new Messages(bundled());
        String text = PlainTextComponentSerializer.plainText().serialize(
                messages.get("kick.wrong-case", Placeholder.unparsed("name", "Steve")));
        assertEquals("This account is registered as Steve. Use exactly that name, including capital letters.", text);
    }

    @Test
    void plainReplacesBraceVariables() {
        Messages messages = new Messages(bundled());
        String body = messages.plain("email.reset.body", Map.of("name", "Steve", "code", "123456", "minutes", "15"));
        assertTrue(body.contains("Hi Steve,"));
        assertTrue(body.contains("123456"));
        assertTrue(body.contains("15 minutes"));
    }

    @Test
    void missingKeyFallsBackToKey() {
        Messages messages = new Messages(new YamlConfiguration());
        assertEquals("nope.key", PlainTextComponentSerializer.plainText().serialize(messages.get("nope.key")));
    }

    @Test
    void everyKeyUsedInCodeExists() throws IOException {
        YamlConfiguration yaml = bundled();
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            // AuthConfig reads config.yml paths such as "email.smtp.host"; those are not message keys.
            for (Path file : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("AuthConfig.java")).toList()) {
                Matcher m = KEY_LITERAL.matcher(Files.readString(file));
                while (m.find()) {
                    if (!yaml.isString(m.group(1))) {
                        missing.add(m.group(1) + " (" + file.getFileName() + ")");
                    }
                }
            }
        }
        assertEquals(List.of(), missing);
    }
}
