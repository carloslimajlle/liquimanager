# 12 — Registro de decisões

**Todas as decisões listadas estão fechadas** e já refletidas nos demais documentos. Este arquivo é o
registro: mantenha-o como histórico e acrescente novas linhas (D-18 em diante) quando surgirem decisões,
sempre atualizando os documentos afetados no mesmo commit.

Três delas carregam restrições que valem reler antes de começar: **D-13** (a IA é feature futura e nasce
desativada), **D-17** (Liquibase 5.x é FSL, e as APIs internas usadas pelo § 4.4 mudaram entre a 4.x e a 5.x)
e **D-18** (o starter fica em Spring Boot 3.4 enquanto os serviços não migrarem).

> **D-12 não existe.** Era "time e datas"; foi descartada por não ser uma decisão de produto. O planejamento
> vive no [11-roadmap-e-backlog](11-roadmap-e-backlog.md). Os IDs **não** foram renumerados de propósito —
> D-13 e D-17 são citadas em vários documentos e renumerar quebraria essas referências.

| ID | Tema | Opções | Recomendação | Decisão | Responsável | Data |
|----|------|--------|--------------|---------|-------------|------|
| D-01 | Banco do próprio LiquiManager | PostgreSQL 16 · SQL Server | PostgreSQL (JSONB, particionamento nativo). Se a infra só oferece SQL Server, adaptar DDL de 05 | PostgreSQL | Carlos | 01/11/26 |
| D-02 | Nome do produto e prefixo técnico | DB Manager · outro | Manter "DB Manager" (pasta `database-manager`) | **LiquiManager**, pasta `liquimanager`, prefixo técnico **`lqm`** em tudo: módulos (`lqm-api`…), comando da CLI (`lqm`), properties (`lqm.*`), flow file (`lqm.flow.yaml`), header `X-LQM-Workspace`, tokens `lqm_`, códigos `LQM-0xx`, pacote `io.github.carloslimajlle.liquimanager` | Carlos | 01/11/26 |
| D-03 | Provedor SSO | Azure AD (Entra ID) · Keycloak · outro | O que o `backend/seguranca` já usa | Keycloak | Carlos | 01/11/26 |
| D-04 | Provedor de segredos | Vault · Consul KV · Azure Key Vault | Consul KV já existe (`tools/consulkvsync`); Vault se houver | Vault | Carlos | 01/11/26 |
| D-05 | Onde rodar migrations em PRD | Startup do pod (atual) · passo `migrate-only` antes do rollout · job no GitLab | Passo `migrate-only` — falha não derruba o serviço, e 2+ réplicas não disputam lock | Passo migrate-only (um Job do k8s, um passo de pipeline ou um contêiner único: de quem opera, D-19) | Carlos | 01/11/26 |
| D-06 | Referência do drift | Último snapshot limpo · banco de referência · banco efêmero | Último snapshot limpo; efêmero na fase 3 | Último snapshot | Carlos | 01/11/26 |
| D-07 | Fail-open × fail-closed em PRD | fail-open · fail-closed | fail-open + alerta; fail-closed só após 3 meses estável | fail-open + alerta; fail-closed só após 3 meses estável | Carlos | 01/11/26 |
| D-08 | Retenção de operações/auditoria | 1 · 5 · 10 anos | 5 anos (validar com Compliance/Jurídico) | 5 anos | Carlos | 01/11/26 |
| D-09 | Object storage para logs | MinIO · Azure Blob · S3 · no próprio banco | O que o serviço `drive` usa | MinioIO | Carlos | 01/11/26 |
| D-10 | Domínios/ingress | | `lqm.<domínio-interno>` | `liquimanager.<domínio-interno>` | Carlos | 01/11/26 |
| D-11 | Liberação de rede worker/runner → bancos | | Abrir chamado com infra listando hosts:portas | Abrir chamado com infra listando hosts:portas | Carlos | 01/11/26 |
| D-13 | IA: provedor e política de dados | Claude via API Anthropic · via Bedrock/Vertex · desligado | Claude via API, sem dados de linhas, aprovação de Segurança | **Feature futura, desativada por padrão** (`ai_enabled = false`). Fica especificada (RF-IA-01/02, `ai_analysis`, `/ai-analyses`, `ai:use`, `AiPanel`, § 4.5) mas **fora das fases 0–3**; provedor ainda não escolhido | Carlos | 01/11/26 |
| D-14 | Executar update pela UI? | Sim (runner) · Não (só observa + rollback) | Só rollback e promoção na fase 2; update via UI na fase 3 se houver demanda | Só rollback e promoção na fase 2; update via UI na fase 3 se houver demanda | Carlos | 01/11/26 |
| D-15 | Ambientes padrão | DEV/HML/PRD · DEV/TST/HML/PRD | DEV/TST/HML/PRD configuráveis (prints usam 4) | DEV/TST/HML/PRD configuráveis (prints usam 4) | Carlos | 01/11/26 |
| D-16 | Padrão de ID de changeset | `SUP-NNNN-NNN-descricao` · outro | Formalizar o padrão já usado nas pastas `work/SUP-XXXX` | SUP-NNNN-NNN-descricao | Carlos | 01/11/26 |
| D-17 | Licença Liquibase | OSS · comprar Secure | OSS (este projeto existe para isso) — verificar a licença FSL do Liquibase 5.x antes de atualizar | **OSS, linha 5.x liberada** para uso interno; versão exata fixada no `pom.xml` pai | Carlos | 01/11/26 |
| D-18 | Linha do Spring Boot | 3.4.x (como a spec supunha) · 4.x | 4.x na API e no worker; o starter fica em 3.4.x | **Spring Boot 4.x** (4.1.2) em `lqm-api` e `lqm-worker`; `lqm-spring-boot-starter` compilado contra 3.4.13 | Carlos | 01/11/26 |
| D-19 | O que executa o rollback pela UI | A API cria um `Job` do Kubernetes · um runner que puxa o trabalho, em qualquer lugar | A API cria um Job (o que a spec dizia) | **Um runner (`lqm runner`) que puxa o trabalho e roda em qualquer lugar**: serviço ou pontual, sem Kubernetes obrigatório | Carlos | 02/11/26 |
| D-20 | Onde rodam os jobs | Sempre na API · módulo `lqm-worker` separado · a mesma imagem da API em outro papel | Módulo separado (o que a arquitetura sugeria) | **A mesma imagem**: `LQM_JOBS_ENABLED=false` nas réplicas web e `true` nas de jobs; ShedLock evita a disputa. O módulo `lqm-worker` fica reservado | Carlos | 02/11/26 |
| D-21 | SpEL nos checks customizados | Ficar só salvo, sem rodar · `spring-expression` no motor, em contexto restrito | Rodar (a spec lista SpEL como kind) | **`spring-expression` no motor** (só ele: sem Spring Boot nem contexto), avaliado com `SimpleEvaluationContext`: sem métodos, tipos nem construtores | Carlos | 02/11/26 |
| D-22 | Ordem dos ambientes | Só a promoção da tela confere · o deploy (`lqm update`) confere | Não havia regra | **O deploy confere**: o que está em HML tem de estar antes em DEV, **na mesma versão** (mesmo checksum, salvo `validCheckSum`), segundo o que o LiquiManager sabe do estágio anterior do pipeline; `lqm update` para com código 3. Vale também para quem roda o pipeline do GitLab sem passar pela tela | Carlos | 03/11/26 |
| D-23 | `X-LQM-Workspace` ausente numa leitura | Valer "todos os workspaces que a pessoa vê" · valer o workspace `default` | A spec (docs/06) dizia "todos os permitidos" | **Vale o workspace `default`**, em leitura e em escrita. Agregar vários workspaces numa lista não existe e não está planejado: quem usa outro workspace manda o cabeçalho (a SPA ainda não tem seletor de workspace e usa o `default`). É a resposta estrita: nunca mostra mais do que a pessoa pediu | Carlos | 03/11/26 |
| D-24 | Ambientes e times na leitura por escopo | Filtrar por escopo como os demais recursos · catálogo comum | Nada decidido | **Catálogo comum**: quem lê algo em algum escopo vê a lista de ambientes e de times. São nomes, não dado de projeto, e as telas precisam do ambiente de cada linha que a pessoa pode ler. Escrever neles continua exigindo `workspace:admin` | Carlos | 04/11/26 |

