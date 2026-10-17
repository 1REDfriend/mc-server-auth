package dev.tongt.mcserverauth.email;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public record EmailAddress(String value) {
    private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s.]{2,}$");

    public static Optional<EmailAddress> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.length() > 254 || !SHAPE.matcher(trimmed).matches()) {
            return Optional.empty();
        }
        int at = trimmed.lastIndexOf('@');
        String local = trimmed.substring(0, at);
        if (local.length() > 64) {
            return Optional.empty();
        }
        return Optional.of(new EmailAddress(local + "@" + trimmed.substring(at + 1).toLowerCase(Locale.ROOT)));
    }

    /** First character of the local part, then ***, then the full domain: t***@gmail.com */
    public String masked() {
        int at = value.lastIndexOf('@');
        return value.charAt(0) + "***" + value.substring(at);
    }
}
