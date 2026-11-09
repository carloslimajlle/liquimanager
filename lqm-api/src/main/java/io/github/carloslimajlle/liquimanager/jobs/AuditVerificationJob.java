package io.github.carloslimajlle.liquimanager.jobs;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.carloslimajlle.liquimanager.audit.AuditService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Re-verifies the whole audit chain once a day (RNF-09). A broken chain means someone changed the log, so
 * the run is recorded as failed and logged at ERROR; the notification channel (E19) will carry it further.
 */
@Component
class AuditVerificationJob {

    static final String NAME = "audit-verify";

    private final AuditService audit;
    private final JobHistory history;
    private final io.github.carloslimajlle.liquimanager.notifications.NotificationService notifications;

    AuditVerificationJob(AuditService audit, JobHistory history, io.github.carloslimajlle.liquimanager.notifications.NotificationService notifications) {
        this.audit = audit;
        this.history = history;
        this.notifications = notifications;
    }

    @Scheduled(cron = "${lqm.jobs.audit-verify-cron:0 0 3 * * *}")
    @SchedulerLock(name = NAME, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void verify() {
        runNow();
    }

    /** The body, callable without waiting for the clock. */
    JobHistory.Outcome runNow() {
        return history.run(NAME, () -> {
            AuditService.Verification result = audit.verify();
            if (result.intact()) {
                return new JobHistory.Outcome(true, result.checked() + " events verified");
            }
            // A broken chain means somebody changed the log: the one alert that must not stay in a log file.
            notifications.notifyEveryWorkspace("SYSTEM_ALERT", "Trilha de auditoria adulterada",
                    "A cadeia de hashes da auditoria nao confere a partir do evento " + result.brokenAt()
                            + ". Alguem alterou ou removeu um registro.", "audit-broken:" + result.brokenAt());
            return new JobHistory.Outcome(false, "audit chain broken at seq " + result.brokenAt()
                    + " after " + result.checked() + " verified events");
        });
    }
}
