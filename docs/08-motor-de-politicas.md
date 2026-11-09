# 08 — Motor de políticas (checks)

Biblioteca `lqm-policy-engine` (Java 21, sem Spring), usada pela API, CLI e starter.

## 1. Conceitos

| Conceito | Definição |
|----------|-----------|
| **Check** | Regra que avalia um changeset (escopo CHANGELOG) ou o banco alvo (escopo DATABASE) e produz achados |
| **Severidade** | 0 Info · 1 Minor · 2 Major · 3 Critical · 4 Blocker (exibidos como INFO, MINOR, MAJOR, CRITICAL, CRITICAL/blocker) |
| **Pacote** | Conjunto de checks com overrides de severidade/parâmetros, atribuído a projeto/changelog/estágio |
| **Gate** | Resultado de rodar o pacote em um estágio: `PASSED`, `WARNED`, `BLOCKED` |
| **Exceção** | Permissão temporária e aprovada para um check não bloquear um changeset |
| **Check encadeado** | Check que agrega outros e reporta a pior severidade (ex.: `ChangesetStandards`) |

## 2. Interface

```java
public interface PolicyCheck {
  String code();                                   // "GrantStatementReview"
  CheckScope scope();                              // CHANGELOG | DATABASE | BOTH
  List<Finding> evaluate(CheckContext ctx, Map<String, Object> params);
}

public record CheckContext(
  DatabaseChangeLog changelog,                     // liquibase.changelog.DatabaseChangeLog já parseado
  List<ChangeSet> changeSetsUnderReview,           // pendentes p/ o ambiente (listUnrunChangeSets) ou todos
  Database database,                               // liquibase.database.Database (pode ser OfflineConnection p/ SQL Server)
  Function<ChangeSet, List<String>> generatedSql,  // SqlGeneratorFactory → SQL por changeset (cacheado)
  Optional<DatabaseSnapshot> snapshot,             // só para escopo DATABASE
  String environmentCode, boolean isProduction) {}

public record Finding(String checkCode, int severity, String changesetKey, String objectName, Integer line, String message) {}
```

Avaliação: `PolicyEngine.evaluate(ctx, effectivePackage, exceptions)` → `EvaluationResult(gate, findings)`.
Regra de gate: `max(severity)` ≥ `blockThreshold` (3 em PRD, 4 nos demais, por padrão) e sem exceção vigente → `BLOCKED`;
algum achado ≥ 2 → `WARNED`; senão `PASSED`.

**Análise de SQL**: para changesets em XML/YAML/JSON o SQL é gerado offline pelo Liquibase para o dialeto do alvo
(`OfflineConnection` `offline:mssql`), então checks de padrão SQL funcionam igual para todos os formatos.
Parser SQL: JSqlParser (com fallback para regex quando o parse falha).

## 3. Tipos de check customizado

| Kind | Parâmetros | Exemplo |
|------|-----------|---------|
| `REGEX` | `pattern`, `appliesTo` (SQL \| CHANGESET_XML \| COMMENT \| ID), `mustMatch` (bool) | Proibir `NOLOCK` em views |
| `SQL_PATTERN` | `statementType` (CREATE_TABLE, ALTER_TABLE, DROP, GRANT…), `operator` (STARTS_WITH/CONTAINS/ENDS_WITH/REGEXP/ALL), `tables`, `columns`, `requiredConstraints[]` | Tabelas `tb_pessoa*` com coluna `cpf` exigem FK |
| `SPEL` | `expression`: SpEL sobre `changeSet`, `change`, `sql`, `env`; verdadeira = viola (§ 7) | `changeSet.comments == null && env == 'PRD'` |
| `CHAINED` | lista de checks | `ChangesetStandards` |

