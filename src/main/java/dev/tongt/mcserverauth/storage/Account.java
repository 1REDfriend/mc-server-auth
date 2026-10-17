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

    /** Leaves out the password hash and email so a stray log line cannot leak them. */
    @Override
    public String toString() {
        return "Account[nameLower=" + nameLower + ", displayName=" + displayName + "]";
    }
}
