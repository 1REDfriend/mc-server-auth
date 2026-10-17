package dev.tongt.mcserverauth.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Map;

/** Player-facing text from messages.yml. Unknown keys render as the key itself so they are easy to spot. */
public final class Messages {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final ConfigurationSection yaml;

    public Messages(ConfigurationSection yaml) {
        this.yaml = yaml;
    }

    public Component get(String key, TagResolver... resolvers) {
        return MINI_MESSAGE.deserialize(raw(key), resolvers);
    }

    /** Plain text with {variable} replacement, used for email subjects and bodies. */
    public String plain(String key, Map<String, String> variables) {
        String text = raw(key);
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            text = text.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return text;
    }

    private String raw(String key) {
        String value = yaml.getString(key);
        return value != null ? value : key;
    }
}