Os parâmetros da tela **Policy Library** (Nome, Categoria, Severidade, Escopo, Tipos de changelog, Operador,
Tabelas a verificar, Coluna, Constraints exigidas, Mensagem) são exatamente o `parameters_schema` do kind `SQL_PATTERN`.
A mensagem aceita variáveis: `<TABLE_NAME>`, `<COLUMN_NAME>`, `<CHANGESET_ID>`, `<AUTHOR>`, `<ENV>`, `<OBJECT>`.

## 4. Catálogo pré-construído (50 checks, 7 categorias) {#catálogo}

Legenda: **P** = incluso no pacote `default`. Sev = severidade padrão. Fase = quando implementar (1 = MVP).

### 4.1 Padrões de changeset (Scripting Standards)

| Código | Descrição | Sev | P | Fase |
|--------|-----------|-----|---|------|
| `RollbackRequired` | Changeset sem `<rollback>` e não auto-reversível | 2 | P | 1 |
| `ChangesetCommentRequired` | Changeset sem `<comment>` | 0 | P | 1 |
| `ChangesetIdPattern` | ID fora do padrão (param `pattern`, padrão `^SUP-\d+-\d{3}.*`) | 1 | P | 1 |
| `AuthorRequired` / `AuthorAllowedList` | Autor vazio ou fora da lista | 1 | | 1 |
| `LabelRequired` | Sem label de release | 1 | P | 1 |
| `ContextRequired` | Sem context (os projetos usam context = perfil) | 2 | P | 1 |
| `OneChangePerChangeset` | Mais de N changes no mesmo changeset (param `max`, padrão 1) | 1 | P | 1 |
| `RunAlwaysForbidden` / `RunOnChangeReview` | `runAlways`/`runOnChange` sem justificativa | 2 | | 2 |
| `FailOnErrorFalse` | `failOnError="false"` | 3 | P | 1 |
| `ChangesetStandards` | CHAINED: RollbackRequired + ChangesetCommentRequired + LabelRequired | 2 | P | 2 |

### 4.2 Proteção de dados (Data Protection)

| Código | Descrição | Sev | P | Fase |
|--------|-----------|-----|---|------|
| `DropTableWarn` | `DROP TABLE` | 3 | P | 1 |
| `DropColumnWarn` | `DROP COLUMN` | 3 | P | 1 |
| `TruncateTableWarn` | `TRUNCATE` | 4 | P | 1 |
| `DeleteWithoutWhere` / `UpdateWithoutWhere` | DML sem `WHERE` | 4 | P | 1 |
| `ModifyDataTypeNarrowing` | Redução de tamanho/tipo de coluna (ex.: `VARCHAR(200)`→`VARCHAR(50)`) | 3 | P | 2 |
| `UniqueIndexRequiresDedup` | Criação de UNIQUE/PK em tabela existente sem changeset anterior de deduplicação ou `precondition` de duplicidade | 4 | P | 2 |
| `AddNotNullWithoutDefault` | `NOT NULL` em tabela existente sem `defaultValue` | 3 | P | 1 |
| `RenameObjectWarn` | `RENAME` de tabela/coluna (quebra consumidores) | 2 | P | 1 |

### 4.3 Autorização e acesso

| Código | Descrição | Sev | P | Fase |
|--------|-----------|-----|---|------|
| `GrantStatementReview` | `GRANT` em tabelas listadas (SQL_PATTERN) | 3 | P | 1 |
| `GrantToPublicForbidden` | `GRANT ... TO PUBLIC` | 4 | P | 1 |
| `GrantWithGrantOption` | `WITH GRANT OPTION` | 3 | P | 1 |
| `CreateLoginOrUser` | `CREATE LOGIN/USER` em changelog de aplicação | 3 | P | 1 |
| `AlterRoleMembership` | `ALTER ROLE ... ADD MEMBER` / `sp_addrolemember` | 3 | P | 2 |
| `SecretInChangelog` | Senha/token/connection string em changeset (regex de entropia + padrões) | 4 | P | 1 |

### 4.4 Compatibilidade de banco

