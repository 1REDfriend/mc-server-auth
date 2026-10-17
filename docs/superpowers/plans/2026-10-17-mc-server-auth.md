# mc-server-auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Paper 26.3 plugin that makes every player pass a shared server password and a personal password (with email-verified, code-based self-service reset) in native dialogs before they enter the world.

**Architecture:** Small pure units (config, hashing, rules, limiters, codes, email parsing, SQLite store, messages) are unit-tested without a server. A `DialogWaiter` turns dialog button clicks into blocking `Response`s, so `LoginFlow` can run the whole login as straight-line code on Paper's async configuration thread (`AsyncPlayerConnectionConfigureEvent`). Commands reuse the same waiter on virtual threads.

**Tech Stack:** Java 25, Gradle 9.8.1 (Kotlin DSL), paper-api `26.3.build.200-stable`, BCrypt (`at.favre.lib:bcrypt:0.10.2`), Jakarta Mail (`org.eclipse.angus:angus-mail:2.0.5`), SQLite (bundled with Paper; `org.xerial:sqlite-jdbc:3.53.4.0` for tests), JUnit 6.1.3, Mailpit `v1.31.6` (local fake SMTP).

Spec: `docs/superpowers/specs/2026-10-17-mc-server-auth-design.md`

**Rules for every task**
- Everything inside source files is English (identifiers, comments, logs, strings). Player-facing text lives only in `messages.yml`.
- Shell commands below are PowerShell, run from the project root `D:\work\write_code\mc-server-auth`.
- Commit messages end with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

## File structure

```
build.gradle.kts, settings.gradle.kts, gradlew, gradlew.bat, gradle/wrapper/*
README.md
dev/test-config.yml                      config used by the local test server (points at Mailpit)
scripts/start-mailpit.ps1                runs Mailpit in Docker on 41853 (SMTP) / 41854 (web)
scripts/start-test-server.ps1            downloads Paper 26.3, installs the plugin, starts on 34127
src/main/resources/plugin.yml
src/main/resources/config.yml
src/main/resources/messages.yml
src/main/java/dev/tongt/mcserverauth/
  McServerAuthPlugin.java                wiring, bootstrap, fail-closed lockdown
  config/AuthConfig.java                 immutable config + validation
  config/Messages.java                   MiniMessage text + email templates from messages.yml
  security/PasswordHasher.java           BCrypt hash/verify
  security/PasswordRules.java            new-password rules
  security/AttemptLimiter.java           per-IP failure window + temporary bans
  security/SessionCache.java             optional "remember (name, IP)" (feature C)
  email/EmailAddress.java                parse/normalize/mask
  email/CodeService.java                 6-digit codes, expiry, attempts, send limits
  email/MailSender.java                  interface
  email/SmtpMailSender.java              Jakarta Mail implementation
  storage/Account.java                   record
  storage/AccountStore.java              SQLite access
  flow/DialogWaiter.java                 click → Response bridge, blocking await
  flow/DialogFactory.java                builds every dialog
  flow/LoginFlow.java                    configuration-phase login state machine
  flow/PreLoginGuard.java                ban / duplicate name / wrong case checks
  command/ChangePasswordCommand.java     /changepassword
  command/AuthAdminCommand.java          /authadmin reset|setserverpassword|info
src/test/java/dev/tongt/mcserverauth/
  testutil/MutableClock.java
  config/AuthConfigTest.java, config/MessagesTest.java
  security/PasswordHasherTest.java, PasswordRulesTest.java, AttemptLimiterTest.java, SessionCacheTest.java
  email/EmailAddressTest.java, CodeServiceTest.java, SmtpMailSenderTest.java
  storage/AccountStoreTest.java
```

---

### Task 1: Gradle scaffold

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `src/main/resources/plugin.yml`, `src/main/java/dev/tongt/mcserverauth/McServerAuthPlugin.java`, Gradle wrapper files
- Modify: `.gitignore`

- [ ] **Step 1: Bootstrap the Gradle wrapper** (no Gradle is installed globally)

```powershell
$tmp = Join-Path $env:TEMP 'gradle-bootstrap'
New-Item -ItemType Directory -Force $tmp | Out-Null
Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-9.8.1-bin.zip' -OutFile "$tmp\gradle.zip"
Expand-Archive "$tmp\gradle.zip" -DestinationPath $tmp -Force
Set-Content settings.gradle.kts 'rootProject.name = "mc-server-auth"'
& "$tmp\gradle-9.8.1\bin\gradle.bat" wrapper --gradle-version 9.8.1
```
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` exist.

- [ ] **Step 2: Write `build.gradle.kts`**

```kotlin
plugins {
    java
}

group = "dev.tongt"
version = "0.1.0"

val paperApi = "io.papermc.paper:paper-api:26.3.build.200-stable"
val bcrypt = "at.favre.lib:bcrypt:0.10.2"
val angusMail = "org.eclipse.angus:angus-mail:2.0.5"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // bcrypt and angus-mail are downloaded by Paper at runtime (plugin.yml `libraries`).
    compileOnly(paperApi)
    compileOnly(bcrypt)
    compileOnly(angusMail)

    testImplementation(paperApi)
    testImplementation(bcrypt)
    testImplementation(angusMail)
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
```

- [ ] **Step 3: Write `src/main/resources/plugin.yml`**

```yaml
name: McServerAuth
version: '${version}'
main: dev.tongt.mcserverauth.McServerAuthPlugin
api-version: '26.3'
description: Server password and per-player passwords with email reset, shown as dialogs before joining.
author: tongt
libraries:
  - at.favre.lib:bcrypt:0.10.2
  - org.eclipse.angus:angus-mail:2.0.5
permissions:
  mcserverauth.changepassword:
    description: Use /changepassword
    default: true
  mcserverauth.admin:
    description: Use /authadmin
    default: op
```

- [ ] **Step 4: Write a placeholder main class** (replaced in Task 17)

`src/main/java/dev/tongt/mcserverauth/McServerAuthPlugin.java`
```java
package dev.tongt.mcserverauth;

import org.bukkit.plugin.java.JavaPlugin;

public final class McServerAuthPlugin extends JavaPlugin {
}
```

- [ ] **Step 5: Extend `.gitignore`**

Final content:
```
run/
build/
.gradle/
*.db
```
(Already present from the spec commit; confirm, no change needed if identical.)

- [ ] **Step 6: Build**

Run: `.\gradlew.bat build`
Expected: `BUILD SUCCESSFUL`; `build\libs\mc-server-auth-0.1.0.jar` exists.

- [ ] **Step 7: Commit**

```powershell
git add settings.gradle.kts build.gradle.kts gradlew gradlew.bat gradle src .gitignore
git commit -m "build: scaffold Gradle project for Paper 26.3 plugin`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Test clock helper + AuthConfig

**Files:**
- Create: `src/test/java/dev/tongt/mcserverauth/testutil/MutableClock.java`
- Create: `src/main/resources/config.yml`
- Create: `src/main/java/dev/tongt/mcserverauth/config/AuthConfig.java`
- Test: `src/test/java/dev/tongt/mcserverauth/config/AuthConfigTest.java`

- [ ] **Step 1: Write `MutableClock`**

```java
package dev.tongt.mcserverauth.testutil;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class MutableClock extends Clock {
    private Instant now;

    public MutableClock(Instant start) {
        this.now = start;
    }

    public void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
```

- [ ] **Step 2: Write `src/main/resources/config.yml`**

```yaml
# Failed password attempts allowed per connection before the player is disconnected.
max-attempts-per-connection: 3
ip-ban:
  failures: 5          # failures from one IP ...
  window-minutes: 10   # ... within this window
  ban-minutes: 10      # result in a temporary ban
login-timeout-seconds: 60
# How long a dialog that waits for an emailed code stays open.
code-wait-timeout-minutes: 10
password:
  min-length: 6
  max-length: 64
session-remember:
  enabled: false       # skip dialogs on reconnect from the same IP
  minutes: 5
email:
  max-accounts-per-email: 3
  code-valid-minutes: 15
  code-max-attempts: 5
  smtp:
    host: ""           # e.g. smtp.gmail.com; blank disables registration and reset
    port: 587
    security: starttls # starttls | ssl | none (none only for a local test mail server)
    username: ""
    password: ""       # app password; never commit a real one
    from: ""           # e.g. "My Server <noreply@example.com>"
```

- [ ] **Step 3: Write the failing test**

```java
package dev.tongt.mcserverauth.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class AuthConfigTest {

    private static YamlConfiguration defaults() {
        var stream = Objects.requireNonNull(AuthConfigTest.class.getResourceAsStream("/config.yml"));
        return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }

    @Test
    void parsesBundledDefaults() {
        AuthConfig config = AuthConfig.from(defaults());

        assertEquals(3, config.maxAttemptsPerConnection());
        assertEquals(5, config.ipBanFailures());
        assertEquals(Duration.ofMinutes(10), config.ipBanWindow());
        assertEquals(Duration.ofSeconds(60), config.loginTimeout());
        assertEquals(Duration.ofMinutes(10), config.codeWaitTimeout());
        assertEquals(6, config.passwordMinLength());
        assertEquals(64, config.passwordMaxLength());
        assertFalse(config.sessionRememberEnabled());
        assertEquals(Duration.ofMinutes(15), config.codeValidity());
        assertEquals(AuthConfig.Security.STARTTLS, config.smtp().security());
        assertFalse(config.smtp().configured());
    }

    @Test
    void rejectsMinLengthAboveMaxLength() {
        YamlConfiguration yaml = defaults();
        yaml.set("password.min-length", 70);
        yaml.set("password.max-length", 10);
        var e = assertThrows(IllegalArgumentException.class, () -> AuthConfig.from(yaml));
        assertTrue(e.getMessage().contains("password.min-length"));
    }

    @Test
    void rejectsZero() {
        YamlConfiguration yaml = defaults();
        yaml.set("max-attempts-per-connection", 0);
        var e = assertThrows(IllegalArgumentException.class, () -> AuthConfig.from(yaml));
        assertTrue(e.getMessage().contains("max-attempts-per-connection"));
    }

    @Test
    void rejectsUnknownSmtpSecurity() {
        YamlConfiguration yaml = defaults();
        yaml.set("email.smtp.security", "tls13");
        var e = assertThrows(IllegalArgumentException.class, () -> AuthConfig.from(yaml));
        assertTrue(e.getMessage().contains("email.smtp.security"));
    }

    @Test
    void smtpConfiguredNeedsHostAndFrom() {
        YamlConfiguration yaml = defaults();
        yaml.set("email.smtp.host", "127.0.0.1");
        assertFalse(AuthConfig.from(yaml).smtp().configured());
        yaml.set("email.smtp.from", "Test <noreply@example.test>");
        assertTrue(AuthConfig.from(yaml).smtp().configured());
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.config.AuthConfigTest"`
Expected: compilation FAIL, `cannot find symbol: class AuthConfig`.

- [ ] **Step 5: Write `AuthConfig`**

