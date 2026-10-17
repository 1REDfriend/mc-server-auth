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
}