| Código | Descrição | Sev | P | Fase |
|--------|-----------|-----|---|------|
| `DbmsAttributeRequiredForRawSql` | `<sql>`/`<sqlFile>` sem `dbms` | 1 | P | 1 |
| `DeprecatedDataType` | `TEXT`, `NTEXT`, `IMAGE` (SQL Server) | 2 | P | 1 |
| `IdentityVsSequence` | Mistura de identity e sequence na mesma tabela | 1 | | 2 |
| `ReservedWordAsIdentifier` | Nome de objeto é palavra reservada no dialeto | 2 | P | 2 |
| `CrossDatabaseReference` | Referência `[OutroBanco].dbo.tabela` (o projeto usa `${db.name}`) | 2 | P | 2 |
| `CollationExplicit` | Coluna com collation diferente da do banco | 1 | | 2 |

### 4.5 Desempenho e estrutura (Advanced)

| Código | Descrição | Sev | P | Fase |
|--------|-----------|-----|---|------|
| `CheckTablesForIndex` | Tabela criada sem índice além da PK quando tem FK | 0 | P | 1 |
| `ForeignKeyWithoutIndex` | FK sem índice na coluna | 2 | P | 1 |
| `TableColumnLimit` | Tabela com mais de N colunas (padrão 50) | 2 | P | 1 |
| `CreateIndexOnline` | Índice em tabela grande sem `ONLINE = ON` (SQL Server Enterprise) — usa snapshot p/ tamanho | 2 | | 3 |
| `SqlSelectStarWarn` | `SELECT *` em view/procedure | 0 | P | 1 |
| `NoLockHintWarn` | `WITH (NOLOCK)` | 1 | P | 1 |
| `PrimaryKeyRequired` | `CREATE TABLE` sem PK | 3 | P | 1 |
| `CursorUsageWarn` | Cursor em procedure | 1 | | 3 |

### 4.6 Metadados e nomenclatura

| Código | Descrição | Sev | P | Fase |
|--------|-----------|-----|---|------|
| `ObjectNamingConvention` | Prefixos: `pk_`, `fk_`, `uk_`, `ix_`, `ck_`, `df_` (parâmetros por tipo) | 0 | P | 1 |
| `TableNamePattern` | Nome de tabela fora do padrão (regex) | 1 | | 1 |
| `ColumnNamePattern` | Nome de coluna fora do padrão | 1 | | 1 |
| `TableCommentRequired` | Tabela sem descrição (`remarks`) | 0 | P | 2 |
| `ColumnCommentRequired` | Coluna sem `remarks` | 0 | | 2 |
| `SchemaRequired` | Change sem `schemaName` explícito | 1 | P | 1 |

### 4.7 Dados sensíveis

| Código | Descrição | Sev | P | Fase |
|--------|-----------|-----|---|------|
| `PiiColumnClassification` | Coluna com nome sugestivo de PII (`cpf`, `cnpj`, `rg`, `email`, `telefone`, `cnh`, `placa`) sem label `pii` | 2 | P | 2 |
| `PiiColumnEncryption` | Coluna PII sem criptografia/mascaramento (Always Encrypted / Dynamic Data Masking) | 2 | | 3 |
| `SeedDataWithPii` | `<insert>`/`<loadData>` com valores que parecem CPF/e-mail reais | 3 | P | 2 |
| `LoadDataFileSize` | `loadData` com CSV > N MB | 1 | | 2 |

### 4.8 Checks de processo (gerados pelo sistema, não configuráveis pelo usuário)

| Código | Origem | Sev |
|--------|--------|-----|
| `OutOfProcessChange` | Changeset aplicado por origem não autorizada no ambiente ou só visto pelo scanner | 3 |
| `ChecksumChanged` | Changeset já aplicado teve o conteúdo alterado (MD5SUM divergente) | 4 |
| `ManualBreakFix` | Changeset `break-fix` aplicado pulando estágios | 2 |
| `DriftDetected` | Execução de drift encontrou diferenças | 2 |

