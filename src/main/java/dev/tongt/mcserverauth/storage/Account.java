package dev.tongt.mcserverauth.storage;

import org.jspecify.annotations.Nullable;

public record Account(
        String nameLower,
        String displayName,
        String passwordHash,
        String email,
        long createdAt,
        @Nullable Long lastLoginAt,
        @Nullable String lastIp) {

    /** An account registered without an email has an empty {@code email}; it can only be reset by an admin. */
    public boolean hasEmail() {
        return !email.isEmpty();
    }

    /** Leaves out the password hash and email so a stray log line cannot leak them. */
    @Override
    public String toString() {
        return "Account[nameLower=" + nameLower + ", displayName=" + displayName + "]";
    }
}