> **D-18 — por que o starter fica para trás de propósito.**
>
> `lqm-api` e `lqm-worker` usam **Spring Boot 4.1.2** (Spring Framework 7). A escolha se
> sustenta sozinha: a 4.1.2 **já gerencia `liquibase-core` 5.0.4**, exatamente a versão que
> D-17 fixa, e o `springdoc-openapi` 3.2.0 é construído contra ela. Baseline de Java 17;
> o projeto usa 21.
>
> O **`lqm-spring-boot-starter` é a exceção** e continua compilado contra **3.4.13**. Ele
> entra no classpath dos serviços que o adotam, que rodam Boot 3.4 / Framework 6 — um
> artefato compilado contra o Framework 7 não carregaria lá dentro. O módulo importa o BOM
> da 3.4.x no próprio `pom.xml`, que vence o herdado do pai, e marca `spring-*` e
> `liquibase-core` como `provided`: quem manda é a versão do serviço.
>
> **Quando os serviços migrarem para Boot 4**, o starter pode acompanhar e a exceção some.
> Até lá, qualquer API nova usada pelo starter precisa existir nas duas linhas.

> **D-17 — Liquibase 5.x liberado.**
>
> O LiquiManager usa `liquibase-core` da **linha 5.x**. A versão exata é fixada no `pom.xml` pai
> (`<liquibase.version>`) no início da Fase 0 e só sobe por decisão explícita, nunca por atualização
> automática — configure Renovate/Dependabot para abrir MR mas não auto-mergear esse artefato.
>
> **Duas restrições que vêm junto e precisam ser respeitadas:**
>
> 1. **Licença.** A 5.x usa FSL, não Apache 2.0. A FSL permite uso interno e o uso como dependência, mas **veda oferecer
>    o produto como serviço ou produto concorrente**. O código do LiquiManager é Apache 2.0 e o
>    `liquibase-core` entra só como dependência (não é embutido nem redistribuído no repositório), mas quem
>    opera o LiquiManager aceita a FSL do Liquibase. Se algum dia o LiquiManager for oferecido como serviço
>    hospedado a terceiros, isto precisa ser reavaliado **antes** — registre aqui como nova decisão.
> 2. **API interna.** O [04-arquitetura](04-arquitetura.md) § 4.4 depende de APIs internas do `liquibase-core`
>    (`ChangeSet.rollback(Database)`, `ChangeLogHistoryService.removeFromHistory`, `SnapshotGeneratorFactory`)
>    para reimplementar o que é Pro. Essas assinaturas **mudaram entre a 4.x e a 5.x**. A primeira tarefa
>    técnica da Fase 0 é validar as três contra a 5.x escolhida; se alguma não existir mais, o ADR-001 precisa
>    de uma alternativa antes de a Fase 1 começar.