Total: **51 checks configuráveis** + 4 de processo. A tela 07 mostra "50+ em 7 categorias".

## 5. Testes do motor

- Cada check tem fixtures em `src/test/resources/checks/<Code>/{pass,fail}/*.xml` e teste parametrizado.
- Teste de contrato: mesmos fixtures rodados via CLI e via API (`POST /policy-checks/{id}/test`) devem gerar achados idênticos.

## 6. Estado da implementação (fase 1)

**33 dos 51 checks configuráveis estão implementados** — todos os marcados com fase 1 na tabela acima. Os 18 restantes (fases 2 e 3) estão no catálogo e no banco, **desabilitados**, e o motor ignora uma entrada de pacote sem implementação em vez de falhar a avaliação inteira. A tela da biblioteca mostra `implemented` para cada um, para não prometer proteção que não existe.

Três testes impedem que a especificação e o código se afastem (`CatalogueConsistencyTest`): todo check implementado existe neste catálogo, todo check de fase 1 está implementado, e a severidade padrão do código é a da tabela.

**Os 4 checks de processo (§ 4.8) não são implementados no motor.** `OutOfProcessChange`, `ChecksumChanged`, `ManualBreakFix` e `DriftDetected` são gerados pelo sistema a partir de ingestão, scanner e drift — chegam com E14/E15 (fase 2). **`ChecksumChanged` já existe**, fora do motor: o coletor (`ChecksumGate`) manda os checksums que calcula agora e o servidor (`POST /policies/checksum-check`) responde quais já foram executados no ambiente com outro checksum, com as linhas que mudaram; o achado entra no gate e no comentário do MR com a severidade do pacote (padrão bloqueante). Um `validCheckSum` que cobre o checksum aplicado o dispensa. Trocar o author ou o arquivo de um changeset já aplicado também é acusado (o Liquibase o trata como novo), e nesse caso nada o dispensa. Estão no seed, habilitados, porque a tabela de achados precisa referenciá-los.

Decisões que o catálogo não fechava:

- **`GrantStatementReview`**: com o parâmetro `tables` vazio, **todo** `GRANT` é revisado — o padrão mais seguro para um check cujo propósito é pôr uma pessoa no processo. Com a lista, só os que citam aquelas tabelas, por palavra inteira (a tabela `a` não casa com "gr**a**nt").
- **`AuthorAllowedList`**: sem lista configurada, **não acusa nada**. Uma lista vazia que rejeitasse todo mundo bloquearia todo deploy no instante em que o check fosse ligado.
- **`PrimaryKeyRequired`**: uma chave primária criada por um `ALTER TABLE` no **mesmo** changeset conta. Uma tabela que só ganha chave num changeset posterior é acusada, e com razão: até lá ela não tem.
- **`SqlSelectStarWarn`**: numa view a busca para no fim do statement; no corpo de uma procedure ela segue até o próximo `CREATE`/`ALTER`, o que pode pegar um `select *` solto logo depois. Aceito porque o check é informativo (severidade 0).
- **`DeprecatedDataType`** exige o tipo depois de um espaço, para uma coluna chamada `[text]` não disparar.
- **Pacote efetivo**: a precedência é **estágio > changelog > pacote `default` do workspace**. Não há degrau de projeto, porque o modelo (docs/05) nunca guardou um pacote por projeto.

## 7. Estado da implementação (fase 2, E9)

Checks escritos na biblioteca são **dados**, interpretados pelo próprio motor (`CustomChecks`) — o mesmo código na API, na CLI e no starter:

