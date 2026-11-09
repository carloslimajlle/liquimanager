# POC - Liquibase em Merge Request (GitLab CE self-hosted)

Valida migrations de banco no ciclo de revisão: todo MR roda `validate`,
publica o SQL que seria executado como artefato e aplica/reverte o changelog
em um Postgres efêmero.

## Estrutura

```
db/changelog/db.changelog-master.yaml   # master com includeAll
db/changelog/changes/                   # um arquivo por changeset
.gitlab-ci.yml                          # pipeline
```

## Pipeline

| Stage    | Job                  | Quando            | O que faz |
|----------|----------------------|-------------------|-----------|
| validate | `validate`           | sempre            | `liquibase validate` + `status --verbose` |
| preview  | `update-sql`         | só em MR          | gera `migration.sql` como artefato para revisão |
| apply    | `update-and-rollback`| MR e branch default | `update`, `history`, `rollback-count 1`, `update` |

Cada job sobe seu próprio `postgres:16-alpine` como service, então nada toca
banco real. O `rollback-count` prova que todo changeset novo tem rollback válido.

## Rodando local

```bash
docker run --rm -v "$PWD:/liquibase/changelog" -w /liquibase/changelog \
  liquibase/liquibase:latest \
  --url=jdbc:postgresql://host.docker.internal:5432/pocdb \
  --username=poc --password=poc \
  --changelog-file=db/changelog/db.changelog-master.yaml update
```

## Armadilhas que esta POC já pagou

- **`--no-banner` não existe** como flag de CLI. O banner só sai por
  `LIQUIBASE_SHOW_BANNER=false`. Usar a flag faz todo comando abortar com
  `Unexpected argument(s)`.
- **A imagem `liquibase/liquibase` 5.x só embute o driver do H2.** Para Postgres é
  preciso `lpm add postgresql --global` no `before_script`, senão o erro é
  `Cannot find database driver: org.postgresql.Driver`. Em pipeline de verdade,
  vale assar uma imagem própria com o driver em vez de baixar em cada job.
- **Probe de espera não deve engolir a saída.** O `liquibase status >/dev/null`
  do loop escondeu as duas falhas acima e fez parecer que o service `postgres`
  estava inacessível. O loop agora imprime o diagnóstico antes de abortar.

## Convenções adotadas

- Um changeset por arquivo, prefixo numérico sequencial (`001-`, `002-`).
- `rollback` explícito em todo changeset — sem isso o job `update-and-rollback` falha.
- Nunca editar um changeset já aplicado (checksum): criar um novo.
