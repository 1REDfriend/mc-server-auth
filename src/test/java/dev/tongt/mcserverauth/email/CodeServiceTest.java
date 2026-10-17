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
        return issued(purpose, subject).code();
    }

    private Issued issued(Purpose purpose, String subject) {
        return assertInstanceOf(Issued.class, codes.issue(purpose, subject, IP));
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
        Issued issued = issued(Purpose.RESET, "steve");
        codes.cancelIssue(Purpose.RESET, "steve", IP, issued);
        assertFalse(codes.hasLiveCode(Purpose.RESET, "steve"));
        issue(Purpose.RESET, "steve");
    }

    @Test
    void cancelIssueFreesTheIpSlot() {
        issue(Purpose.RESET, "a");
        issue(Purpose.RESET, "b");
        Issued c = issued(Purpose.RESET, "c");
        codes.cancelIssue(Purpose.RESET, "c", IP, c);
        issue(Purpose.RESET, "d");
    }

    @Test
    void failedResendRestoresThePreviousCode() {
        String a = issue(Purpose.REGISTER, "steve");
        clock.advance(Duration.ofSeconds(60));
        Issued b = issued(Purpose.REGISTER, "steve");
        codes.cancelIssue(Purpose.REGISTER, "steve", IP, b);
        assertEquals(Check.OK, codes.verify(Purpose.REGISTER, "steve", a));
    }

    @Test
    void staleOrRepeatedCancelIsANoOp() {
        Issued a = issued(Purpose.REGISTER, "steve");
        clock.advance(Duration.ofSeconds(60));
        Issued b = issued(Purpose.REGISTER, "steve");
        codes.cancelIssue(Purpose.REGISTER, "steve", IP, a);
        assertEquals(Check.OK, codes.verify(Purpose.REGISTER, "steve", b.code()));

        Issued c = issued(Purpose.RESET, "alex");
        codes.cancelIssue(Purpose.RESET, "alex", IP, c);
        codes.cancelIssue(Purpose.RESET, "alex", IP, c);
        assertFalse(codes.hasLiveCode(Purpose.RESET, "alex"));
    }

    @Test
    void nullCodeDoesNotThrow() {
        issue(Purpose.RESET, "steve");
        assertEquals(Check.WRONG, codes.verify(Purpose.RESET, "steve", null));
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