- **`REGEX`** (`pattern`, `appliesTo` = `SQL` | `COMMENT` | `ID`, `mustMatch`) e **`SQL_PATTERN`** (`statementType`, `operator`, `tables`, `columns`, `requiredConstraints`) funcionam como descrito no § 3. `CHANGESET_XML` **não** é suportado como `appliesTo`: o Liquibase não guarda o XML do changeset.
- **`CHAINED`** roda os membros e emite **um** achado por changeset, com a pior severidade entre eles; a severidade do próprio encadeado não conta. Um membro não pode ser outro encadeado.
- **`SPEL`** (`expression`) roda no próprio motor, só com `spring-expression` (D-21), num `SimpleEvaluationContext`: leitura de propriedades e operadores, **sem** chamada de método, `T(...)`, `new` ou beans. A expressão é verdadeira quando o changeset **viola** a regra. Variáveis: `changeSet` (`id`, `author`, `comments`, `labels`, `contexts`, `filePath`, `runOnChange`, `runAlways`, `failOnError`), `change` (`type`, ex.: `createTable`; avaliado uma vez por change), `sql` (o SQL gerado, em minúsculas; use `matches`) e `env`. No máximo um achado por changeset. Expressão que não compila é recusada ao salvar (422); se mesmo assim chegar ao motor, ou ler um nome que não existe, ou não devolver booleano, o check se cala.
- **Cópia de um builtin** tem código, severidade e parâmetros próprios e roda a lógica do pai (`implementation`). Uma cópia de cópia aponta para o builtin raiz. Builtin nunca é editado nem excluído.
- Uma regex inválida é recusada **ao salvar** (422); se mesmo assim chegar ao motor, o check se cala.
- O dry-run (`POST /policy-checks/{id}/test`) usa o mesmo motor e a mesma geração de SQL (`SqlGeneration`) dos coletores, sobre um banco **offline** do dialeto escolhido.
- Todo create/update/delete/copy de check e de pacote gera `audit_event` com before/after.

Sem implementar: o teste de contrato CLI × API do § 5 com fixtures por check (hoje há o equivalente em testes de integração por kind).

## 8. Estado da implementação (fase 3, E25)

Implementados em `Phase3Checks`: `CreateIndexOnline`, `CursorUsageWarn` e `PiiColumnEncryption`. O seed gerado
(`003-checks.sql`) só habilita a fase 1, e um changeset aplicado não pode mudar, então `seed/004-phase3-checks.sql`
habilita os três. Nenhum está no pacote `default` (coluna P vazia): habilitar só os deixa disponíveis para um pacote.

- `CursorUsageWarn` lê o SQL gerado e vale em qualquer dialeto.
- `CreateIndexOnline` e `PiiColumnEncryption` falam de recursos do SQL Server (índice online, Always Encrypted e
  máscara dinâmica de dados) e **se calam em qualquer outro banco**: acusar uma coluna do PostgreSQL por não ter um
  recurso que ele não tem seria um falso positivo que bloqueia deploy (ADR-004).
- **Desvio da spec:** `CreateIndexOnline` deveria usar o snapshot para o tamanho da tabela, e um check de changelog não
  tem snapshot. O parâmetro `tables` (lista separada por vírgula) nomeia as tabelas grandes; sem lista, todo índice
  criado sem `ONLINE = ON` é apontado.
- `PiiColumnEncryption` usa o parâmetro `columns` (padrão `cpf,cnpj,rg,email,telefone,cnh,placa`) e aceita
  `ENCRYPTED WITH` ou `MASKED WITH` na definição da coluna. Só olha `CREATE TABLE` e `ALTER TABLE ... ADD`.
- Os checks de fase 2 foram implementados depois, ver § 9.

## 9. Estado da implementação (fase 2 restante)

Os 15 checks de fase 2 que faltavam estão em `Phase2Checks`; **todo check configurável do catálogo tem implementação**, e o que o motor
não avalia são os 4 de processo, que o sistema levanta sozinho (scanner, drift, ingestão). `seed/006-phase2-checks.sql` os habilita e
coloca no pacote `default` os nove marcados `P` (os outros ficam só disponíveis para um pacote). Cada um é escrito para **se calar
na dúvida**: vários estão no pacote padrão em severidade que bloqueia produção.

