package dev.tongt.mcserverauth.email;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A plain {@code local@domain} address. {@link #parse(String)} is the validating entry point; the record
 * constructor accepts any string, so only build instances directly from values that already came out of
 * {@code parse} (for example, stored addresses).
 */
public record EmailAddress(String value) {
    private static final Pattern LOCAL = Pattern.compile("[A-Za-z0-9._%+-]+");
    private static final String LABEL = "[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?";
    private static final Pattern DOMAIN = Pattern.compile("(?:" + LABEL + "\\.)+[A-Za-z]{2,}");

    public static Optional<EmailAddress> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.length() > 254) {
            return Optional.empty();
        }
        int at = trimmed.indexOf('@');
        if (at <= 0 || at != trimmed.lastIndexOf('@')) {
            return Optional.empty();
        }
        String local = trimmed.substring(0, at);
        String domain = trimmed.substring(at + 1);
        if (local.length() > 64 || !LOCAL.matcher(local).matches()
                || local.startsWith(".") || local.endsWith(".") || local.contains("..")
                || !DOMAIN.matcher(domain).matches()) {
            return Optional.empty();
        }
        return Optional.of(new EmailAddress(local + "@" + domain.toLowerCase(Locale.ROOT)));
    }

    /** First character of the local part, then ***, then the full domain: t***@gmail.com */
    public String masked() {
        int at = value.lastIndexOf('@');
        return value.charAt(0) + "***" + value.substring(at);
    }
}
