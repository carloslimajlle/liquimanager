package io.github.carloslimajlle.liquimanager.policy;

import java.util.List;

import io.github.carloslimajlle.liquimanager.policy.builtin.ChangeChecks;
import io.github.carloslimajlle.liquimanager.policy.builtin.ChangesetCommentRequired;
import io.github.carloslimajlle.liquimanager.policy.builtin.ChangesetIdPattern;
import io.github.carloslimajlle.liquimanager.policy.builtin.ContextRequired;
import io.github.carloslimajlle.liquimanager.policy.builtin.DeleteWithoutWhere;
import io.github.carloslimajlle.liquimanager.policy.builtin.DropTableWarn;
import io.github.carloslimajlle.liquimanager.policy.builtin.FailOnErrorFalse;
import io.github.carloslimajlle.liquimanager.policy.builtin.GrantToPublicForbidden;
import io.github.carloslimajlle.liquimanager.policy.builtin.OneChangePerChangeset;
import io.github.carloslimajlle.liquimanager.policy.builtin.Phase2Checks;
import io.github.carloslimajlle.liquimanager.policy.builtin.Phase3Checks;
import io.github.carloslimajlle.liquimanager.policy.builtin.RollbackRequired;
import io.github.carloslimajlle.liquimanager.policy.builtin.SecretInChangelog;
import io.github.carloslimajlle.liquimanager.policy.builtin.SqlChecks;
import io.github.carloslimajlle.liquimanager.policy.builtin.TruncateTableWarn;

/**
 * Registry of the prebuilt checks that ARE ALREADY IMPLEMENTED.
 *
 * <p>The full catalogue (51 configurable + 4 process checks) lives in
 * docs/08-motor-de-politicas.md and is mirrored in the database by
 * seed/003-checks.sql. Checks without an implementation sit in the
 * database with {@code enabled = false}; the engine skips a package entry it
 * cannot resolve rather than failing the whole evaluation.
 *
 * <p>When implementing a new check: register it here and mark it as phase 1 in the
 * catalogue, so the seed enables it. {@code BuiltinChecksTest} fails if a registered check's
 * code is not in the catalogue, or if a phase-1 catalogue check is missing from here.
 */
public final class BuiltinChecks {

    private BuiltinChecks() {
    }

    public static List<PolicyCheck> all() {
        return List.of(
                // changeset standards
                new RollbackRequired(),
                new ChangesetCommentRequired(),
                new ChangesetIdPattern(),
                new ChangeChecks.AuthorRequired(),
                new ChangeChecks.AuthorAllowedList(),
                new ChangeChecks.LabelRequired(),
                new ContextRequired(),
                new OneChangePerChangeset(),
                new FailOnErrorFalse(),
                // data protection
                new DropTableWarn(),
                new SqlChecks.DropColumnWarn(),
                new TruncateTableWarn(),
                new DeleteWithoutWhere(),
                new SqlChecks.UpdateWithoutWhere(),
                new SqlChecks.AddNotNullWithoutDefault(),
                new SqlChecks.RenameObjectWarn(),
                // authorization and access
                new SqlChecks.GrantStatementReview(),
                new GrantToPublicForbidden(),
                new SqlChecks.GrantWithGrantOption(),
                new SqlChecks.CreateLoginOrUser(),
                new SecretInChangelog(),
                // database compatibility
                new ChangeChecks.DbmsAttributeRequiredForRawSql(),
                new SqlChecks.DeprecatedDataType(),
                // performance and structure
                new SqlChecks.CheckTablesForIndex(),
                new SqlChecks.ForeignKeyWithoutIndex(),
                new ChangeChecks.TableColumnLimit(),
                new SqlChecks.SqlSelectStarWarn(),
                new SqlChecks.NoLockHintWarn(),
                new SqlChecks.PrimaryKeyRequired(),
                // metadata and naming
                new ChangeChecks.ObjectNamingConvention(),
                new ChangeChecks.TableNamePattern(),
                new ChangeChecks.ColumnNamePattern(),
                new ChangeChecks.SchemaRequired(),
                // phase 3
                new Phase3Checks.CreateIndexOnline(),
                new Phase3Checks.CursorUsageWarn(),
                new Phase3Checks.PiiColumnEncryption(),

                new Phase2Checks.RunAlwaysForbidden(),
                new Phase2Checks.RunOnChangeReview(),
                new Phase2Checks.ChangesetStandards(),
                new Phase2Checks.ModifyDataTypeNarrowing(),
                new Phase2Checks.UniqueIndexRequiresDedup(),
                new Phase2Checks.AlterRoleMembership(),
                new Phase2Checks.IdentityVsSequence(),
                new Phase2Checks.ReservedWordAsIdentifier(),
                new Phase2Checks.CrossDatabaseReference(),
                new Phase2Checks.CollationExplicit(),
                new Phase2Checks.TableCommentRequired(),
                new Phase2Checks.ColumnCommentRequired(),
                new Phase2Checks.PiiColumnClassification(),
                new Phase2Checks.SeedDataWithPii(),
                new Phase2Checks.LoadDataFileSize());
    }
}
