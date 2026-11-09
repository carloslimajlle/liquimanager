# POC — GitLab CE + runner (validação de changeset em MR)

Ambiente **separado** do `deploy/docker-compose.yml`: aqui sobe só um GitLab CE
self-hosted com um runner, para exercitar a validação de migrations Liquibase
dentro do ciclo de merge request.

```bash
docker compose -f deploy/gitlab-poc/docker-compose.yml up -d
```

| Serviço | Endereço | Observação |
|---------|----------|------------|
| GitLab CE | http://localhost:8929 | usuário `root`, senha em `GITLAB_ROOT_PASSWORD` |
| GitLab SSH | `localhost:2224` | a 22 do host fica livre |
| gitlab-runner | — | executor `docker`, socket do host montado |

O primeiro boot leva de 4 a 8 minutos (`docker inspect gitlab --format '{{.State.Health.Status}}'`).

## `sample-project/`

Repositório de exemplo que é importado para dentro do GitLab da POC — não faz
parte do build do LiquiManager. Tem o changelog master com `includeAll`, dois
changesets e o `.gitlab-ci.yml` que valida a migration no MR (`validate`,
`update-sql` como artefato, `update` + `rollback-count` em Postgres efêmero).
O `.gitlab-ci.yml` daqui é inerte para este repositório: o GitLab só lê o da raiz.

## Por que `host.docker.internal` no `external_url`

O runner cria os containers de job como irmãos (socket do host montado), não
dentro da rede do compose. Com `external_url http://host.docker.internal:8929`,
o mesmo nome resolve no host, no runner e nos containers de job — então o
`git clone` do job e a URL que você abre no navegador são a mesma coisa.

## Portas

Não colide com o ambiente local do projeto, que usa 5432, 8081, 9000/9001 e 1433.

## Economia de recursos

Prometheus, Grafana, KAS e o container registry vêm desligados, e `puma`/`sidekiq`
estão reduzidos: o GitLab CE completo não cabe junto com o ambiente local nos
~8 GB que o Docker Desktop reserva. Ligue o que a POC precisar.

## Ligar ao LiquiManager

O `connect-lqm.py` conecta este GitLab a um LiquiManager local, para exercitar as três integrações contra um GitLab de verdade: a
leitura de arquivos do Developer Mode, o botão Promover (gatilho de pipeline) e o comentário de `lqm checks run` no merge request.
Precisa da API **sem OIDC** (ele não faz login).

```bash
python deploy/gitlab-poc/connect-lqm.py setup
docker compose -f deploy/docker-compose.yml -f deploy/gitlab-poc/docker-compose.lqm.yml --env-file deploy/gitlab-poc/.lqm/lqm.env up -d api
python deploy/gitlab-poc/connect-lqm.py register
python deploy/gitlab-poc/connect-lqm.py verify
```

`setup` cria o token de gatilho do projeto (em `.lqm/lqm.env`, fora do git); `register` cadastra no LiquiManager o projeto, o
changelog, duas conexões e o pipeline `POC-GATE` (DEV → HML) com o gatilho apontando para o `liquibase-poc`; `verify` lê o
changelog, promove para HML e confere no GitLab o pipeline criado e as variáveis recebidas.

### A CLI dentro do runner (`lqm-checks`)

O `.gitlab-ci.yml` do `sample-project` tem o job `lqm-checks`: dentro do runner, na imagem da CLI, ele roda `lqm checks run`, avalia o
changelog contra o pacote efetivo e comenta o resumo no merge request. A imagem vem do Docker local (o runner usa
`pull_policy = if-not-present`); num GitLab de verdade ela estaria no registry do grupo.

```bash
docker build -f lqm-cli/Dockerfile -t lqm-cli:local .          # a imagem
python deploy/gitlab-poc/connect-lqm.py ci                     # LQM_URL, LQM_TOKEN e LQM_GITLAB_TOKEN no projeto (mascarados)
# o .gitlab-ci.yml do sample-project vai para a branch do MR (feat/pedido) e o pipeline roda sozinho
```

`ci` cria um token de serviço (`ingest` e `checks:read`) e um token de projeto com escopo `api` (o que escreve a nota), e guarda os dois
só como variáveis mascaradas do GitLab, nunca em arquivo. O token de projeto é do tipo bot: a nota do MR passa a ser dele.

### Sem o runner

O comentário no merge request também roda pela CLI, a partir do `sample-project` (variáveis de ambiente, como no CI):

```bash
LQM_URL=http://localhost:8080/api/v1 LQM_TOKEN=<token de serviço> LQM_PROJECT=liquibase-poc LQM_ENV=DEV \
LQM_DB_URL=jdbc:postgresql://localhost:5432/lqm LQM_DB_USERNAME=lqm LQM_DB_PASSWORD=lqm \
LQM_GITLAB_TOKEN=glpat-poc-liquibase-token1 CI_API_V4_URL=http://localhost:8929/api/v4 CI_PROJECT_ID=1 CI_MERGE_REQUEST_IID=1 \
java -jar lqm-cli/target/lqm.jar checks run --changelog-file=db/changelog/db.changelog-master.yaml
```
