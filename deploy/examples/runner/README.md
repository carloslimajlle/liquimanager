# O runner de rollback: um processo, em qualquer lugar

O LiquiManager **não cria Jobs, contêineres nem processos** para executar um rollback. Depois que o pedido é aprovado, quem o executa é o
`lqm runner`, um processo comum que **puxa** o trabalho: pergunta à API "há rollback aprovado?", clona o changelog no commit da operação
original, roda o rollback no banco e reporta o resultado. Por isso ele roda em qualquer lugar que alcance a API e o banco, e o produto não
depende de nenhum orquestrador (D-19).

Dois modos, e a escolha é de quem opera:

| Modo | Comando | Quando |
|------|---------|--------|
| **Serviço** (o recomendado) | `lqm runner` | Fica de pé, consulta a cada `--poll-seconds` (10), executa e volta a consultar. Em SIGTERM **termina o rollback em andamento** antes de sair (`--grace-seconds`, 600), então reiniciar, atualizar ou parar não corta um rollback ao meio. |
| **Pontual** | `lqm runner --once` | Pega no máximo um rollback e sai. Para quem já tem um agendador: cron, uma pipeline agendada, um CronJob. |

Pode haver **vários runners** ao mesmo tempo (um por rede, por ambiente, por equipe): a API entrega cada pedido a um só.

## O que o runner precisa

- `LQM_URL` e `LQM_TOKEN`: um token de serviço com os escopos `runner` e `ingest` (tela Tokens).
- `LQM_DB_USERNAME` e `LQM_DB_PASSWORD`: a conta que pode **reverter changesets** no banco alvo. São do runner; o LiquiManager nunca as envia.
- `LQM_GIT_URL` (ou `--git-url`): o repositório do changelog, com credencial de **leitura** se for privado. Sem ele, o runner usa o checkout em
  `--workdir`, que precisa estar no commit certo.
- `git` e Java 21 (a imagem `lqm-cli` traz os dois; `docker build -f lqm-cli/Dockerfile -t lqm-cli .`).

## Exemplos, um por plataforma

| Arquivo | Onde |
|---------|------|
| [`docker-compose.yml`](docker-compose.yml) | Qualquer máquina com Docker: serviço com `restart: unless-stopped`. |
| [`lqm-runner.service`](lqm-runner.service) e [`lqm-runner.env.example`](lqm-runner.env.example) | Linux com systemd, sem contêiner. |
| [`crontab.example`](crontab.example) | cron, ou o Agendador de Tarefas do Windows com o mesmo comando. |
| [`gitlab-ci.yml`](gitlab-ci.yml) | Um job de pipeline agendada do GitLab. |
| [`kubernetes.yaml`](kubernetes.yaml) | Kubernetes, **como uma opção entre as outras**: um `Deployment` (ou um CronJob com `--once`). |

Nenhum deles foi exercitado num ambiente de produção; o serviço (contêiner e parada com SIGTERM) foi verificado com a imagem `lqm-cli`.
