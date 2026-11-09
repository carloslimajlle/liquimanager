# LiquiManager — guia para contribuir

Control plane de mudanças de banco sobre **Liquibase OSS**. O produto não substitui o
Liquibase: ele observa, governa e dá visibilidade às mudanças que o `liquibase-core` executa.

A especificação em [`docs/`](docs/) é a fonte da verdade e está completa. **Leia o documento
da área antes de mexer nela.** Código que diverge da spec é bug; spec que diverge da realidade
deve ser corrigida no mesmo commit.

## Idioma

**Código em inglês, documentação e interface em pt-BR.** Identificadores, comentários,
javadoc, nomes de teste, comentários dos changelogs e o contrato OpenAPI são escritos em
inglês. Já `docs/`, este arquivo e as mensagens de commit ficam em português.

A fronteira passa pelo texto que o usuário lê: mensagens de finding dos checks, nomes de
templates de papel e descrições de check no seed continuam em pt-BR, porque aparecem na
tela (RNF-12). Termos do Liquibase (`update`, `rollback`, `changeset`, `drift`, `tag`)
ficam em inglês nos dois lados — são vocabulário de domínio, não prosa.

## Onde as coisas estão

| Caminho | O quê |
|---------|-------|
| `docs/` | Especificação: 13 documentos + 10 telas + mockups e prints alvo |
| `contracts/openapi.yaml` | Contrato da API, escrito à mão. Back e front trabalham contra ele |
| `lqm-policy-engine/` | Biblioteca pura (sem Spring Boot; só `spring-expression`, D-21) com os checks. Usada por API, CLI e starter |
| `lqm-core-client/` | Modelos de payload + cliente HTTP, usados por CLI e starter |
| `lqm-collector/` | O que CLI e starter compartilham: recorder de changesets, gate de políticas, montagem do relatório |
| `lqm-api/` | Spring Boot: REST, SSE, segurança. Dono do schema |
| `lqm-cli/` | Comando `lqm` (Picocli) |
| `lqm-spring-boot-starter/` | Auto-config para serviços que rodam Liquibase no startup |
| `lqm-worker/` | Jobs: drift, métricas, scanner, notificações |
| `lqm-web/` | SPA React. Alvo visual: `docs/prints/mockups/*.png`. `lqm-web/Dockerfile` a serve atrás do Nginx (`--profile web` no compose) |
| `deploy/` | `docker-compose.yml` do ambiente local |
| `.github/` | O workflow de CI (`ci.yml`) e o Dependabot (que não atualiza o Liquibase) |

## Ambiente local

```bash
docker compose -f deploy/docker-compose.yml up -d --build
cd lqm-web && npm install && npm run dev
```

API em 8080 (imagem `lqm-api/Dockerfile`, contexto na raiz; aplica o próprio schema ao subir), Postgres em 5432
(`lqm`/`lqm`/`lqm`), Keycloak em 8081, armazenamento S3 em 9000/9001 (RustFS; o MinIO saiu dos registries, o container
segue chamado `lqm-minio`). Sem emissor OIDC a API roda na cadeia de desenvolvimento (tudo liberado, `X-LQM-User`
representa um usuário). O front em `npm run dev` repassa `/api` para a 8080. Para entrar de verdade: acrescente
`-f deploy/docker-compose.oidc.yml` ao `up` e rode `npm run dev:oidc` (usuários e clients em `deploy/keycloak/README.md`).
`--profile target` adiciona um SQL Server para exercitar coleta e drift. `--profile vault` sobe um Vault de desenvolvimento (`deploy/vault/README.md`). `deploy/docker-compose.ha.yml` acrescenta uma segunda réplica da API (8083) e o armazenamento de objetos (docs/04, "mais de uma réplica"). **`npm run dev` não pede login**; para entrar use `npm run dev:oidc`
(e a API com o override OIDC). Para percorrer as telas sem login contra uma API de desenvolvimento **separada** (porta 8082, banco `lqm_ui`, sem OIDC),
suba uma API sem emissor nessa porta e use `npm run dev:ui` (porta 5174);
`localStorage.setItem('lqm.devUser', 'ana')` faz as chamadas agirem como ana, o que serve para ver a tela com um escopo restrito. O teste de ponta a ponta, do banco limpo ao rollback, está em `deploy/e2e/README.md`.
**Os testes de integração usam o banco `lqm_test`, nunca o `lqm` do ambiente** (`LQM_TEST_DB_URL` muda o padrão; crie o banco com 
`create database lqm_test`): eles criam projetos e conexões sem limpar, e no `lqm` isso aparece nas telas de quem está usando.

## Regras que não são óbvias no código

**O prefixo é `lqm`, em tudo.** Módulos, comando da CLI, properties (`lqm.*`),
`lqm.flow.yaml`, header `X-LQM-Workspace`, tokens `lqm_`, códigos de erro `LQM-0xx`.
O produto se chama LiquiManager (D-02). A exceção é o pacote Java e o `groupId`, que seguem a
convenção do GitHub para projeto pessoal: `io.github.carloslimajlle.liquimanager`.

