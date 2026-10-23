package dev.tongt.mcserverauth.audit;

import org.jspecify.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Appends every dialog input the plugin receives, exactly as the client sent it, to one file per UTC day, so an
 * input that breaks the server can be found afterwards. Lines are written and flushed before the input is
 * handled. Text that came from the player is base64-encoded: that keeps one record per line and stops a casual
 * glance from reading it, but it is not encryption, and the files hold real passwords.
 *
 * <p>Line format: {@code <instant> phase=<phase> ip=<ip> name=<b64> id=<b64> payload=<b64 or ->}
 */
public final class SecretInputLog implements AutoCloseable {
    private static final Pattern FILE_NAME = Pattern.compile("secret-input-(\\d{4}-\\d{2}-\\d{2})\\.log");

    private final Path dir;
    private final Clock clock;
    private final int keepDays;
    private @Nullable BufferedWriter writer;
    private @Nullable LocalDate writerDate;

    public SecretInputLog(Path dir, Clock clock, int keepDays) throws IOException {
        this.dir = dir;
        this.clock = clock;
        this.keepDays = keepDays;
        Files.createDirectories(dir);
        restrict(dir, "rwx------");
        deleteOld(today());
    }

    /** Records one input and flushes it to disk before returning. */
    public synchronized void record(String phase, String ip, String name, String id, @Nullable String payload)
            throws IOException {
        Instant now = clock.instant();
        LocalDate date = LocalDate.ofInstant(now, ZoneOffset.UTC);
        BufferedWriter out = writerFor(date);
        out.write(now + " phase=" + phase + " ip=" + ip + " name=" + b64(name) + " id=" + b64(id)
                + " payload=" + (payload == null ? "-" : b64(payload)));
        out.newLine();
        out.flush();
    }

    private BufferedWriter writerFor(LocalDate date) throws IOException {
        if (writer != null && date.equals(writerDate)) {
            return writer;
        }
        closeWriter();
        deleteOld(date);
        Path file = dir.resolve("secret-input-" + date + ".log");
        boolean created = !Files.exists(file);
        writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (created) {
            restrict(file, "rw-------");
        }
        writerDate = date;
        return writer;
    }

    /** Keeps today's file and the ones from the {@code keepDays - 1} days before it. */
    private void deleteOld(LocalDate today) throws IOException {
        LocalDate oldestKept = today.minusDays(keepDays - 1L);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                Matcher m = FILE_NAME.matcher(file.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                try {
                    if (LocalDate.parse(m.group(1)).isBefore(oldestKept)) {
                        Files.deleteIfExists(file);
                    }
                } catch (DateTimeParseException ignored) {
                    // Not one of ours.
                }
            }
        }
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Owner-only access where the file system supports POSIX permissions; Windows keeps the folder's ACL. */
    private static void restrict(Path path, String permissions) throws IOException {
        Set<String> views = path.getFileSystem().supportedFileAttributeViews();
        if (views.contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        }
    }

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // Nothing more can be done for a log file that will not close.
            }
            writer = null;
            writerDate = null;
        }
    }

    @Override
    public synchronized void close() {
        closeWriter();
    }
}
