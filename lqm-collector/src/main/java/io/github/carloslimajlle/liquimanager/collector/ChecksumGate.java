package io.github.carloslimajlle.liquimanager.collector;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.github.carloslimajlle.liquimanager.client.ChecksumChange;
import io.github.carloslimajlle.liquimanager.client.EffectivePolicies;
import io.github.carloslimajlle.liquimanager.client.LqmClient;
import io.github.carloslimajlle.liquimanager.policy.Finding;
import io.github.carloslimajlle.liquimanager.policy.Severity;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.change.CheckSum;
import liquibase.changelog.ChangeSetStatus;

/**
 * {@code ChecksumChanged}, a process check: a changeset that already ran in the environment and was edited since. Liquibase refuses the
 * whole deploy for it, so this says so earlier (in the merge request) and says what changed.
 *
 * <p>It is not in the engine because it needs what only the control plane knows: the checksum each database held when the changeset ran.
 * The collector sends the checksums it computes now and the server answers which of them differ.
 *
 * <p>Fail-open like everything a collector does (ADR-004): any trouble means no finding. A changeset that declares
 * {@code validCheckSum} for the applied checksum is not reported either: somebody already decided it is the same.
 */
public final class ChecksumGate {

    private static final Logger LOG = Logger.getLogger(ChecksumGate.class.getName());
    public static final String CODE = "ChecksumChanged";
    /** Where the lines are not different: a change in what the text does not show, so a person looks, but it need not block. */
    private static final int EQUIVALENT_SEVERITY = Severity.MAJOR;
    private static final int MAX_DIFF_LINES = 12;

    private ChecksumGate() {
    }

    public static List<Finding> evaluate(Liquibase liquibase, Contexts contexts, LabelExpression labels, LqmClient client,
                                         String project, String env, String commit, String changelog, EffectivePolicies policies) {
        try {
            List<ChecksumChange.Item> items = new ArrayList<>();
            java.util.Map<String, liquibase.changelog.ChangeSet> byKey = new java.util.HashMap<>();
            for (ChangeSetStatus status : liquibase.getChangeSetStatuses(contexts, labels, true)) {
                if (status.getCurrentCheckSum() == null) {
                    continue;
                }
                var cs = status.getChangeSet();
                items.add(new ChecksumChange.Item(cs.getId(), cs.getAuthor(), cs.getFilePath(), status.getCurrentCheckSum().toString()));
                byKey.put(key(cs.getId(), cs.getAuthor(), cs.getFilePath()), cs);
            }
            Optional<List<ChecksumChange>> answer = client.checksumChanges(project, env, commit, changelog, items);
            if (answer.isEmpty()) {
                return List.of();
            }
            int severity = severityOf(policies);
            List<Finding> out = new ArrayList<>();
            for (ChecksumChange change : answer.get()) {
                var cs = byKey.get(key(change.id(), change.author(), change.filePath()));
                if (!change.isIdentityChanged() && !change.isRemoved() && cs != null && isDeclaredValid(cs, change.appliedChecksum())) {
                    continue;
                }
                out.add(finding(change, severity));
            }
            return out;
        } catch (Exception | LinkageError e) {
            LOG.log(Level.WARNING, "lqm: could not check for changed checksums: " + e.getMessage());
            return List.of();
        }
    }

    private static boolean isDeclaredValid(liquibase.changelog.ChangeSet cs, String applied) {
        try {
            return applied != null && cs.isCheckSumValid(CheckSum.parse(applied));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The severity the package gives the check, else the catalogue's (blocker). */
    private static int severityOf(EffectivePolicies policies) {
        if (policies != null && policies.checks() != null) {
            for (EffectivePolicies.Check check : policies.checks()) {
                if (CODE.equals(check.code())) {
                    return check.severity();
                }
            }
        }
        return Severity.BLOCKER;
    }

    /** The finding, in pt-BR (a person reads it): what happened, the lines that changed, and what to do. */
    static Finding finding(ChecksumChange change, int configuredSeverity) {
        if (change.isRemoved()) {
            // A warning at most: Liquibase ignores it, and it can be on purpose (a squash, a baseline).
            return Finding.of(CODE, Math.min(configuredSeverity, EQUIVALENT_SEVERITY), change.id() + "::" + change.author() + "::" + change.filePath(),
                    "O changeset " + change.id() + " já foi aplicado em " + change.environment()
                            + (change.connection() == null ? "" : " (" + change.connection() + ")")
                            + " e não está mais no changelog. O Liquibase ignora, mas o histórico do banco deixa de bater com o repositório: "
                            + "um rollback ou um drift não consegue mais ser explicado por ele. Se foi de propósito (squash, baseline), ignore.");
        }
        if (change.isIdentityChanged()) {
            return Finding.of(CODE, configuredSeverity, change.id() + "::" + change.author() + "::" + change.filePath(),
                    "O changeset " + change.id() + " já foi aplicado em " + change.environment()
                            + (change.connection() == null ? "" : " (" + change.connection() + ")") + " com author "
                            + change.appliedAuthor() + " e arquivo " + change.appliedFilePath() + ", e agora aparece com author "
                            + change.author() + " e arquivo " + change.filePath() + ". Id, author e arquivo identificam o changeset: para o "
                            + "Liquibase este é um changeset novo, que roda de novo e costuma falhar no que já existe. "
                            + "Volte o author e o arquivo, ou reverta o changeset no ambiente e reaplique.");
        }
        StringBuilder text = new StringBuilder();
        text.append("O changeset ").append(change.id()).append(" já foi aplicado em ").append(change.environment())
                .append(change.connection() == null ? "" : " (" + change.connection() + ")")
                .append(" e foi alterado depois: o checksum mudou de ").append(change.appliedChecksum()).append(" para ")
                .append(change.currentChecksum()).append(". O Liquibase vai recusar o deploy.");
        int severity = configuredSeverity;
        if (change.isSourceKnown() && change.isEquivalent()) {
            severity = Math.min(configuredSeverity, EQUIVALENT_SEVERITY);
            text.append("\nNenhuma linha que conta mudou (comentários não entram no checksum): parece equivalente. ")
                    .append("Se for, declare validCheckSum com o checksum aplicado; senão, trate como mudança de estrutura.");
        } else {
            if (change.isSourceKnown()) {
                List<ChecksumChange.Line> lines = change.diff();
                lines.stream().limit(MAX_DIFF_LINES).forEach(line -> text.append('\n').append(line.op()).append(' ').append(line.text().strip()));
                if (lines.size() > MAX_DIFF_LINES) {
                    text.append("\n… e mais ").append(lines.size() - MAX_DIFF_LINES).append(" linhas");
                }
            }
            text.append("\nSe a mudança altera a estrutura, o banco não acompanha o arquivo: reverta o changeset no ambiente e reaplique, ")
                    .append("ou crie um changeset novo. validCheckSum só serve se o resultado for o mesmo.");
        }
        return Finding.of(CODE, severity, change.id() + "::" + change.author() + "::" + change.filePath(), text.toString());
    }

    private static String key(String id, String author, String filePath) {
        return id + "::" + author + "::" + filePath;
    }
}