**A API usa Spring Boot 4; o starter, não** (D-18). `lqm-api` e `lqm-worker` estão em Boot 4.1.2
(Framework 7). O `lqm-spring-boot-starter` é compilado contra **3.4.13**, porque entra no
classpath dos serviços que o adotam, que rodam Boot 3.4 — Framework 7 não carregaria lá. Ele
importa o BOM da 3.4 no próprio pom, que vence o herdado. Toda API usada pelo starter precisa
existir nas duas linhas.

**Boot 4 separou a auto-configuração por módulo.** Ter `liquibase-core` no classpath **não**
roda o changelog no startup: é preciso o `spring-boot-starter-liquibase`. Vale para qualquer
tecnologia nova — se uma auto-config "não pega", suspeite do starter antes do código. E
"a API aplica o schema" só vale depois de subir contra um banco **vazio**: um banco que já
tinha o schema esconde exatamente esse defeito.

**O pom pai importa o BOM do Boot, não herda do `spring-boot-starter-parent`** — é o que
mantém `lqm-policy-engine` e `lqm-core-client` livres de Spring. O preço é perder o que o
parent configura de graça. Já pegou um: a flag `-parameters` (`maven.compiler.parameters`),
sem a qual `@PathVariable UUID id` não resolve o nome e todo endpoint responde 422. Mudar
flag de compilador **não** recompila sozinho: depois, rode `mvn clean`.

**RBAC é aplicado, com uma tabela só** (E11). `RbacRules` diz que permissão cada rota exige, e
`RbacInterceptor` a aplica antes do controller: esquecer uma linha cai no padrão mais estrito
(escrita = `workspace:admin`, leitura = `assignment:view`). Rota nova ⇒ linha nova e teste em
`RbacRulesTest`. Em desenvolvimento, sem emissor OIDC, a chamada **sem identidade** passa livre
(é o que deixa o ambiente local subir sem Keycloak); a que traz `X-LQM-User` passa pelas mesmas
regras de um usuário real — é como se testa quem-faz-o-quê. **A leitura também respeita o escopo**: uma lista mostra só as
linhas que os bindings da pessoa cobrem (`ReadScope`, com o mesmo alvo que `ScopeResolver` dá ao recurso), e um GET com id no caminho
é julgado no escopo desse recurso. Lista nova ⇒ aplique o filtro de `ReadScope.Visible`. Uma escrita vale
por escopo: PROJECT, CONNECTION, PIPELINE e ENVIRONMENT valem para o recurso a que a ação se refere
(`ScopeResolver`, docs/09 § 8.2). Rota nova sobre um recurso que o resolvedor ainda não conhece só aceita
binding do workspace inteiro: acrescente a linha lá.

**Liquibase 5.x é FSL, não Apache 2.0** (D-17). A licença cobre uso interno, mas veda
oferecer o produto como serviço concorrente. A versão é travada em
`<liquibase.version>` no pom pai e **não sobe por atualização automática** — o § 4.4 da
arquitetura depende de APIs internas (`ChangeSet.rollback`,
`ChangeLogHistoryService.removeFromHistory`, `SnapshotGeneratorFactory`) que mudam entre
versões.

**Os coletores são fail-open** (ADR-004). Indisponibilidade do LiquiManager **nunca** pode
impedir um deploy, salvo `lqm.enforce.failClosed=true` no ambiente. Isso vale também para
erro de rede, timeout e resposta malformada: na dúvida, deixa passar e registra.

**Nenhuma credencial de banco entra aqui** (RNF-06). `db_connection.jdbc_url` é sem
usuário e senha; só a referência ao segredo é persistida. Resolução em memória, cache
de no máximo 5 minutos, nunca em disco, nunca em log.

**O motor de políticas é a mesma biblioteca nos três lugares.** Um check precisa dar o
mesmo resultado na CLI do dev, no starter dentro do pod e no servidor. Nada de lógica de
check fora de `lqm-policy-engine`.

Daí três regras ao escrever um check:

- **Sem estado.** A mesma instância é usada em paralelo. Nada de campo mutável, relógio
  (`Instant.now()`) ou acesso a rede dentro de um check — `PolicyEngine.evaluate` recebe o
  instante por parâmetro justamente para o resultado não depender de onde roda.
- **Na dúvida, não acusa.** Falso positivo aqui bloqueia deploy (ADR-004). Regex inválida,
  SQL que não deu para analisar, exceção inesperada: o check se cala. É o que `RollbackRequired`
  faz quando `supportsRollback` estoura.
- **O `code()` é igual ao nome da classe.** Há teste garantindo — é o que liga a implementação
  à linha do catálogo e ao seed.