| Check | O que olha | Decisões e limites |
|-------|-----------|--------------------|
| `RunAlwaysForbidden`, `RunOnChangeReview` | `runAlways` / `runOnChange` | "Sem justificativa" é um changeset sem `comment`. |
| `ChangesetStandards` | encadeia checks e diz **uma** coisa por changeset, com a pior severidade | Padrão: `RollbackRequired`, `ChangesetCommentRequired`, `LabelRequired`; o parâmetro `checks` troca a lista (também aceita `AuthorRequired`, `ContextRequired`, `ChangesetIdPattern`, `OneChangePerChangeset`). Se os checks encadeados também estão ligados, o mesmo problema aparece nos dois. |
| `ModifyDataTypeNarrowing` | `ALTER COLUMN` para um tipo que cabe menos | Um check de changelog não tem snapshot: o tipo antigo é o que **o próprio changelog** declarou antes (`createTable`, `addColumn`, `modifyDataType`). Coluna cujo tipo antigo o changelog nunca diz **não é julgada**. Compara tamanho de texto, família de inteiros e precisão/escala de decimais; tipos de famílias diferentes não são julgados. |
| `UniqueIndexRequiresDedup` | `UNIQUE`/`PRIMARY KEY` numa tabela que **já existe** | "Existe" = o mesmo changeset não a cria. Tratou duplicados quem tem, antes, um `DELETE` na tabela (neste changeset ou num anterior), um changeset anterior cujo id ou comentário diz `dedup`/`duplicad` e cita a tabela, ou uma `precondition` SQL que conta ou agrupa. Não olha os dados: a tabela pode estar vazia e ainda assim ser apontada, e quem sabe disso usa uma exceção. |
| `AlterRoleMembership` | `ALTER [SERVER] ROLE … ADD/DROP MEMBER`, `sp_addrolemember` | SQL gerado. |
| `IdentityVsSequence` | identity (ou serial) e valor padrão de sequence no mesmo `CREATE TABLE` | SQL gerado. |
| `ReservedWordAsIdentifier` | nomes de tabela, coluna, índice, view e sequence | Usa a lista de palavras reservadas do **dialeto** que o Liquibase conhece; sem dialeto, não diz nada. |
| `CrossDatabaseReference` | nome em três partes depois de `FROM`, `JOIN`, `INTO`, `UPDATE`, `TABLE`, `VIEW`, `REFERENCES`, `EXEC` | O SQL já chega com o `${db.name}` expandido, então o nome do banco do projeto aparece como referência a outro banco: o parâmetro `allowed` lista os nomes aceitos. |
| `CollationExplicit` | `COLLATE` numa definição de coluna | O changelog não vê a collation do banco: o parâmetro `database` diz qual é, e sem ele toda collation explícita é apontada. |
| `TableCommentRequired`, `ColumnCommentRequired` | `remarks` em `createTable` e `addColumn` | A de colunas dá **um** achado por changeset, citando algumas. |
| `PiiColumnClassification` | coluna com nome de dado pessoal (parâmetro `columns`) num changeset sem o label `pii` | Mesmo reconhecimento de definição de coluna do `PiiColumnEncryption`; vale em qualquer dialeto. |
| `SeedDataWithPii` | `INSERT` com CPF de dígitos verificadores válidos ou e-mail fora de domínio de exemplo | Dígitos de CPF que não fecham, ou repetidos, não contam; domínios `example.*`, `test`, `local`, `invalid`, `localhost` são aceitos. Só `INSERT` (o `loadData` entra pelo SQL que ele gera). |
| `LoadDataFileSize` | `loadData` grande | **Desvio da spec:** um check não tem sistema de arquivos, então mede o **SQL que o `loadData` gera**, maior que o CSV; `maxMb` (padrão 5) é em MB de SQL. Sem SQL gerado, não diz nada. |
