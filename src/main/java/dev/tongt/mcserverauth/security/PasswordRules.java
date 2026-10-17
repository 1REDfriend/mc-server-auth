package dev.tongt.mcserverauth.security;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Predicate;

/** Rules for a newly chosen password. Returns a messages.yml key describing the first problem. */
public final class PasswordRules {
    private final int minLength;
    private final int maxLength;

    public PasswordRules(int minLength, int maxLength) {
        this.minLength = minLength;
        this.maxLength = maxLength;
    }

    public Optional<String> check(String password, String confirm, Predicate<String> isServerPassword) {
        int length = password.codePointCount(0, password.length());
        if (length < minLength || length > maxLength) {
            return Optional.of("error.password-length");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > PasswordHasher.MAX_BYTES) {
            return Optional.of("error.password-too-long");
        }
        if (!password.equals(confirm)) {
            return Optional.of("error.password-mismatch");
        }
        if (isServerPassword.test(password)) {
            return Optional.of("error.password-same-as-server");
        }
        return Optional.empty();
    }

    /** Placeholders <min> and <max> for the error.password-length message. */
    public TagResolver placeholders() {
        return TagResolver.resolver(
                Placeholder.unparsed("min", Integer.toString(minLength)),
                Placeholder.unparsed("max", Integer.toString(maxLength)));
    }

    public int maxLength() {
        return maxLength;
    }
}