Checks que olham SQL herdam de `SqlPatternCheck` e analisam o **SQL gerado**, não o XML:
assim `<dropTable>`, `<sql>DROP TABLE…</sql>` e um `<sqlFile>` caem todos no mesmo texto.
Os statements chegam em minúsculas e separados por `;` + quebra de linha, então `[^;]` significa
"dentro de um statement". Os checks de fase 1 vivem em `SqlChecks` (padrões no SQL) e
`ChangeChecks` (leem o modelo do Liquibase: autor, label, schema, nomes); os de fase 2 e 3 vivem em
`Phase2Checks` e `Phase3Checks`. O nome simples da classe aninhada **é** o código do catálogo.

**Código e catálogo andam juntos.** `CatalogueConsistencyTest` lê `docs/08` e falha se um
check implementado não existir lá, se um de fase 1 não estiver implementado, ou se a
severidade divergir. Check novo: implemente, registre em `BuiltinChecks`, e marque a fase no
catálogo — o seed se regenera dele. Cada check de SQL ganha uma linha **positiva e uma
negativa** em `SqlChecksTest`; a negativa é um falso positivo a menos, e falso positivo bloqueia deploy.

A severidade que vale é a do **pacote**, não a `defaultSeverity()` do check — o engine
sobrescreve. Um check devolve `defaultSeverity()` apenas como ponto de partida do catálogo.

**A IA é feature futura e nasce desligada** (D-13). `workspace.ai_enabled` é `false`, a
tabela `ai_analysis` existe vazia e os endpoints `/ai-analyses` respondem `LQM-060`.
Não implementar nas fases 0–3. O diagnóstico de falha da tela 02 usa só dados coletados.

## Changelogs do próprio banco

Ficam em `lqm-api/src/main/resources/db/changelog/`, em **formatted SQL** (o schema usa
`jsonb`, `text[]` e particionamento, que o XML abstrai mal).

Duas armadilhas do parser, ambas já custaram tempo:

1. **Comentário que vira diretiva.** O parser lê `--<palavra>` com espaços opcionais e em
   qualquer indentação. Um comentário como `-- Changesets e seu estado` ou
   `  -- rollback explícito` é interpretado como diretiva e o resto da linha vira SQL. O
   erro aparece longe da causa.
2. **Rollback com mais de um statement.** A linha de `--rollback` é quebrada no `;`, e
   `splitStatements:false` não vale para ela. Um `do $$ ... $$` com `;` dentro estoura com
   "Unterminated dollar quote".

**Todo changeset tem rollback explícito.** O LiquiManager aplica em si mesmo o
`RollbackRequired` que cobra dos outros. O CI roda `update → rollback total → update` (o teste
`SchemaRoundTripIntegrationTest`, num banco vazio criado para ele); se o seu changeset não reverte,
o pipeline quebra.

## Seed de checks

`seed/003-checks.sql` espelha o catálogo de `docs/08-motor-de-politicas.md` (51 configuráveis em 7 categorias + 4 de processo). Mudou o
catálogo, atualize o seed no mesmo commit; o `CatalogueConsistencyTest` confere o código contra o catálogo.

## Frontend

Tokens em `src/styles/theme.css`, derivados de `docs/10-design-system.md`. **Nenhum hex
solto nos componentes** — se a cor não está no `@theme`, ela não existe. Largura alvo
1600 px, mínimo 1366. Interface em pt-BR; termos do Liquibase (`update`, `rollback`,
`changeset`, `drift`, `tag`) ficam em inglês e em mono.

Status nunca é só cor (RNF-13, WCAG 2.1 AA): todo badge carrega texto.

**Storybook** (`npm run storybook`, porta 6006) cataloga `KpiCard`, `StatusBadge`, `EnvBadge`, `CommandChip` e `DataTable`, com o mesmo provedor de
textos e os mesmos tokens do app. Componente novo de `src/components/` ganha um `*.stories.tsx` ao lado; o CI roda `build-storybook`.

Formatação vive em `src/lib/format.ts` — use essas funções em vez de `toLocaleString`
solto, para que datas e durações fiquem iguais em todas as telas. Elas recebem o locale
por parâmetro; pegue o ativo com `useLocale()`.

**Nenhum texto cravado no componente.** Toda string visível sai de `src/i18n` via
`useMessages()`. Há dois catálogos, `pt.ts` e `en.ts`, mas só pt-BR é oferecido —
`AVAILABLE_LOCALES` lista apenas ele (RNF-12). O catálogo em inglês existe para que a
estrutura seja exercitada por mais de um idioma desde já; chave nova entra nos dois, e o
TypeScript quebra se faltar em um. O `pt.ts` define a forma do catálogo e **não** leva
`as const`: isso tornaria cada valor um tipo literal e o `en.ts` não compilaria.

## Decisões

`docs/12-decisoes-pendentes.md` é o registro. D-01 a D-24 estão fechadas (D-12 foi
descartada; os IDs não são renumerados, porque outras páginas citam D-13, D-17 e D-18).
Decisão nova entra como D-25 em diante, no mesmo commit que atualiza os documentos
afetados.