```java
package dev.tongt.mcserverauth.config;

import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.Locale;

public record AuthConfig(
        int maxAttemptsPerConnection,
        int ipBanFailures,
        Duration ipBanWindow,
        Duration ipBanDuration,
        Duration loginTimeout,
        Duration codeWaitTimeout,
        int passwordMinLength,
        int passwordMaxLength,
        boolean sessionRememberEnabled,
        Duration sessionRememberDuration,
        int maxAccountsPerEmail,
        Duration codeValidity,
        int codeMaxAttempts,
        Smtp smtp) {

    public enum Security { STARTTLS, SSL, NONE }

    public record Smtp(String host, int port, Security security, String username, String password, String from) {
        public boolean configured() {
            return !host.isBlank() && !from.isBlank();
        }
    }

    public static AuthConfig from(ConfigurationSection c) {
        int minLength = positive(c, "password.min-length");
        int maxLength = positive(c, "password.max-length");
        if (minLength > maxLength) {
            throw new IllegalArgumentException("password.min-length must not be greater than password.max-length");
        }
        if (maxLength > 64) {
            throw new IllegalArgumentException("password.max-length must be 64 or less");
        }
        Smtp smtp = new Smtp(
                c.getString("email.smtp.host", "").trim(),
                positive(c, "email.smtp.port"),
                security(c.getString("email.smtp.security", "starttls")),
                c.getString("email.smtp.username", ""),
                c.getString("email.smtp.password", ""),
                c.getString("email.smtp.from", "").trim());
        return new AuthConfig(
                positive(c, "max-attempts-per-connection"),
                positive(c, "ip-ban.failures"),
                minutes(c, "ip-ban.window-minutes"),
                minutes(c, "ip-ban.ban-minutes"),
                Duration.ofSeconds(positive(c, "login-timeout-seconds")),
                minutes(c, "code-wait-timeout-minutes"),
                minLength,
                maxLength,
                c.getBoolean("session-remember.enabled", false),
                minutes(c, "session-remember.minutes"),
                positive(c, "email.max-accounts-per-email"),
                minutes(c, "email.code-valid-minutes"),
                positive(c, "email.code-max-attempts"),
                smtp);
    }

    private static Security security(String raw) {
        try {
            return Security.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("email.smtp.security must be starttls, ssl or none");
        }
    }

    private static Duration minutes(ConfigurationSection c, String path) {
        return Duration.ofMinutes(positive(c, path));
    }

    private static int positive(ConfigurationSection c, String path) {
        if (!c.isInt(path)) {
            throw new IllegalArgumentException(path + " must be a whole number");
        }
        int value = c.getInt(path);
        if (value <= 0) {
            throw new IllegalArgumentException(path + " must be greater than 0");
        }
        return value;
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.config.AuthConfigTest"`
Expected: PASS (5 tests).

- [ ] **Step 7: Commit**

```powershell
git add src
git commit -m "feat: add validated plugin config`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: PasswordHasher

**Files:**
- Create: `src/main/java/dev/tongt/mcserverauth/security/PasswordHasher.java`
- Test: `src/test/java/dev/tongt/mcserverauth/security/PasswordHasherTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.tongt.mcserverauth.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {
    // Cost 4 keeps tests fast; production uses 12.
    private final PasswordHasher hasher = new PasswordHasher(4);

    @Test
    void hashIsNotThePassword() {
        assertNotEquals("secret123", hasher.hash("secret123"));
    }

    @Test
    void verifiesCorrectPasswordOnly() {
        String hash = hasher.hash("secret123");
        assertTrue(hasher.verify("secret123", hash));
        assertFalse(hasher.verify("secret124", hash));
    }

    @Test
    void samePasswordGivesDifferentHashes() {
        assertNotEquals(hasher.hash("secret123"), hasher.hash("secret123"));
    }

    @Test
    void overlongInputIsRejectedWithoutThrowing() {
        String hash = hasher.hash("secret123");
        assertFalse(hasher.verify("ก".repeat(64), hash)); // 192 UTF-8 bytes, above BCrypt's 72
    }

    @Test
    void malformedHashIsRejectedWithoutThrowing() {
        assertFalse(hasher.verify("secret123", "not-a-hash"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.security.PasswordHasherTest"`
Expected: compilation FAIL, `cannot find symbol: class PasswordHasher`.

- [ ] **Step 3: Write `PasswordHasher`**

```java
package dev.tongt.mcserverauth.security;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;

public final class PasswordHasher {
    /** BCrypt only uses the first 72 bytes; longer input is refused instead of silently truncated. */
    public static final int MAX_BYTES = 72;

    private final int cost;

    public PasswordHasher(int cost) {
        this.cost = cost;
    }

    public String hash(String password) {
        return BCrypt.withDefaults().hashToString(cost, password.toCharArray());
    }

    public boolean verify(String password, String hash) {
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return false;
        }
        try {
            return BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.security.PasswordHasherTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat: add BCrypt password hasher`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: PasswordRules

**Files:**
- Create: `src/main/java/dev/tongt/mcserverauth/security/PasswordRules.java`
- Test: `src/test/java/dev/tongt/mcserverauth/security/PasswordRulesTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.tongt.mcserverauth.security;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PasswordRulesTest {
    private final PasswordRules rules = new PasswordRules(6, 64);

    @Test
    void acceptsValidPassword() {
        assertEquals(Optional.empty(), rules.check("secret123", "secret123", pw -> false));
    }

    @Test
    void rejectsTooShortAndTooLong() {
        assertEquals(Optional.of("error.password-length"), rules.check("abc", "abc", pw -> false));
        String longPw = "a".repeat(65);
        assertEquals(Optional.of("error.password-length"), rules.check(longPw, longPw, pw -> false));
    }

    @Test
    void rejectsMoreThan72Bytes() {
        String thai = "ก".repeat(30); // 30 characters, 90 bytes
        assertEquals(Optional.of("error.password-too-long"), rules.check(thai, thai, pw -> false));
    }

    @Test
    void rejectsMismatch() {
        assertEquals(Optional.of("error.password-mismatch"), rules.check("secret123", "secret321", pw -> false));
    }

    @Test
    void rejectsServerPassword() {
        assertEquals(Optional.of("error.password-same-as-server"),
                rules.check("secret123", "secret123", pw -> pw.equals("secret123")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.security.PasswordRulesTest"`
Expected: compilation FAIL, `cannot find symbol: class PasswordRules`.

- [ ] **Step 3: Write `PasswordRules`**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.security.PasswordRulesTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat: add new-password rules`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: AttemptLimiter

**Files:**
- Create: `src/main/java/dev/tongt/mcserverauth/security/AttemptLimiter.java`
- Test: `src/test/java/dev/tongt/mcserverauth/security/AttemptLimiterTest.java`

Per-connection counting stays a local counter inside `LoginFlow` (Task 13); this class owns the cross-connection, per-IP part.

- [ ] **Step 1: Write the failing test**

```java
package dev.tongt.mcserverauth.security;

