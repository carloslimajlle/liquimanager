package io.github.carloslimajlle.liquimanager.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** When a message may leave: not during the quiet hours, and grouped into windows every replica agrees on. */
class QuietHoursTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final LocalTime TEN_PM = LocalTime.of(22, 0);
    private static final LocalTime SEVEN_AM = LocalTime.of(7, 0);

    /** 2026-11-04 at the given local time in Sao Paulo. */
    private static Instant at(int hour, int minute) {
        return java.time.LocalDateTime.of(2026, 11, 4, hour, minute).atZone(SAO_PAULO).toInstant();
    }

    @Test
    @DisplayName("a window that crosses midnight holds a message from 22:00 to 07:00 and releases it at 07:00 of the right day")
    void overnight() {
        assertThat(QuietHours.releaseAt(at(21, 59), TEN_PM, SEVEN_AM, SAO_PAULO)).as("just before").isEqualTo(at(21, 59));
        assertThat(QuietHours.releaseAt(at(22, 0), TEN_PM, SEVEN_AM, SAO_PAULO)).as("at the start: tomorrow 07:00")
                .isEqualTo(Instant.parse("2026-11-05T10:00:00Z"));
        assertThat(QuietHours.releaseAt(at(3, 30), TEN_PM, SEVEN_AM, SAO_PAULO)).as("after midnight: today 07:00").isEqualTo(at(7, 0));
        assertThat(QuietHours.releaseAt(at(7, 0), TEN_PM, SEVEN_AM, SAO_PAULO)).as("at the end it is over").isEqualTo(at(7, 0));
    }

    @Test
    @DisplayName("a window inside one day holds only inside it; no window, or an empty one, holds nothing")
    void sameDayAndNone() {
        LocalTime noon = LocalTime.of(12, 0);
        LocalTime one = LocalTime.of(13, 0);
        assertThat(QuietHours.releaseAt(at(12, 30), noon, one, SAO_PAULO)).isEqualTo(at(13, 0));
        assertThat(QuietHours.releaseAt(at(13, 0), noon, one, SAO_PAULO)).isEqualTo(at(13, 0));
        assertThat(QuietHours.releaseAt(at(11, 59), noon, one, SAO_PAULO)).isEqualTo(at(11, 59));
        assertThat(QuietHours.releaseAt(at(12, 30), null, null, SAO_PAULO)).isEqualTo(at(12, 30));
        assertThat(QuietHours.releaseAt(at(12, 30), noon, noon, SAO_PAULO)).as("start = end is no window").isEqualTo(at(12, 30));
    }

    @Test
    @DisplayName("the zone decides what 22:00 is")
    void zone() {
        Instant utcMidnight = Instant.parse("2026-11-04T00:30:00Z");

        assertThat(QuietHours.releaseAt(utcMidnight, TEN_PM, SEVEN_AM, ZoneId.of("UTC"))).as("00:30 UTC is quiet there").isEqualTo(Instant.parse("2026-11-04T07:00:00Z"));
        assertThat(QuietHours.releaseAt(utcMidnight, TEN_PM, SEVEN_AM, SAO_PAULO)).as("21:30 in Sao Paulo is not").isEqualTo(utcMidnight);
    }

    @Test
    @DisplayName("digest windows are aligned to the epoch, so the end is the next multiple")
    void digest() {
        assertThat(QuietHours.digestEnd(Instant.parse("2026-11-04T10:07:00Z"), 15)).isEqualTo(Instant.parse("2026-11-04T10:15:00Z"));
        assertThat(QuietHours.digestEnd(Instant.parse("2026-11-04T10:15:00Z"), 15)).as("on the boundary: the next window").isEqualTo(Instant.parse("2026-11-04T10:30:00Z"));
        assertThat(QuietHours.digestEnd(Instant.parse("2026-11-04T10:07:00Z"), 60)).isEqualTo(Instant.parse("2026-11-04T11:00:00Z"));
    }
}
