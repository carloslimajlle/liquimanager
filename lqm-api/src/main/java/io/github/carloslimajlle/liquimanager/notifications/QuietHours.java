package io.github.carloslimajlle.liquimanager.notifications;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * When a delivery may leave, given a rule's quiet hours and digest window. Pure: the instant comes in as a parameter.
 *
 * <p>Quiet hours do not drop a message: it waits for the window to end and goes then. A window that crosses midnight (22:00 to 07:00) is
 * the ordinary case. A window that starts and ends at the same time is no window.
 */
final class QuietHours {

    private QuietHours() {
    }

    /** The instant a message queued at {@code now} may be sent: {@code now} itself outside the window, the window's end inside it. */
    static Instant releaseAt(Instant now, LocalTime start, LocalTime end, ZoneId zone) {
        if (start == null || end == null || start.equals(end)) {
            return now;
        }
        ZonedDateTime local = now.atZone(zone);
        LocalTime time = local.toLocalTime();
        boolean inside = start.isBefore(end) ? !time.isBefore(start) && time.isBefore(end) : !time.isBefore(start) || time.isBefore(end);
        if (!inside) {
            return now;
        }
        ZonedDateTime release = local.with(end);
        if (!release.isAfter(local)) {
            release = release.plusDays(1);
        }
        return release.toInstant();
    }

    /** The end of the digest window {@code now} falls in: windows are aligned to the epoch, so every replica agrees on them. */
    static Instant digestEnd(Instant now, int minutes) {
        long window = Duration.ofMinutes(minutes).toSeconds();
        long next = (now.getEpochSecond() / window + 1) * window;
        return Instant.ofEpochSecond(next);
    }
}