import dev.tongt.mcserverauth.testutil.MutableClock;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AttemptLimiterTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
    private final AttemptLimiter limiter =
            new AttemptLimiter(clock, 5, Duration.ofMinutes(10), Duration.ofMinutes(10));
    private final InetAddress ip = InetAddress.getLoopbackAddress();

    @Test
    void bansAfterFailuresInsideWindow() {
        for (int i = 0; i < 4; i++) limiter.recordFailure(ip);
        assertTrue(limiter.banRemaining(ip).isEmpty());
        limiter.recordFailure(ip);
        assertEquals(Duration.ofMinutes(10), limiter.banRemaining(ip).orElseThrow());
    }

    @Test
    void failuresOutsideWindowDoNotCount() {
        for (int i = 0; i < 4; i++) limiter.recordFailure(ip);
        clock.advance(Duration.ofMinutes(11));
        limiter.recordFailure(ip);
        assertTrue(limiter.banRemaining(ip).isEmpty());
    }

    @Test
    void banExpires() {
        for (int i = 0; i < 5; i++) limiter.recordFailure(ip);
        clock.advance(Duration.ofMinutes(10));
        assertTrue(limiter.banRemaining(ip).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.security.AttemptLimiterTest"`
Expected: compilation FAIL, `cannot find symbol: class AttemptLimiter`.

- [ ] **Step 3: Write `AttemptLimiter`**

```java
package dev.tongt.mcserverauth.security;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Counts password failures per IP and issues temporary bans. In-memory only. */
public final class AttemptLimiter {
    private final Clock clock;
    private final int failuresBeforeBan;
    private final Duration window;
    private final Duration banDuration;
    private final Map<InetAddress, Deque<Instant>> recentFailures = new HashMap<>();
    private final Map<InetAddress, Instant> bannedUntil = new HashMap<>();

    public AttemptLimiter(Clock clock, int failuresBeforeBan, Duration window, Duration banDuration) {
        this.clock = clock;
        this.failuresBeforeBan = failuresBeforeBan;
        this.window = window;
        this.banDuration = banDuration;
    }

    public synchronized void recordFailure(InetAddress ip) {
        Instant now = clock.instant();
        Deque<Instant> failures = recentFailures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        failures.addLast(now);
        while (!failures.isEmpty() && failures.peekFirst().isBefore(now.minus(window))) {
            failures.removeFirst();
        }
        if (failures.size() >= failuresBeforeBan) {
            bannedUntil.put(ip, now.plus(banDuration));
            recentFailures.remove(ip);
        }
    }

    public synchronized Optional<Duration> banRemaining(InetAddress ip) {
        Instant until = bannedUntil.get(ip);
        if (until == null) {
            return Optional.empty();
        }
        Duration left = Duration.between(clock.instant(), until);
        if (left.isNegative() || left.isZero()) {
            bannedUntil.remove(ip);
            return Optional.empty();
        }
        return Optional.of(left);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.security.AttemptLimiterTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat: add per-IP failure limiter with temporary bans`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: SessionCache (feature C)

**Files:**
- Create: `src/main/java/dev/tongt/mcserverauth/security/SessionCache.java`
- Test: `src/test/java/dev/tongt/mcserverauth/security/SessionCacheTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.tongt.mcserverauth.security;

import dev.tongt.mcserverauth.testutil.MutableClock;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SessionCacheTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
    private final InetAddress ip = InetAddress.getLoopbackAddress();

    @Test
    void disabledNeverRemembers() {
        SessionCache cache = new SessionCache(clock, false, Duration.ofMinutes(5));
        cache.remember("steve", ip);
        assertFalse(cache.isRemembered("steve", ip));
    }

    @Test
    void enabledRemembersUntilExpiry() {
        SessionCache cache = new SessionCache(clock, true, Duration.ofMinutes(5));
        cache.remember("steve", ip);
        assertTrue(cache.isRemembered("steve", ip));
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertFalse(cache.isRemembered("steve", ip));
    }

    @Test
    void differentIpIsNotRemembered() throws UnknownHostException {
        SessionCache cache = new SessionCache(clock, true, Duration.ofMinutes(5));
        cache.remember("steve", ip);
        assertFalse(cache.isRemembered("steve", InetAddress.getByName("10.0.0.2")));
    }

    @Test
    void forgetRemovesAllEntriesForName() {
        SessionCache cache = new SessionCache(clock, true, Duration.ofMinutes(5));
        cache.remember("steve", ip);
        cache.forget("steve");
        assertFalse(cache.isRemembered("steve", ip));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.security.SessionCacheTest"`
Expected: compilation FAIL, `cannot find symbol: class SessionCache`.

- [ ] **Step 3: Write `SessionCache`**

```java
package dev.tongt.mcserverauth.security;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Remembers recent successful logins per (name, IP) so a quick reconnect skips the dialogs. Off by default. */
public final class SessionCache {
    private record Key(String nameLower, InetAddress ip) {}

    private final Clock clock;
    private final boolean enabled;
    private final Duration ttl;
    private final Map<Key, Instant> entries = new ConcurrentHashMap<>();

    public SessionCache(Clock clock, boolean enabled, Duration ttl) {
        this.clock = clock;
        this.enabled = enabled;
        this.ttl = ttl;
    }

    public void remember(String nameLower, InetAddress ip) {
        if (enabled) {
            entries.put(new Key(nameLower, ip), clock.instant());
        }
    }

    public boolean isRemembered(String nameLower, InetAddress ip) {
        if (!enabled) {
            return false;
        }
        Key key = new Key(nameLower, ip);
        Instant at = entries.get(key);
        if (at == null) {
            return false;
        }
        if (clock.instant().isAfter(at.plus(ttl))) {
            entries.remove(key);
            return false;
        }
        return true;
    }

    public void forget(String nameLower) {
        entries.keySet().removeIf(key -> key.nameLower().equals(nameLower));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.security.SessionCacheTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat: add optional session remember cache`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: EmailAddress

**Files:**
- Create: `src/main/java/dev/tongt/mcserverauth/email/EmailAddress.java`
- Test: `src/test/java/dev/tongt/mcserverauth/email/EmailAddressTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.tongt.mcserverauth.email;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EmailAddressTest {

    @Test
    void parsesAndLowercasesDomainOnly() {
        assertEquals("Tong.T@gmail.com", EmailAddress.parse("  Tong.T@GMail.COM ").orElseThrow().value());
    }

    @Test
    void rejectsBadShapes() {
        assertEquals(Optional.empty(), EmailAddress.parse(""));
        assertEquals(Optional.empty(), EmailAddress.parse(null));
        assertEquals(Optional.empty(), EmailAddress.parse("no-at-sign"));
        assertEquals(Optional.empty(), EmailAddress.parse("a@b"));
        assertEquals(Optional.empty(), EmailAddress.parse("a@@b.com"));
        assertEquals(Optional.empty(), EmailAddress.parse("a b@c.com"));
    }

    @Test
    void rejectsTooLong() {
        assertEquals(Optional.empty(), EmailAddress.parse("a".repeat(65) + "@example.com"));
        assertEquals(Optional.empty(), EmailAddress.parse("a@" + "b".repeat(250) + ".com"));
    }

    @Test
    void masksLocalPart() {
        assertEquals("t***@gmail.com", new EmailAddress("tongt@gmail.com").masked());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.email.EmailAddressTest"`
Expected: compilation FAIL, `cannot find symbol: class EmailAddress`.

- [ ] **Step 3: Write `EmailAddress`**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.email.EmailAddressTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat: add email address parsing and masking`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: CodeService

**Files:**
- Create: `src/main/java/dev/tongt/mcserverauth/email/CodeService.java`
- Test: `src/test/java/dev/tongt/mcserverauth/email/CodeServiceTest.java`

Send limits come from the spec table and are constants on `Purpose`:
REGISTER — 60 s per name, 5 per IP per hour; RESET — 15 min per name, 3 per IP per hour.
The "max 3 register sends per connection" limit is a counter in `LoginFlow`.

- [ ] **Step 1: Write the failing test**

```java
package dev.tongt.mcserverauth.email;

import dev.tongt.mcserverauth.email.CodeService.Check;
import dev.tongt.mcserverauth.email.CodeService.CoolingDown;
import dev.tongt.mcserverauth.email.CodeService.Issued;
import dev.tongt.mcserverauth.email.CodeService.Purpose;
import dev.tongt.mcserverauth.testutil.MutableClock;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CodeServiceTest {
    private static final String IP = "127.0.0.1";
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-17T00:00:00Z"));
    private final CodeService codes = new CodeService(clock, new SecureRandom(), Duration.ofMinutes(15), 5);

    private String issue(Purpose purpose, String subject) {
        return assertInstanceOf(Issued.class, codes.issue(purpose, subject, IP)).code();
    }

    @Test
    void codeIsSixDigitsAndWorksOnce() {
        String code = issue(Purpose.REGISTER, "steve");
        assertTrue(code.matches("\\d{6}"));
        assertEquals(Check.OK, codes.verify(Purpose.REGISTER, "steve", code));
        assertEquals(Check.NONE, codes.verify(Purpose.REGISTER, "steve", code));
    }

    @Test
    void purposesAreSeparate() {
        String code = issue(Purpose.REGISTER, "steve");
        assertEquals(Check.NONE, codes.verify(Purpose.RESET, "steve", code));
    }

    @Test
    void wrongAttemptsBurnTheCode() {
        String code = issue(Purpose.RESET, "steve");
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 4; i++) {
            assertEquals(Check.WRONG, codes.verify(Purpose.RESET, "steve", wrong));
        }
        assertEquals(Check.BURNED, codes.verify(Purpose.RESET, "steve", wrong));
        assertEquals(Check.NONE, codes.verify(Purpose.RESET, "steve", code));
    }

    @Test
    void codeExpires() {
        String code = issue(Purpose.RESET, "steve");
        assertTrue(codes.hasLiveCode(Purpose.RESET, "steve"));
        clock.advance(Duration.ofMinutes(15));
        assertFalse(codes.hasLiveCode(Purpose.RESET, "steve"));
        assertEquals(Check.NONE, codes.verify(Purpose.RESET, "steve", code));
    }

    @Test
    void registerCooldownThenNewCodeReplacesOld() {
        String first = issue(Purpose.REGISTER, "steve");
        CoolingDown wait = assertInstanceOf(CoolingDown.class, codes.issue(Purpose.REGISTER, "steve", IP));
        assertEquals(Duration.ofSeconds(60), wait.remaining());
        clock.advance(Duration.ofSeconds(60));
        String second = issue(Purpose.REGISTER, "steve");
        if (!first.equals(second)) {
            assertEquals(Check.WRONG, codes.verify(Purpose.REGISTER, "steve", first));
        }
        assertEquals(Check.OK, codes.verify(Purpose.REGISTER, "steve", second));
    }

    @Test
    void resetCooldownIsFifteenMinutes() {
        issue(Purpose.RESET, "steve");
        CoolingDown wait = assertInstanceOf(CoolingDown.class, codes.issue(Purpose.RESET, "steve", IP));
        assertEquals(Duration.ofMinutes(15), wait.remaining());
    }

    @Test
    void resetPerIpLimitIsThreePerHour() {
        issue(Purpose.RESET, "a");
        issue(Purpose.RESET, "b");
        issue(Purpose.RESET, "c");
        assertInstanceOf(CoolingDown.class, codes.issue(Purpose.RESET, "d", IP));
        clock.advance(Duration.ofHours(1));
        issue(Purpose.RESET, "d");
    }

    @Test
    void cancelIssueRefundsTheSend() {
        issue(Purpose.RESET, "steve");
        codes.cancelIssue(Purpose.RESET, "steve", IP);
        assertFalse(codes.hasLiveCode(Purpose.RESET, "steve"));
        issue(Purpose.RESET, "steve");
    }

    @Test
    void invalidateRemovesAllPurposes() {
        issue(Purpose.REGISTER, "steve");
        issue(Purpose.RESET, "steve");
        codes.invalidate("steve");
        assertFalse(codes.hasLiveCode(Purpose.REGISTER, "steve"));
        assertFalse(codes.hasLiveCode(Purpose.RESET, "steve"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.email.CodeServiceTest"`
Expected: compilation FAIL, `cannot find symbol: class CodeService`.

- [ ] **Step 3: Write `CodeService`**

```java
package dev.tongt.mcserverauth.email;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Issues and checks short-lived 6-digit email codes. In-memory; a restart invalidates all codes. */
public final class CodeService {
    private static final Duration HOUR = Duration.ofHours(1);

    public enum Purpose {
        REGISTER(Duration.ofSeconds(60), 5),
        RESET(Duration.ofMinutes(15), 3);

        private final Duration cooldown;
        private final int sendsPerIpPerHour;

        Purpose(Duration cooldown, int sendsPerIpPerHour) {
            this.cooldown = cooldown;
            this.sendsPerIpPerHour = sendsPerIpPerHour;
        }
    }

    public sealed interface Issue permits Issued, CoolingDown {}

    public record Issued(String code) implements Issue {}

    public record CoolingDown(Duration remaining) implements Issue {}

    /** OK: accepted and consumed. WRONG: try again. BURNED: this attempt used up the code. NONE: no live code. */
    public enum Check { OK, WRONG, BURNED, NONE }

    private record Key(Purpose purpose, String subject) {}

    private record IpKey(Purpose purpose, String ip) {}

    private static final class LiveCode {
        final byte[] salt;
        final byte[] hash;
        final Instant expires;
        int wrongAttempts;

        LiveCode(byte[] salt, byte[] hash, Instant expires) {
            this.salt = salt;
            this.hash = hash;
            this.expires = expires;
        }
    }

    private final Clock clock;
    private final SecureRandom random;
    private final Duration validity;
    private final int maxAttempts;
    private final Map<Key, LiveCode> live = new HashMap<>();
    private final Map<Key, Instant> lastSent = new HashMap<>();
    private final Map<IpKey, Deque<Instant>> ipSends = new HashMap<>();

    public CodeService(Clock clock, SecureRandom random, Duration validity, int maxAttempts) {
        this.clock = clock;
        this.random = random;
        this.validity = validity;
        this.maxAttempts = maxAttempts;
    }

    public synchronized Issue issue(Purpose purpose, String subject, String ip) {
        Instant now = clock.instant();
        Key key = new Key(purpose, subject);
        Instant last = lastSent.get(key);
        if (last != null && now.isBefore(last.plus(purpose.cooldown))) {
            return new CoolingDown(Duration.between(now, last.plus(purpose.cooldown)));
        }
        Deque<Instant> sends = ipSends.computeIfAbsent(new IpKey(purpose, ip), k -> new ArrayDeque<>());
        while (!sends.isEmpty() && !sends.peekFirst().isAfter(now.minus(HOUR))) {
            sends.removeFirst();
        }
        if (sends.size() >= purpose.sendsPerIpPerHour) {
            return new CoolingDown(Duration.between(now, sends.peekFirst().plus(HOUR)));
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        live.put(key, new LiveCode(salt, digest(salt, code), now.plus(validity)));
        lastSent.put(key, now);
        sends.addLast(now);
        return new Issued(code);
    }

    /** Undo the last issue() when the email could not be sent, so it does not count against limits. */
    public synchronized void cancelIssue(Purpose purpose, String subject, String ip) {
        Key key = new Key(purpose, subject);
        live.remove(key);
        lastSent.remove(key);
        Deque<Instant> sends = ipSends.get(new IpKey(purpose, ip));
        if (sends != null) {
            sends.pollLast();
        }
    }

    public synchronized Check verify(Purpose purpose, String subject, String code) {
        Key key = new Key(purpose, subject);
        LiveCode liveCode = live.get(key);
        if (liveCode == null) {
            return Check.NONE;
        }
        if (!clock.instant().isBefore(liveCode.expires)) {
            live.remove(key);
            return Check.NONE;
        }
        if (MessageDigest.isEqual(liveCode.hash, digest(liveCode.salt, code))) {
            live.remove(key);
            return Check.OK;
        }
        liveCode.wrongAttempts++;
        if (liveCode.wrongAttempts >= maxAttempts) {
            live.remove(key);
            return Check.BURNED;
        }
        return Check.WRONG;
    }

    public synchronized boolean hasLiveCode(Purpose purpose, String subject) {
        LiveCode liveCode = live.get(new Key(purpose, subject));
        return liveCode != null && clock.instant().isBefore(liveCode.expires);
    }

    public synchronized void invalidate(String subject) {
        live.keySet().removeIf(key -> key.subject().equals(subject));
    }

    private static byte[] digest(byte[] salt, String code) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(salt);
            sha.update(code.getBytes(StandardCharsets.UTF_8));
            return sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.email.CodeServiceTest"`
Expected: PASS (9 tests).

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat: add email code service with expiry and send limits`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Account + AccountStore

**Files:**
- Create: `src/main/java/dev/tongt/mcserverauth/storage/Account.java`
- Create: `src/main/java/dev/tongt/mcserverauth/storage/AccountStore.java`
- Test: `src/test/java/dev/tongt/mcserverauth/storage/AccountStoreTest.java`

- [ ] **Step 1: Write the failing test**

```java
package dev.tongt.mcserverauth.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AccountStoreTest {
    @TempDir
    Path dir;
    private AccountStore store;

    @BeforeEach
    void open() throws SQLException {
        store = AccountStore.open(dir.resolve("accounts.db"));
    }

    @AfterEach
    void close() throws SQLException {
        store.close();
    }

    @Test
    void createAndFindIgnoringCase() throws SQLException {
        store.create("Steve", "hash1", "steve@example.com", 1000L);
        Account account = store.find("STEVE").orElseThrow();
        assertEquals("steve", account.nameLower());
        assertEquals("Steve", account.displayName());
        assertEquals("hash1", account.passwordHash());
        assertEquals("steve@example.com", account.email());
        assertEquals(1000L, account.createdAt());
        assertNull(account.lastLoginAt());
    }

    @Test
    void missingAccountIsEmpty() throws SQLException {
        assertEquals(Optional.empty(), store.find("nobody"));
    }

    @Test
    void duplicateNameIsRejected() throws SQLException {
        store.create("Steve", "hash1", "steve@example.com", 1000L);
        assertThrows(SQLException.class, () -> store.create("steve", "hash2", "x@example.com", 2000L));
    }

    @Test
    void updatePasswordAndRecordLogin() throws SQLException {
        store.create("Steve", "hash1", "steve@example.com", 1000L);
        assertTrue(store.updatePassword("steve", "hash2"));
        store.recordLogin("Steve", 5000L, "127.0.0.1");
        Account account = store.find("steve").orElseThrow();
        assertEquals("hash2", account.passwordHash());
        assertEquals(5000L, account.lastLoginAt());
        assertEquals("127.0.0.1", account.lastIp());
    }

    @Test
    void deleteAndCountByEmail() throws SQLException {
        store.create("A", "h", "same@example.com", 1L);
        store.create("B", "h", "same@example.com", 1L);
        assertEquals(2, store.countByEmail("same@example.com"));
        assertTrue(store.delete("a"));
        assertFalse(store.delete("a"));
        assertEquals(1, store.countByEmail("same@example.com"));
    }

    @Test
    void serverPasswordHashRoundTrip() throws SQLException {
        assertEquals(Optional.empty(), store.serverPasswordHash());
        store.setServerPasswordHash("h1");
        store.setServerPasswordHash("h2");
        assertEquals(Optional.of("h2"), store.serverPasswordHash());
    }

    @Test
    void dataSurvivesReopen() throws SQLException {
        store.create("Steve", "hash1", "steve@example.com", 1000L);
        store.close();
        store = AccountStore.open(dir.resolve("accounts.db"));
        assertTrue(store.find("steve").isPresent());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.storage.AccountStoreTest"`
Expected: compilation FAIL, `cannot find symbol: class AccountStore`.

- [ ] **Step 3: Write `Account`**

```java
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
```

(`org.jspecify` comes with paper-api. If the import does not resolve, use `org.jetbrains.annotations.Nullable` instead — also on paper-api's classpath.)

- [ ] **Step 4: Write `AccountStore`**

```java
package dev.tongt.mcserverauth.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Optional;

/** SQLite-backed account storage. Thread-safe; call it off the main server thread. */
public final class AccountStore implements AutoCloseable {
    private static final String SELECT_ACCOUNT = """
            SELECT name_lower, name_display, password_hash, email, created_at, last_login_at, last_ip
            FROM accounts WHERE name_lower = ?""";

    private final Connection connection;

    private AccountStore(Connection connection) {
        this.connection = connection;
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static AccountStore open(Path file) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite driver not found", e);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS accounts (
                      name_lower    TEXT PRIMARY KEY,
                      name_display  TEXT NOT NULL,
                      password_hash TEXT NOT NULL,
                      email         TEXT NOT NULL,
                      created_at    INTEGER NOT NULL,
                      last_login_at INTEGER,
                      last_ip       TEXT
                    )""");
            statement.execute("CREATE INDEX IF NOT EXISTS accounts_email ON accounts(email)");
            statement.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        return new AccountStore(connection);
    }

    public synchronized Optional<Account> find(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(SELECT_ACCOUNT)) {
            ps.setString(1, key(name));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                long lastLogin = rs.getLong(6);
                Long lastLoginAt = rs.wasNull() ? null : lastLogin;
                return Optional.of(new Account(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), lastLoginAt, rs.getString(7)));
            }
        }
    }

    public synchronized void create(String displayName, String passwordHash, String email, long now) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO accounts (name_lower, name_display, password_hash, email, created_at) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, key(displayName));
            ps.setString(2, displayName);
            ps.setString(3, passwordHash);
            ps.setString(4, email);
            ps.setLong(5, now);
            ps.executeUpdate();
        }
    }

    public synchronized boolean updatePassword(String name, String passwordHash) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE accounts SET password_hash = ? WHERE name_lower = ?")) {
            ps.setString(1, passwordHash);
            ps.setString(2, key(name));
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized void recordLogin(String name, long now, String ip) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE accounts SET last_login_at = ?, last_ip = ? WHERE name_lower = ?")) {
            ps.setLong(1, now);
            ps.setString(2, ip);
            ps.setString(3, key(name));
            ps.executeUpdate();
        }
    }

    public synchronized boolean delete(String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM accounts WHERE name_lower = ?")) {
            ps.setString(1, key(name));
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized int countByEmail(String email) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM accounts WHERE email = ?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public synchronized Optional<String> serverPasswordHash() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM settings WHERE key = 'server_password_hash'")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    public synchronized void setServerPasswordHash(String hash) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO settings (key, value) VALUES ('server_password_hash', ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value""")) {
            ps.setString(1, hash);
            ps.executeUpdate();
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.storage.AccountStoreTest"`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```powershell
git add src
git commit -m "feat: add SQLite account store`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: Messages + messages.yml

**Files:**
- Create: `src/main/resources/messages.yml`
- Create: `src/main/java/dev/tongt/mcserverauth/config/Messages.java`
- Test: `src/test/java/dev/tongt/mcserverauth/config/MessagesTest.java`

`MessagesTest.everyKeyUsedInCodeExists` scans `src/main/java` for message-key string literals, so a typo in any later task fails the build. Run the full test suite after Tasks 12–17 for this reason.

- [ ] **Step 1: Write `src/main/resources/messages.yml`**

```yaml
# Player-facing text. MiniMessage format: https://docs.advntr.dev/minimessage/format
# Placeholders in <angle brackets> are filled in by the plugin.
dialog:
  server-password:
    title: "Server password"
    body: "This server is private. Enter the server password to continue."
  register:
    title: "Create your account"
    body: "First time here. Choose a personal password and enter your email. We send a code to confirm the email. Do not reuse a password from another account."
  verify-email:
    title: "Confirm your email"
    body: "We sent a 6-digit code to <email>. Enter it below."
  login:
    title: "Log in"
    body: "Enter your personal password."
  forgot:
    title: "Forgot password"
    body: "We can send a 6-digit code to <email>."
  reset-code:
    title: "Reset your password"
    body: "Enter the 6-digit code sent to <email>."
  reset-password:
    title: "Choose a new password"
  change-password:
    title: "Change password"
  set-server-password:
    title: "Set server password"
input:
  password: "Password"
  confirm: "Confirm password"
  email: "Email"
  code: "6-digit code"
  current-password: "Current password"
  new-password: "New password"
button:
  submit: "Continue"
  login: "Log in"
  forgot: "Forgot password?"
  cancel: "Cancel"
  confirm: "Confirm"
  resend: "Resend code"
  back: "Back"
  send-code: "Send me a code"
  have-code: "I already have a code"
error:
  wrong-server-password: "<red>Wrong server password."
  wrong-password: "<red>Wrong password."
  password-length: "<red>Password must be <min>-<max> characters."
  password-too-long: "<red>Password is too long. Use fewer special characters."
  password-mismatch: "<red>The two passwords do not match."
  password-same-as-server: "<red>Your password must be different from the server password."
  email-invalid: "<red>That email address does not look right."
  email-limit: "<red>Too many accounts already use this email."
  code-wrong: "<red>Wrong code."
  code-expired: "<red>That code is no longer valid. Press Resend code to get a new one."
  code-cooldown: "<red>A code was sent recently. Try again in <seconds> seconds."
  too-many-codes: "<red>Too many codes requested. Reconnect and try again later."
  email-send-failed: "<red>Could not send the email. Try again later."
  reset-unavailable: "<red>Password reset is unavailable. Contact an admin."
  no-live-code: "<red>No valid code for this account. Ask for a new one."
info:
  code-resent: "<green>A new code was sent."
kick:
  cancelled: "Login cancelled."
  timeout: "Login timed out."
  too-many-attempts: "Too many wrong attempts."
  ip-banned: "Too many wrong attempts from your address. Try again in <minutes> minutes."
  name-in-use: "A player with this name is already online or logging in."
  wrong-case: "This account is registered as <name>. Use exactly that name, including capital letters."
  unavailable: "Login is unavailable right now. Try again later."
  registration-unavailable: "Registration is unavailable. Contact an admin."
  account-reset: "Your account was reset by an admin. Reconnect to register again."
  lockdown: "Login system unavailable. Contact an admin."
command:
  players-only: "<red>Only players can use this command."
  password-changed: "<green>Your password was changed."
  usage: "<yellow>/authadmin reset <name> | /authadmin setserverpassword [password] | /authadmin info <name>"
  reset-done: "<green>Account <name> was reset."
  no-such-account: "<red>No account named <name>."
  server-password-set: "<green>Server password changed. It applies to new logins."
  console-password-logged: "<yellow>Warning: the server log records this command, including the password."
  info: "<gray>Name: <white><name></white> | Email: <white><email></white> | Created: <white><created></white> | Last login: <white><last_login></white> | Last IP: <white><last_ip></white>"
  error: "<red>Something went wrong. See the server log."
email:
  register:
    subject: "Your verification code"
    body: |
      Hi {name},

      Your verification code is: {code}

      It is valid for {minutes} minutes.

      If this wasn't you, ignore this email.
  reset:
    subject: "Your password reset code"
    body: |
      Hi {name},

      Your password reset code is: {code}

      It is valid for {minutes} minutes.

      If this wasn't you, ignore this email. Your password has not changed.
```

- [ ] **Step 2: Write the failing test**

```java
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
```

- [ ] **Step 3: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.config.MessagesTest"`
Expected: compilation FAIL, `cannot find symbol: class Messages`.

- [ ] **Step 4: Write `Messages`**

```java
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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.config.MessagesTest"`
Expected: PASS (4 tests). `everyKeyUsedInCodeExists` passes: `PasswordRules` keys exist in the yml.

- [ ] **Step 6: Commit**

```powershell
git add src
git commit -m "feat: add player-facing messages and loader`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: MailSender + SmtpMailSender

**Files:**
- Create: `src/main/java/dev/tongt/mcserverauth/email/MailSender.java`
- Create: `src/main/java/dev/tongt/mcserverauth/email/SmtpMailSender.java`
- Test: `src/test/java/dev/tongt/mcserverauth/email/SmtpMailSenderTest.java`

Real delivery is checked against Mailpit in Task 19.

- [ ] **Step 1: Write the failing test**

```java
package dev.tongt.mcserverauth.email;

import dev.tongt.mcserverauth.config.AuthConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SmtpMailSenderTest {

    @Test
    void unconfiguredSenderIsUnavailableAndFails() {
        var smtp = new AuthConfig.Smtp("", 587, AuthConfig.Security.STARTTLS, "", "", "");
        try (SmtpMailSender sender = new SmtpMailSender(smtp)) {
            assertFalse(sender.available());
            var future = sender.send("a@example.com", "s", "b");
            assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void unreachableServerFailsTheFuture() {
        // Port 1 on loopback is closed; the send must fail, not hang.
        var smtp = new AuthConfig.Smtp("127.0.0.1", 1, AuthConfig.Security.NONE, "", "", "Test <t@example.test>");
        try (SmtpMailSender sender = new SmtpMailSender(smtp)) {
            assertTrue(sender.available());
            var future = sender.send("a@example.com", "s", "b");
            assertThrows(ExecutionException.class, () -> future.get(30, TimeUnit.SECONDS));
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.email.SmtpMailSenderTest"`
Expected: compilation FAIL, `cannot find symbol: class SmtpMailSender`.

- [ ] **Step 3: Write `MailSender`**

```java
package dev.tongt.mcserverauth.email;

import java.util.concurrent.CompletableFuture;

public interface MailSender extends AutoCloseable {
    /** False when SMTP is not configured; registration and reset are then refused. */
    boolean available();

    /** Sends one plain-text email in the background. The future fails if delivery fails. */
    CompletableFuture<Void> send(String to, String subject, String body);

    @Override
    void close();
}
```

- [ ] **Step 4: Write `SmtpMailSender`**

```java
package dev.tongt.mcserverauth.email;

import dev.tongt.mcserverauth.config.AuthConfig;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Date;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SmtpMailSender implements MailSender {
    private static final String TIMEOUT_MILLIS = "10000";

    private final AuthConfig.Smtp smtp;
    private final ExecutorService executor;

    public SmtpMailSender(AuthConfig.Smtp smtp) {
        this.smtp = smtp;
        // Jakarta Mail finds its SMTP provider through the context class loader, which must be the plugin's.
        ClassLoader pluginLoader = SmtpMailSender.class.getClassLoader();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "McServerAuth-mail");
            thread.setDaemon(true);
            thread.setContextClassLoader(pluginLoader);
            return thread;
        });
    }

    @Override
    public boolean available() {
        return smtp.configured();
    }

    @Override
    public CompletableFuture<Void> send(String to, String subject, String body) {
        if (!available()) {
            return CompletableFuture.failedFuture(new IllegalStateException("SMTP is not configured"));
        }
        return CompletableFuture.runAsync(() -> {
            try {
                deliver(to, subject, body);
            } catch (MessagingException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    private void deliver(String to, String subject, String body) throws MessagingException {
        boolean auth = !smtp.username().isBlank();
        Properties props = new Properties();
        props.put("mail.smtp.host", smtp.host());
        props.put("mail.smtp.port", Integer.toString(smtp.port()));
        props.put("mail.smtp.auth", Boolean.toString(auth));
        props.put("mail.smtp.connectiontimeout", TIMEOUT_MILLIS);
        props.put("mail.smtp.timeout", TIMEOUT_MILLIS);
        props.put("mail.smtp.writetimeout", TIMEOUT_MILLIS);
        switch (smtp.security()) {
            case STARTTLS -> {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
                props.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case SSL -> {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case NONE -> {
            }
        }
        MimeMessage message = new MimeMessage(Session.getInstance(props));
        message.setFrom(new InternetAddress(smtp.from()));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, true));
        message.setSubject(subject, "UTF-8");
        message.setText(body, "UTF-8");
        message.setSentDate(new Date());
        if (auth) {
            Transport.send(message, smtp.username(), smtp.password());
        } else {
            Transport.send(message);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "dev.tongt.mcserverauth.email.SmtpMailSenderTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```powershell
git add src
git commit -m "feat: add SMTP mail sender`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: DialogWaiter + DialogFactory

**Files:**
- Create: `src/main/java/dev/tongt/mcserverauth/flow/DialogWaiter.java`
- Create: `src/main/java/dev/tongt/mcserverauth/flow/DialogFactory.java`

These need a running server (dialog registry), so they are verified by compiling here and by the manual tests in Task 19.

Paper API facts this relies on (checked in paper-api 26.3.build.200 sources):
- `Dialog.create(f -> f.empty().base(DialogBase).type(DialogType))`
- `DialogBase.builder(Component).canCloseWithEscape(..).afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE).body(List).inputs(List).build()`
- `DialogType.multiAction(List<ActionButton>).columns(int).build()`
- `DialogInput.text(key, label).width(int).maxLength(int).build()`
- `ActionButton.builder(label).width(int).action(DialogAction.customClick(Key, null)).build()`
- `DialogBody.plainMessage(Component)`
- `PlayerCustomClickEvent#getIdentifier()`, `#getDialogResponseView()` (`getText(key)`), `#getCommonConnection()`
- `PlayerConfigurationConnection#getProfile()`, `PlayerGameConnection#getPlayer()`
- `com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent#getPlayerUniqueId()`

- [ ] **Step 1: Write `DialogWaiter`**

```java
package dev.tongt.mcserverauth.flow;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.key.Key;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Turns dialog button clicks into a blocking call: show a dialog, wait for the player's answer. */
public final class DialogWaiter implements Listener {
    public static final String NAMESPACE = "mcserverauth";

    public record Response(String action, @Nullable DialogResponseView view) {
        public static final Response CLOSED = new Response("closed", null);

        public boolean is(String expected) {
            return action.equals(expected);
        }

        public String text(String inputKey) {
            if (view == null) {
                return "";
            }
            String value = view.getText(inputKey);
            return value == null ? "" : value;
        }
    }

    private final Map<UUID, CompletableFuture<Response>> pending = new ConcurrentHashMap<>();

    /** Blocks the calling thread. Never call from the main server thread. */
    public Response await(UUID playerId, Runnable showDialog, Duration timeout) throws TimeoutException {
        CompletableFuture<Response> future = new CompletableFuture<>();
        CompletableFuture<Response> previous = pending.put(playerId, future);
        if (previous != null) {
            previous.complete(Response.CLOSED);
        }
        try {
            showDialog.run();
            return future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.CLOSED;
        } catch (ExecutionException e) {
            return Response.CLOSED;
        } finally {
            pending.remove(playerId, future);
        }
    }

    public void cancel(UUID playerId) {
        CompletableFuture<Response> future = pending.remove(playerId);
        if (future != null) {
            future.complete(Response.CLOSED);
        }
    }

    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent event) {
        Key id = event.getIdentifier();
        if (!id.namespace().equals(NAMESPACE)) {
            return;
        }
        UUID playerId = switch (event.getCommonConnection()) {
            case PlayerConfigurationConnection configuring -> configuring.getProfile().getId();
            case PlayerGameConnection playing -> playing.getPlayer().getUniqueId();
            default -> null;
        };
        if (playerId == null) {
            return;
        }
        CompletableFuture<Response> future = pending.get(playerId);
        if (future != null) {
            future.complete(new Response(id.value(), event.getDialogResponseView()));
        }
    }

    @EventHandler
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        cancel(event.getPlayerUniqueId());
    }
}
```

- [ ] **Step 2: Write `DialogFactory`**

```java
package dev.tongt.mcserverauth.flow;

import dev.tongt.mcserverauth.config.Messages;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Builds every dialog the plugin shows. Button actions are the constants below. */
public final class DialogFactory {
    public static final String SUBMIT = "submit";
    public static final String CANCEL = "cancel";
    public static final String FORGOT = "forgot";
    public static final String SEND_CODE = "send_code";
    public static final String HAVE_CODE = "have_code";
    public static final String RESEND = "resend";
    public static final String BACK = "back";

    private static final int INPUT_WIDTH = 250;
    private static final int BUTTON_WIDTH = 120;
    private static final int EMAIL_MAX_LENGTH = 254;
    private static final int CODE_LENGTH = 6;

    private final Messages messages;
    private final int passwordMaxLength;

    public DialogFactory(Messages messages, int passwordMaxLength) {
        this.messages = messages;
        this.passwordMaxLength = passwordMaxLength;
    }

    public Dialog serverPassword(@Nullable Component error) {
        return build("dialog.server-password.title", body("dialog.server-password.body", error),
                List.of(password("password", "input.password")),
                List.of(button("button.submit", SUBMIT), button("button.cancel", CANCEL)), false);
    }

    public Dialog register(@Nullable Component error) {
        return build("dialog.register.title", body("dialog.register.body", error),
                List.of(password("password", "input.password"), password("confirm", "input.confirm"),
                        text("email", "input.email", EMAIL_MAX_LENGTH)),
                List.of(button("button.submit", SUBMIT), button("button.cancel", CANCEL)), false);
    }

    public Dialog login(@Nullable Component error) {
        return build("dialog.login.title", body("dialog.login.body", error),
                List.of(password("password", "input.password")),
                List.of(button("button.login", SUBMIT), button("button.forgot", FORGOT), button("button.cancel", CANCEL)),
                false);
    }

    public Dialog forgotMenu(String maskedEmail, @Nullable Component error) {
        return build("dialog.forgot.title", body("dialog.forgot.body", error, email(maskedEmail)),
                List.of(),
                List.of(button("button.send-code", SEND_CODE), button("button.have-code", HAVE_CODE),
                        button("button.back", BACK)),
                false);
    }

    /** Code entry for email verification (reset = false) or password reset (reset = true). */
    public Dialog code(boolean reset, String maskedEmail, @Nullable Component error) {
        String title = reset ? "dialog.reset-code.title" : "dialog.verify-email.title";
        String bodyKey = reset ? "dialog.reset-code.body" : "dialog.verify-email.body";
        return build(title, body(bodyKey, error, email(maskedEmail)),
                List.of(text("code", "input.code", CODE_LENGTH)),
                List.of(button("button.confirm", SUBMIT), button("button.resend", RESEND), button("button.back", BACK)),
                false);
    }

    /** New password + confirm. Used for reset, and by admins to set the server password. */
    public Dialog newPassword(String titleKey, @Nullable Component error, boolean closable) {
        return build(titleKey, error == null ? List.of() : List.of(DialogBody.plainMessage(error)),
                List.of(password("password", "input.new-password"), password("confirm", "input.confirm")),
                List.of(button("button.submit", SUBMIT), button("button.cancel", CANCEL)), closable);
    }

    public Dialog changePassword(@Nullable Component error) {
        return build("dialog.change-password.title", error == null ? List.of() : List.of(DialogBody.plainMessage(error)),
                List.of(password("current", "input.current-password"), password("password", "input.new-password"),
                        password("confirm", "input.confirm")),
                List.of(button("button.submit", SUBMIT), button("button.cancel", CANCEL)), true);
    }

    private Dialog build(String titleKey, List<DialogBody> body, List<DialogInput> inputs,
                         List<ActionButton> buttons, boolean closable) {
        DialogBase base = DialogBase.builder(messages.get(titleKey))
                .canCloseWithEscape(closable)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .body(body)
                .inputs(inputs)
                .build();
        DialogType type = DialogType.multiAction(buttons).columns(Math.min(buttons.size(), 3)).build();
        return Dialog.create(factory -> factory.empty().base(base).type(type));
    }

    private List<DialogBody> body(String key, @Nullable Component error, TagResolver... resolvers) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(messages.get(key, resolvers)));
        if (error != null) {
            body.add(DialogBody.plainMessage(error));
        }
        return body;
    }

    private static TagResolver email(String maskedEmail) {
        return Placeholder.unparsed("email", maskedEmail);
    }

    private DialogInput password(String id, String labelKey) {
        return text(id, labelKey, passwordMaxLength);
    }

    private DialogInput text(String id, String labelKey, int maxLength) {
        return DialogInput.text(id, messages.get(labelKey)).width(INPUT_WIDTH).maxLength(maxLength).build();
    }

    private ActionButton button(String labelKey, String action) {
        return ActionButton.builder(messages.get(labelKey))
                .width(BUTTON_WIDTH)
                .action(DialogAction.customClick(Key.key(DialogWaiter.NAMESPACE, action), null))
                .build();
    }
}
```

- [ ] **Step 3: Compile and run all tests**

Run: `.\gradlew.bat build`
Expected: `BUILD SUCCESSFUL`. If a Paper signature differs, read the matching class in the paper-api sources jar (`~/.gradle/caches/modules-2/files-2.1/io.papermc.paper/paper-api/26.3.build.200-stable/`) and adjust; do not change behavior.

- [ ] **Step 4: Commit**

```powershell
git add src
git commit -m "feat: add dialog waiter and dialog factory`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: LoginFlow

**Files:**
- Create: `src/main/java/dev/tongt/mcserverauth/flow/LoginFlow.java`

Implements the spec's "Login flow" and "Reset flow" diagrams. Verified manually in Task 19.

- [ ] **Step 1: Write `LoginFlow`**

```java
package dev.tongt.mcserverauth.flow;

import dev.tongt.mcserverauth.config.AuthConfig;
import dev.tongt.mcserverauth.config.Messages;
import dev.tongt.mcserverauth.email.CodeService;
import dev.tongt.mcserverauth.email.CodeService.Purpose;
import dev.tongt.mcserverauth.email.EmailAddress;
import dev.tongt.mcserverauth.email.MailSender;
import dev.tongt.mcserverauth.flow.DialogWaiter.Response;
import dev.tongt.mcserverauth.security.AttemptLimiter;
import dev.tongt.mcserverauth.security.PasswordHasher;
import dev.tongt.mcserverauth.security.PasswordRules;
import dev.tongt.mcserverauth.security.SessionCache;
import dev.tongt.mcserverauth.storage.Account;
import dev.tongt.mcserverauth.storage.AccountStore;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs the whole login during the configuration phase, before the player enters the world.
 * Each handler call blocks Paper's async configuration thread until the player passes or is disconnected.
 */
public final class LoginFlow implements Listener {
    private static final int MAX_REGISTER_SENDS_PER_CONNECTION = 3;
    private static final long MAIL_SEND_TIMEOUT_SECONDS = 30;

    private enum Step { DONE, BACK, CANCEL }

    private record StepResult(Step step, @Nullable Component message) {
        static StepResult done() { return new StepResult(Step.DONE, null); }
        static StepResult back(@Nullable Component message) { return new StepResult(Step.BACK, message); }
        static StepResult cancel() { return new StepResult(Step.CANCEL, null); }
    }

    /** Per-connection state. */
    private static final class Session {
        final PlayerConfigurationConnection connection;
        final UUID id;
        final String name;
        final String key;
        final InetAddress ip;
        Instant deadline;
        int failures;
        int registerSends;

        Session(PlayerConfigurationConnection connection, UUID id, String name, InetAddress ip, Instant deadline) {
            this.connection = connection;
            this.id = id;
            this.name = name;
            this.key = AccountStore.key(name);
            this.ip = ip;
            this.deadline = deadline;
        }
    }

    private final AuthConfig config;
    private final Messages messages;
    private final DialogFactory dialogs;
    private final DialogWaiter waiter;
    private final AccountStore store;
    private final PasswordHasher hasher;
    private final PasswordRules rules;
    private final AttemptLimiter limiter;
    private final SessionCache sessions;
    private final CodeService codes;
    private final MailSender mail;
    private final Clock clock;
    private final Logger logger;

    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public LoginFlow(AuthConfig config, Messages messages, DialogFactory dialogs, DialogWaiter waiter,
                     AccountStore store, PasswordHasher hasher, PasswordRules rules, AttemptLimiter limiter,
                     SessionCache sessions, CodeService codes, MailSender mail, Clock clock, Logger logger) {
        this.config = config;
        this.messages = messages;
        this.dialogs = dialogs;
        this.waiter = waiter;
        this.store = store;
        this.hasher = hasher;
        this.rules = rules;
        this.limiter = limiter;
        this.sessions = sessions;
        this.codes = codes;
        this.mail = mail;
        this.clock = clock;
        this.logger = logger;
    }

    /** True while a connection with this name is going through the login dialogs. */
    public boolean isNameBusy(String name) {
        return inFlight.contains(AccountStore.key(name));
    }

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        PlayerConfigurationConnection connection = event.getConnection();
        UUID id = connection.getProfile().getId();
        String name = connection.getProfile().getName();
        if (id == null || name == null) {
            connection.disconnect(messages.get("kick.unavailable"));
            return;
        }
        if (authenticated.contains(id)) {
            return; // reconfiguration of a player who already logged in
        }
        String key = AccountStore.key(name);
        if (!inFlight.add(key)) {
            connection.disconnect(messages.get("kick.name-in-use"));
            return;
        }
        try {
            Session session = new Session(connection, id, name, connection.getClientAddress().getAddress(),
                    clock.instant().plus(config.loginTimeout()));
            Component denial = run(session);
            if (denial != null) {
                connection.disconnect(denial);
            } else {
                authenticated.add(id);
                connection.getAudience().closeDialog();
            }
        } catch (TimeoutException e) {
            connection.disconnect(messages.get("kick.timeout"));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database error while logging in " + name, e);
            connection.disconnect(messages.get("kick.unavailable"));
        } finally {
            inFlight.remove(key);
            waiter.cancel(id);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        authenticated.remove(event.getPlayer().getUniqueId());
    }

    /** Returns null when the player may enter, otherwise the disconnect message. */
    private @Nullable Component run(Session s) throws TimeoutException, SQLException {
        if (sessions.isRemembered(s.key, s.ip)) {
            store.recordLogin(s.name, clock.millis(), s.ip.getHostAddress());
            return null;
        }
        String serverHash = store.serverPasswordHash()
                .orElseThrow(() -> new SQLException("Server password is not set"));

        Component error = null;
        while (true) {
            Response r = prompt(s, dialogs.serverPassword(error));
            if (!r.is(DialogFactory.SUBMIT)) {
                return messages.get("kick.cancelled");
            }
            if (hasher.verify(r.text("password"), serverHash)) {
                break;
            }
            Component denial = recordFailure(s);
            if (denial != null) {
                return denial;
            }
            error = messages.get("error.wrong-server-password");
        }

        Optional<Account> account = store.find(s.name);
        Component denial = account.isEmpty() ? register(s, serverHash) : login(s, account.get(), serverHash);
        if (denial == null) {
            store.recordLogin(s.name, clock.millis(), s.ip.getHostAddress());
            sessions.remember(s.key, s.ip);
        }
        return denial;
    }

    private @Nullable Component register(Session s, String serverHash) throws TimeoutException, SQLException {
        if (!mail.available()) {
            return messages.get("kick.registration-unavailable");
        }
        Component error = null;
        while (true) {
            Response r = prompt(s, dialogs.register(error));
            if (!r.is(DialogFactory.SUBMIT)) {
                return messages.get("kick.cancelled");
            }
            String password = r.text("password");
            Optional<String> problem = rules.check(password, r.text("confirm"), pw -> hasher.verify(pw, serverHash));
            if (problem.isPresent()) {
                error = messages.get(problem.get(), rules.placeholders());
                continue;
            }
            Optional<EmailAddress> email = EmailAddress.parse(r.text("email"));
            if (email.isEmpty()) {
                error = messages.get("error.email-invalid");
                continue;
            }
            if (store.countByEmail(email.get().value()) >= config.maxAccountsPerEmail()) {
                error = messages.get("error.email-limit");
                continue;
            }
            String passwordHash = hasher.hash(password);
            Component sendError = sendCode(s, Purpose.REGISTER, email.get());
            if (sendError != null) {
                error = sendError;
                continue;
            }
            Step step = verifyCode(s, Purpose.REGISTER, email.get());
            if (step == Step.CANCEL) {
                return messages.get("kick.cancelled");
            }
            if (step == Step.BACK) {
                error = null;
                continue;
            }
            store.create(s.name, passwordHash, email.get().value(), clock.millis());
            return null;
        }
    }

    private @Nullable Component login(Session s, Account account, String serverHash) throws TimeoutException, SQLException {
        Component error = null;
        while (true) {
            Response r = prompt(s, dialogs.login(error));
            if (r.is(DialogFactory.FORGOT)) {
                StepResult result = forgot(s, account, serverHash);
                if (result.step() == Step.DONE) {
                    return null;
                }
                if (result.step() == Step.CANCEL) {
                    return messages.get("kick.cancelled");
                }
                error = result.message();
                continue;
            }
            if (!r.is(DialogFactory.SUBMIT)) {
                return messages.get("kick.cancelled");
            }
            if (hasher.verify(r.text("password"), account.passwordHash())) {
                return null;
            }
            Component denial = recordFailure(s);
            if (denial != null) {
                return denial;
            }
            error = messages.get("error.wrong-password");
        }
    }

    private StepResult forgot(Session s, Account account, String serverHash) throws TimeoutException, SQLException {
        EmailAddress email = new EmailAddress(account.email());
        Component menuError = null;
        while (true) {
            Response r = prompt(s, dialogs.forgotMenu(email.masked(), menuError));
            if (r.is(DialogFactory.BACK)) {
                return StepResult.back(null);
            }
            if (r.is(DialogFactory.SEND_CODE)) {
                if (!mail.available()) {
                    return StepResult.back(messages.get("error.reset-unavailable"));
                }
                Component sendError = sendCode(s, Purpose.RESET, email);
                if (sendError != null) {
                    menuError = sendError;
                    continue;
                }
            } else if (r.is(DialogFactory.HAVE_CODE)) {
                if (!codes.hasLiveCode(Purpose.RESET, s.key)) {
                    menuError = messages.get("error.no-live-code");
                    continue;
                }
            } else {
                return StepResult.cancel();
            }
            break;
        }

        Step step = verifyCode(s, Purpose.RESET, email);
        if (step == Step.BACK) {
            return StepResult.back(null);
        }
        if (step == Step.CANCEL) {
            return StepResult.cancel();
        }

        Component error = null;
        while (true) {
            Response r = prompt(s, dialogs.newPassword("dialog.reset-password.title", error, false));
            if (!r.is(DialogFactory.SUBMIT)) {
                return StepResult.cancel();
            }
            String password = r.text("password");
            Optional<String> problem = rules.check(password, r.text("confirm"), pw -> hasher.verify(pw, serverHash));
            if (problem.isPresent()) {
                error = messages.get(problem.get(), rules.placeholders());
                continue;
            }
            store.updatePassword(s.name, hasher.hash(password));
            sessions.forget(s.key);
            return StepResult.done();
        }
    }

    /** Code entry loop. DONE = correct code, BACK = player went back, CANCEL = closed or cancelled. */
    private Step verifyCode(Session s, Purpose purpose, EmailAddress email) throws TimeoutException {
        boolean reset = purpose == Purpose.RESET;
        Component error = null;
        while (true) {
            Response r = promptCode(s, dialogs.code(reset, email.masked(), error));
            if (r.is(DialogFactory.RESEND)) {
                Component sendError = sendCode(s, purpose, email);
                error = sendError != null ? sendError : messages.get("info.code-resent");
                continue;
            }
            if (r.is(DialogFactory.BACK)) {
                return Step.BACK;
            }
            if (!r.is(DialogFactory.SUBMIT)) {
                return Step.CANCEL;
            }
            switch (codes.verify(purpose, s.key, r.text("code").trim())) {
                case OK -> {
                    return Step.DONE;
                }
                case WRONG -> error = messages.get("error.code-wrong");
                case BURNED, NONE -> error = messages.get("error.code-expired");
            }
        }
    }

    /** Issues a code and emails it. Returns null on success, otherwise the error to show. */
    private @Nullable Component sendCode(Session s, Purpose purpose, EmailAddress email) {
        if (purpose == Purpose.REGISTER && s.registerSends >= MAX_REGISTER_SENDS_PER_CONNECTION) {
            return messages.get("error.too-many-codes");
        }
        String ip = s.ip.getHostAddress();
        CodeService.Issue issue = codes.issue(purpose, s.key, ip);
        if (issue instanceof CodeService.CoolingDown wait) {
            long seconds = Math.max(1, (wait.remaining().toMillis() + 999) / 1000);
            return messages.get("error.code-cooldown", Placeholder.unparsed("seconds", Long.toString(seconds)));
        }
        String code = ((CodeService.Issued) issue).code();
        Map<String, String> vars = Map.of(
                "name", s.name,
                "code", code,
                "minutes", Long.toString(config.codeValidity().toMinutes()));
        boolean register = purpose == Purpose.REGISTER;
        String subject = messages.plain(register ? "email.register.subject" : "email.reset.subject", vars);
        String body = messages.plain(register ? "email.register.body" : "email.reset.body", vars);
        try {
            mail.send(email.value(), subject, body).get(MAIL_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            logger.log(Level.WARNING, "Could not send " + purpose + " email for " + s.name, e);
            codes.cancelIssue(purpose, s.key, ip);
            return messages.get("error.email-send-failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            codes.cancelIssue(purpose, s.key, ip);
            return messages.get("error.email-send-failed");
        }
        if (register) {
            s.registerSends++;
        }
        return null;
    }

    /** Counts one wrong password. Returns the disconnect message once a limit is reached. */
    private @Nullable Component recordFailure(Session s) {
        limiter.recordFailure(s.ip);
        s.failures++;
        Optional<Duration> ban = limiter.banRemaining(s.ip);
        if (ban.isPresent()) {
            long minutes = Math.max(1, (ban.get().toSeconds() + 59) / 60);
            return messages.get("kick.ip-banned", Placeholder.unparsed("minutes", Long.toString(minutes)));
        }
        if (s.failures >= config.maxAttemptsPerConnection()) {
            return messages.get("kick.too-many-attempts");
        }
        return null;
    }

    private Response prompt(Session s, Dialog dialog) throws TimeoutException {
        Duration left = Duration.between(clock.instant(), s.deadline);
        if (left.isNegative() || left.isZero()) {
            throw new TimeoutException();
        }
        return waiter.await(s.id, () -> s.connection.getAudience().showDialog(dialog), left);
    }

    /** Code dialogs get the longer email wait; afterwards the player gets a fresh login timeout. */
    private Response promptCode(Session s, Dialog dialog) throws TimeoutException {
        Response response = waiter.await(s.id, () -> s.connection.getAudience().showDialog(dialog), config.codeWaitTimeout());
        s.deadline = clock.instant().plus(config.loginTimeout());
        return response;
    }
}
```

Message keys are always written as full string literals (never built from pieces) so `MessagesTest.everyKeyUsedInCodeExists` can check them.

- [ ] **Step 2: Compile and run all tests**

Run: `.\gradlew.bat build`
Expected: `BUILD SUCCESSFUL` (including `MessagesTest.everyKeyUsedInCodeExists`).

- [ ] **Step 3: Commit**

```powershell
git add src
git commit -m "feat: add configuration-phase login flow with email reset`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: PreLoginGuard

**Files:**
- Create: `src/main/java/dev/tongt/mcserverauth/flow/PreLoginGuard.java`

- [ ] **Step 1: Write `PreLoginGuard`**

```java
package dev.tongt.mcserverauth.flow;

import dev.tongt.mcserverauth.config.Messages;
import dev.tongt.mcserverauth.security.AttemptLimiter;
import dev.tongt.mcserverauth.storage.Account;
import dev.tongt.mcserverauth.storage.AccountStore;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Checks that run before the configuration phase: IP ban, duplicate name, wrong letter case. */
public final class PreLoginGuard implements Listener {
    private final Messages messages;
    private final AttemptLimiter limiter;
    private final AccountStore store;
    private final LoginFlow flow;
    private final Logger logger;

    public PreLoginGuard(Messages messages, AttemptLimiter limiter, AccountStore store, LoginFlow flow, Logger logger) {
        this.messages = messages;
        this.limiter = limiter;
        this.store = store;
        this.flow = flow;
        this.logger = logger;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        String name = event.getName();

        Optional<Duration> ban = limiter.banRemaining(event.getAddress());
        if (ban.isPresent()) {
            long minutes = Math.max(1, (ban.get().toSeconds() + 59) / 60);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    messages.get("kick.ip-banned", Placeholder.unparsed("minutes", Long.toString(minutes))));
            return;
        }

        // In offline mode a second login with the same name would kick the real player. Refuse the newcomer.
        if (Bukkit.getPlayerExact(name) != null || flow.isNameBusy(name)) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get("kick.name-in-use"));
            return;
        }

        try {
            Optional<Account> account = store.find(name);
            if (account.isPresent() && !account.get().displayName().equals(name)) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        messages.get("kick.wrong-case", Placeholder.unparsed("name", account.get().displayName())));
            }
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database error in pre-login check for " + name, e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.get("kick.unavailable"));
        }
    }
}
```

- [ ] **Step 2: Compile and run all tests**

Run: `.\gradlew.bat build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```powershell
git add src
git commit -m "feat: add pre-login guard for bans, duplicate names and wrong case`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 15: /changepassword

**Files:**
- Create: `src/main/java/dev/tongt/mcserverauth/command/ChangePasswordCommand.java`

- [ ] **Step 1: Write `ChangePasswordCommand`**

```java
package dev.tongt.mcserverauth.command;

import dev.tongt.mcserverauth.config.AuthConfig;
import dev.tongt.mcserverauth.config.Messages;
import dev.tongt.mcserverauth.flow.DialogFactory;
import dev.tongt.mcserverauth.flow.DialogWaiter;
import dev.tongt.mcserverauth.flow.DialogWaiter.Response;
import dev.tongt.mcserverauth.security.PasswordHasher;
import dev.tongt.mcserverauth.security.PasswordRules;
import dev.tongt.mcserverauth.security.SessionCache;
import dev.tongt.mcserverauth.storage.Account;
import dev.tongt.mcserverauth.storage.AccountStore;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.dialog.Dialog;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ChangePasswordCommand implements BasicCommand {
    private final AuthConfig config;
    private final Messages messages;
    private final DialogFactory dialogs;
    private final DialogWaiter waiter;
    private final AccountStore store;
    private final PasswordHasher hasher;
    private final PasswordRules rules;
    private final SessionCache sessions;
    private final Logger logger;

    public ChangePasswordCommand(AuthConfig config, Messages messages, DialogFactory dialogs, DialogWaiter waiter,
                                 AccountStore store, PasswordHasher hasher, PasswordRules rules,
                                 SessionCache sessions, Logger logger) {
        this.config = config;
        this.messages = messages;
        this.dialogs = dialogs;
        this.waiter = waiter;
        this.store = store;
        this.hasher = hasher;
        this.rules = rules;
        this.sessions = sessions;
        this.logger = logger;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage(messages.get("command.players-only"));
            return;
        }
        Thread.ofVirtual().name("McServerAuth-changepassword").start(() -> run(player));
    }

    @Override
    public String permission() {
        return "mcserverauth.changepassword";
    }

    private void run(Player player) {
        Component error = null;
        int failures = 0;
        try {
            while (true) {
                Dialog dialog = dialogs.changePassword(error);
                Response r = waiter.await(player.getUniqueId(), () -> player.showDialog(dialog), config.loginTimeout());
                if (!r.is(DialogFactory.SUBMIT)) {
                    player.closeDialog();
                    return;
                }
                Optional<Account> account = store.find(player.getName());
                if (account.isEmpty()) {
                    player.closeDialog();
                    player.sendMessage(messages.get("command.error"));
                    return;
                }
                if (!hasher.verify(r.text("current"), account.get().passwordHash())) {
                    failures++;
                    if (failures >= config.maxAttemptsPerConnection()) {
                        player.closeDialog();
                        player.sendMessage(messages.get("error.wrong-password"));
                        return;
                    }
                    error = messages.get("error.wrong-password");
                    continue;
                }
                String serverHash = store.serverPasswordHash().orElse(null);
                String password = r.text("password");
                Optional<String> problem = rules.check(password, r.text("confirm"),
                        pw -> serverHash != null && hasher.verify(pw, serverHash));
                if (problem.isPresent()) {
                    error = messages.get(problem.get(), rules.placeholders());
                    continue;
                }
                store.updatePassword(player.getName(), hasher.hash(password));
                sessions.forget(AccountStore.key(player.getName()));
                player.closeDialog();
                player.sendMessage(messages.get("command.password-changed"));
                return;
            }
        } catch (TimeoutException e) {
            player.closeDialog();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database error in /changepassword for " + player.getName(), e);
            player.closeDialog();
            player.sendMessage(messages.get("command.error"));
        }
    }
}
```

- [ ] **Step 2: Compile and run all tests**

Run: `.\gradlew.bat build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```powershell
git add src
git commit -m "feat: add /changepassword dialog command`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 16: /authadmin

**Files:**
- Create: `src/main/java/dev/tongt/mcserverauth/command/AuthAdminCommand.java`

- [ ] **Step 1: Write `AuthAdminCommand`**

```java
package dev.tongt.mcserverauth.command;

import dev.tongt.mcserverauth.config.AuthConfig;
import dev.tongt.mcserverauth.config.Messages;
import dev.tongt.mcserverauth.email.CodeService;
import dev.tongt.mcserverauth.flow.DialogFactory;
import dev.tongt.mcserverauth.flow.DialogWaiter;
import dev.tongt.mcserverauth.flow.DialogWaiter.Response;
import dev.tongt.mcserverauth.security.PasswordHasher;
import dev.tongt.mcserverauth.security.PasswordRules;
import dev.tongt.mcserverauth.security.SessionCache;
import dev.tongt.mcserverauth.storage.Account;
import dev.tongt.mcserverauth.storage.AccountStore;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.dialog.Dialog;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class AuthAdminCommand implements BasicCommand {
    private static final List<String> SUBCOMMANDS = List.of("reset", "setserverpassword", "info");

    private final Plugin plugin;
    private final AuthConfig config;
    private final Messages messages;
    private final DialogFactory dialogs;
    private final DialogWaiter waiter;
    private final AccountStore store;
    private final PasswordHasher hasher;
    private final PasswordRules rules;
    private final SessionCache sessions;
    private final CodeService codes;
    private final Logger logger;

    public AuthAdminCommand(Plugin plugin, AuthConfig config, Messages messages, DialogFactory dialogs,
                            DialogWaiter waiter, AccountStore store, PasswordHasher hasher, PasswordRules rules,
                            SessionCache sessions, CodeService codes) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.dialogs = dialogs;
        this.waiter = waiter;
        this.store = store;
        this.hasher = hasher;
        this.rules = rules;
        this.sessions = sessions;
        this.codes = codes;
        this.logger = plugin.getLogger();
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reset" -> {
                if (args.length != 2) {
                    sender.sendMessage(messages.get("command.usage"));
                    return;
                }
                async(() -> reset(sender, args[1]));
            }
            case "info" -> {
                if (args.length != 2) {
                    sender.sendMessage(messages.get("command.usage"));
                    return;
                }
                async(() -> info(sender, args[1]));
            }
            case "setserverpassword" -> {
                if (args.length >= 2) {
                    String password = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                    sender.sendMessage(messages.get("command.console-password-logged"));
                    async(() -> setServerPassword(sender, password, password));
                } else if (sender instanceof Player player) {
                    Thread.ofVirtual().name("McServerAuth-setserverpassword").start(() -> serverPasswordDialog(player));
                } else {
                    sender.sendMessage(messages.get("command.usage"));
                }
            }
            default -> sender.sendMessage(messages.get("command.usage"));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("info"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "mcserverauth.admin";
    }

    private void async(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    private void reset(CommandSender sender, String name) {
        TagResolver namePlaceholder = Placeholder.unparsed("name", name);
        try {
            boolean deleted = store.delete(name);
            String key = AccountStore.key(name);
            codes.invalidate(key);
            sessions.forget(key);
            if (!deleted) {
                sender.sendMessage(messages.get("command.no-such-account", namePlaceholder));
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player online = Bukkit.getPlayerExact(name);
                if (online != null) {
                    online.kick(messages.get("kick.account-reset"));
                }
            });
            sender.sendMessage(messages.get("command.reset-done", namePlaceholder));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database error in /authadmin reset " + name, e);
            sender.sendMessage(messages.get("command.error"));
        }
    }

    private void info(CommandSender sender, String name) {
        try {
            Optional<Account> found = store.find(name);
            if (found.isEmpty()) {
                sender.sendMessage(messages.get("command.no-such-account", Placeholder.unparsed("name", name)));
                return;
            }
            Account a = found.get();
            sender.sendMessage(messages.get("command.info",
                    Placeholder.unparsed("name", a.displayName()),
                    Placeholder.unparsed("email", a.email()),
                    Placeholder.unparsed("created", Instant.ofEpochMilli(a.createdAt()).toString()),
                    Placeholder.unparsed("last_login", a.lastLoginAt() == null ? "-" : Instant.ofEpochMilli(a.lastLoginAt()).toString()),
                    Placeholder.unparsed("last_ip", a.lastIp() == null ? "-" : a.lastIp())));
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database error in /authadmin info " + name, e);
            sender.sendMessage(messages.get("command.error"));
        }
    }

    /** Returns the problem to show, or null when the password was stored. */
    private Component setServerPassword(CommandSender sender, String password, String confirm) {
        Optional<String> problem = rules.check(password, confirm, pw -> false);
        if (problem.isPresent()) {
            Component error = messages.get(problem.get(), rules.placeholders());
            sender.sendMessage(error);
            return error;
        }
        try {
            store.setServerPasswordHash(hasher.hash(password));
            sender.sendMessage(messages.get("command.server-password-set"));
            return null;
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Database error in /authadmin setserverpassword", e);
            Component error = messages.get("command.error");
            sender.sendMessage(error);
            return error;
        }
    }

    private void serverPasswordDialog(Player player) {
        Component error = null;
        try {
            while (true) {
                Dialog dialog = dialogs.newPassword("dialog.set-server-password.title", error, true);
                Response r = waiter.await(player.getUniqueId(), () -> player.showDialog(dialog), config.loginTimeout());
                if (!r.is(DialogFactory.SUBMIT)) {
                    player.closeDialog();
                    return;
                }
                error = setServerPassword(player, r.text("password"), r.text("confirm"));
                if (error == null) {
                    player.closeDialog();
                    return;
                }
            }
        } catch (TimeoutException e) {
            player.closeDialog();
        }
    }
}
```

- [ ] **Step 2: Compile and run all tests**

Run: `.\gradlew.bat build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```powershell
git add src
git commit -m "feat: add /authadmin reset, setserverpassword and info`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 17: Plugin wiring, bootstrap and fail-closed lockdown

**Files:**
- Modify: `src/main/java/dev/tongt/mcserverauth/McServerAuthPlugin.java` (replace placeholder)

Fail closed: when config or database cannot load, the plugin stays enabled with only a listener that refuses every login (disabling would drop that listener and let everyone in).

- [ ] **Step 1: Write `McServerAuthPlugin`**

```java
package dev.tongt.mcserverauth;

import dev.tongt.mcserverauth.command.AuthAdminCommand;
import dev.tongt.mcserverauth.command.ChangePasswordCommand;
import dev.tongt.mcserverauth.config.AuthConfig;
import dev.tongt.mcserverauth.config.Messages;
import dev.tongt.mcserverauth.email.CodeService;
import dev.tongt.mcserverauth.email.MailSender;
import dev.tongt.mcserverauth.email.SmtpMailSender;
import dev.tongt.mcserverauth.flow.DialogFactory;
import dev.tongt.mcserverauth.flow.DialogWaiter;
import dev.tongt.mcserverauth.flow.LoginFlow;
import dev.tongt.mcserverauth.flow.PreLoginGuard;
import dev.tongt.mcserverauth.security.AttemptLimiter;
import dev.tongt.mcserverauth.security.PasswordHasher;
import dev.tongt.mcserverauth.security.PasswordRules;
import dev.tongt.mcserverauth.security.SessionCache;
import dev.tongt.mcserverauth.storage.AccountStore;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;

public final class McServerAuthPlugin extends JavaPlugin {
    private static final int BCRYPT_COST = 12;
    private static final String GENERATED_PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private AccountStore store;
    private MailSender mail;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }
        Messages messages = new Messages(YamlConfiguration.loadConfiguration(messagesFile));

        AuthConfig config;
        try {
            config = AuthConfig.from(getConfig());
        } catch (IllegalArgumentException e) {
            lockDown(messages, "Invalid config.yml: " + e.getMessage(), null);
            return;
        }

        PasswordHasher hasher = new PasswordHasher(BCRYPT_COST);
        SecureRandom random = new SecureRandom();
        try {
            store = AccountStore.open(new File(getDataFolder(), "accounts.db").toPath());
            if (store.serverPasswordHash().isEmpty()) {
                String generated = randomPassword(random);
                store.setServerPasswordHash(hasher.hash(generated));
                getLogger().warning("No server password was set. Generated one: " + generated);
                getLogger().warning("Change it with /authadmin setserverpassword. This is the only time it is shown.");
            }
        } catch (SQLException e) {
            lockDown(messages, "Could not open the account database", e);
            return;
        }

        Clock clock = Clock.systemUTC();
        PasswordRules rules = new PasswordRules(config.passwordMinLength(), config.passwordMaxLength());
        AttemptLimiter limiter = new AttemptLimiter(clock, config.ipBanFailures(), config.ipBanWindow(), config.ipBanDuration());
        SessionCache sessions = new SessionCache(clock, config.sessionRememberEnabled(), config.sessionRememberDuration());
        CodeService codes = new CodeService(clock, random, config.codeValidity(), config.codeMaxAttempts());
        mail = new SmtpMailSender(config.smtp());
        if (!mail.available()) {
            getLogger().warning("SMTP is not configured (email.smtp in config.yml). New registrations and password resets are refused.");
        }

        DialogWaiter waiter = new DialogWaiter();
        DialogFactory dialogs = new DialogFactory(messages, config.passwordMaxLength());
        LoginFlow flow = new LoginFlow(config, messages, dialogs, waiter, store, hasher, rules, limiter,
                sessions, codes, mail, clock, getLogger());

        var plugins = getServer().getPluginManager();
        plugins.registerEvents(waiter, this);
        plugins.registerEvents(flow, this);
        plugins.registerEvents(new PreLoginGuard(messages, limiter, store, flow, getLogger()), this);

        registerCommand("changepassword", "Change your personal password",
                new ChangePasswordCommand(config, messages, dialogs, waiter, store, hasher, rules, sessions, getLogger()));
        registerCommand("authadmin", "Manage McServerAuth accounts",
                new AuthAdminCommand(this, config, messages, dialogs, waiter, store, hasher, rules, sessions, codes));
    }

    @Override
    public void onDisable() {
        if (mail != null) {
            mail.close();
        }
        if (store != null) {
            try {
                store.close();
            } catch (SQLException e) {
                getLogger().warning("Could not close the account database: " + e.getMessage());
            }
        }
    }

    private void lockDown(Messages messages, String reason, Exception cause) {
        if (cause == null) {
            getLogger().severe(reason + " - refusing all logins until this is fixed.");
        } else {
            getLogger().log(java.util.logging.Level.SEVERE, reason + " - refusing all logins until this is fixed.", cause);
        }
        getServer().getPluginManager().registerEvents(new Lockdown(messages.get("kick.lockdown")), this);
    }

    private static String randomPassword(SecureRandom random) {
        StringBuilder password = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            password.append(GENERATED_PASSWORD_ALPHABET.charAt(random.nextInt(GENERATED_PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }

    /** Refuses every login. Used when the plugin cannot start safely. */
    public static final class Lockdown implements Listener {
        private final Component message;

        Lockdown(Component message) {
            this.message = message;
        }

        @EventHandler
        public void onPreLogin(AsyncPlayerPreLoginEvent event) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message);
        }
    }
}
```

- [ ] **Step 2: Build and run all tests**

Run: `.\gradlew.bat build`
Expected: `BUILD SUCCESSFUL`, all unit tests pass, `build\libs\mc-server-auth-0.1.0.jar` exists.

- [ ] **Step 3: Commit**

```powershell
git add src
git commit -m "feat: wire plugin, bootstrap server password, fail closed on startup errors`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 18: Local test server, Mailpit and README

**Files:**
- Create: `dev/test-config.yml`, `scripts/start-mailpit.ps1`, `scripts/start-test-server.ps1`, `README.md`

- [ ] **Step 1: Write `dev/test-config.yml`** (bundled `config.yml` with SMTP pointed at Mailpit)

```yaml
# Local test only. Points SMTP at Mailpit (scripts/start-mailpit.ps1); nothing leaves this machine.
max-attempts-per-connection: 3
ip-ban:
  failures: 5
  window-minutes: 10
  ban-minutes: 10
login-timeout-seconds: 60
code-wait-timeout-minutes: 10
password:
  min-length: 6
  max-length: 64
session-remember:
  enabled: false
  minutes: 5
email:
  max-accounts-per-email: 3
  code-valid-minutes: 15
  code-max-attempts: 5
  smtp:
    host: "127.0.0.1"
    port: 41853
    security: none
    username: ""
    password: ""
    from: "Test Server <noreply@mc-server-auth.test>"
```

- [ ] **Step 2: Write `scripts/start-mailpit.ps1`**

```powershell
# Starts Mailpit, a fake mail server that catches every email locally.
# SMTP: 127.0.0.1:41853   Web inbox: http://127.0.0.1:41854
$ErrorActionPreference = 'Stop'
$name = 'mc-auth-mailpit'
$existing = docker ps -a --filter "name=^$name$" --format '{{.Names}}'
if ($existing -eq $name) {
    docker start $name | Out-Null
} else {
    docker run -d --name $name -p 127.0.0.1:41853:1025 -p 127.0.0.1:41854:8025 axllent/mailpit:v1.31.6 | Out-Null
}
Write-Host 'Mailpit running. Inbox: http://127.0.0.1:41854'
```

- [ ] **Step 3: Write `scripts/start-test-server.ps1`**

```powershell
# Downloads Paper 26.3 (once), installs the freshly built plugin and starts a local offline-mode server on port 34127.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$run = Join-Path $root 'run'
$pluginData = Join-Path $run 'plugins\McServerAuth'
New-Item -ItemType Directory -Force $pluginData | Out-Null

$headers = @{ 'User-Agent' = 'mc-server-auth-dev/0.1 (local test server)' }
$jar = Join-Path $run 'paper.jar'
if (-not (Test-Path $jar)) {
    $build = Invoke-RestMethod 'https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/latest' -Headers $headers
    $download = $build.downloads.'server:default'
    Invoke-WebRequest $download.url -OutFile $jar -Headers $headers
    $actual = (Get-FileHash $jar -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $download.checksums.sha256) {
        Remove-Item $jar
        throw 'Paper jar checksum mismatch; download removed.'
    }
    Write-Host "Downloaded Paper 26.3 build $($build.id)"
}

$eula = Join-Path $run 'eula.txt'
if (-not ((Test-Path $eula) -and (Select-String -Path $eula -Pattern '^eula=true' -Quiet))) {
    Write-Host 'Read the Minecraft EULA at https://aka.ms/MinecraftEULA.'
    Write-Host "If you agree, create $eula containing the line: eula=true"
    exit 1
}

$props = Join-Path $run 'server.properties'
if (-not (Test-Path $props)) {
    Set-Content $props @('online-mode=false', 'server-port=34127', 'server-ip=127.0.0.1', 'motd=mc-server-auth test')
}

$config = Join-Path $pluginData 'config.yml'
if (-not (Test-Path $config)) {
    Copy-Item (Join-Path $root 'dev\test-config.yml') $config
}

$built = Get-ChildItem (Join-Path $root 'build\libs') -Filter 'mc-server-auth-*.jar' | Select-Object -First 1
if (-not $built) { throw 'Plugin jar not found. Run .\gradlew.bat build first.' }
Copy-Item $built.FullName (Join-Path $run 'plugins\McServerAuth.jar') -Force

Push-Location $run
try {
    java -Xms1G -Xmx2G -jar paper.jar --nogui
} finally {
    Pop-Location
}
```

- [ ] **Step 4: Write `README.md`**

````markdown
# mc-server-auth

Paper 26.3 plugin for offline-mode servers (TLauncher and similar). Before a player enters the world they must:

1. enter the shared **server password**, then
2. register (personal password + verified email) on their first visit, or enter their **personal password** after that.

A player who forgets their password can reset it with a 6-digit code sent to their email.

Design: `docs/superpowers/specs/2026-10-17-mc-server-auth-design.md`

## Ports used in development

| Port  | What                                  |
|-------|---------------------------------------|
| 34127 | Local Paper test server (127.0.0.1)   |
| 41853 | Mailpit SMTP (fake mail server)       |
| 41854 | Mailpit web inbox                     |

## Build

```powershell
.\gradlew.bat build
```
The plugin jar is `build\libs\mc-server-auth-0.1.0.jar`.

## Run the local test server

```powershell
.\scripts\start-mailpit.ps1
.\gradlew.bat build
.\scripts\start-test-server.ps1
```
The first run downloads Paper and stops until you accept the Minecraft EULA in `run\eula.txt`.
Connect with an offline launcher to `127.0.0.1:34127`. Emails appear at http://127.0.0.1:41854.
The generated server password is printed once in the server console.

## Install on a real server

1. Put the jar in `plugins/`, set `online-mode=false`, start once.
2. Copy the generated server password from the console, or set your own with `/authadmin setserverpassword`.
3. Fill `email.smtp` in `plugins/McServerAuth/config.yml` (for Gmail: `smtp.gmail.com`, port 587, `starttls`, an App Password). Without SMTP, new players cannot register.
4. Restart.

## Commands

| Command | Permission | Default |
|---|---|---|
| `/changepassword` | `mcserverauth.changepassword` | everyone |
| `/authadmin reset <name>` | `mcserverauth.admin` | op |
| `/authadmin setserverpassword [password]` | `mcserverauth.admin` | op |
| `/authadmin info <name>` | `mcserverauth.admin` | op |

## Tell your players

- Offline mode does not encrypt traffic. **Do not reuse a password from any other account.**
- Password fields show what you type; check nobody is watching your screen.
````

- [ ] **Step 5: Commit**

```powershell
git add dev scripts README.md
git commit -m "chore: add local test server, Mailpit scripts and README`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 19: Manual verification on the test server

Needs the owner: they accept the EULA and play with an offline launcher. The agent watches the server console and Mailpit (http://127.0.0.1:41854, or `Invoke-RestMethod http://127.0.0.1:41854/api/v1/messages`).

- [ ] **Step 1: Start Mailpit and the server**

```powershell
.\scripts\start-mailpit.ps1
.\gradlew.bat build
.\scripts\start-test-server.ps1
```
Ask the owner to accept the EULA when the script stops (the agent must not write `eula=true` itself). Run the server in a terminal tab the owner can see. Expected console lines: `[McServerAuth] Enabling McServerAuth v0.1.0`, the generated server password warning, no `SEVERE`.

- [ ] **Step 2: Walk through the spec's manual checklist with the owner**

Run each case from the spec's "Testing → Manual" list (1–16). For each one, record pass/fail and any console error. For case 15, set `session-remember.enabled: true` in `run\plugins\McServerAuth\config.yml` and restart. For case 16, blank `email.smtp.host` and restart. For the duplicate-name case (9), use a second launcher instance with the same name.

- [ ] **Step 3: Fix failures**

For each failure, use superpowers:systematic-debugging, fix, rebuild, rerun the failed case and the cases near it. Commit each fix separately with a `fix:` message.

- [ ] **Step 4: Record results**

Append a "Manual test results (2026-10-17)" section to `README.md` with the 16 cases and pass/fail, then commit:

```powershell
git add README.md
git commit -m "docs: record manual test results`n`nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
