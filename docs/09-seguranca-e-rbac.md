# 09 — Segurança e RBAC

## 1. Autenticação

- Usuários: OIDC (Authorization Code + PKCE) no SPA; a API valida JWT (Spring Security Resource Server).
  Grupos vêm do claim `groups` e são sincronizados como `principal` do tipo GROUP (D-03).
- Coletores/pipelines: **tokens de serviço** `lqm_<prefix8>_<secret32>`; armazenados com Argon2id; escopos e projetos limitados; expiração obrigatória (máx. 1 ano).
- Conta break-glass local (1 usuário) com MFA TOTP, desabilitada por padrão, todo uso auditado (§ 8.4).

## 2. Permissões

Formato `recurso:ação[:alvo]`. Escopo do binding: WORKSPACE, PROJECT, CONNECTION, PIPELINE ou ENVIRONMENT.

| Permissão | Permite |
|-----------|---------|
| `assignment:view` | Ver tudo no escopo (somente leitura) |
| `assignment:edit` | Editar catálogo no escopo |
| `assignment:admin` | Gerenciar acessos no escopo |
| `deploy:<env>` | Executar update/flow pela UI/runner naquele ambiente (ex.: `deploy:dev,tst`) |
| `promote:<env>` | Promover para o ambiente |
| `rollback:<env>` / `rollback:approve` | Solicitar / aprovar rollback |
| `policy:edit` / `policy:approve` | Editar checks e pacotes / aprovar alterações de política |
| `exception:request` / `exception:approve` | Solicitar / aprovar exceções |
| `drift:run` / `drift:accept` | Rodar drift sob demanda / aceitar itens |
| `audit:export` | Exportar auditoria |
| `connection:edit` / `secret:view-status` | Editar conexões / ver status de segredos (nunca o valor) |
| `catalog:edit` | Criar/editar times, projetos e changelogs |
| `pipeline:edit` | Criar/editar pipelines e estágios |
| `ai:use` | Usar análise por IA (feature futura; sem efeito enquanto `ai_enabled = false`) |
| `workspace:admin` | Configurar workspace, ambientes, notificações, retenção e IA |
| `token:manage` | Gerenciar tokens de serviço |
| `checks:read` / `ingest` | Escopos exclusivos de **tokens de serviço** (ler políticas efetivas / enviar operações) |

Negações explícitas (`denied_permissions`) prevalecem sobre concessões (ex.: "nega todas as leituras" do print 08).

## 3. Papéis (templates) {#papéis}

| Template | Permissões |
|----------|-----------|
| Admin do cliente | `assignment:admin`, `workspace:admin`, `catalog:edit`, `pipeline:edit`, `connection:edit`, `token:manage`, `audit:export`, todas as `:view` |
| DBA | `assignment:view`, `deploy:*`, `rollback:*`, `rollback:approve`, `exception:approve`, `drift:*`, `connection:edit` |
| Desenvolvedor | `assignment:view`, `deploy:dev,tst`, `exception:request`, `drift:run` |
| Release Manager | `assignment:view`, `promote:hml,prd`, `exception:request` |
| Revisor de segurança | `assignment:view`, `policy:edit`, `policy:approve`, `secret:view-status`, `audit:export` |
| Leitura | `assignment:view` |
| Break-glass | `rollback:prd`, `deploy:prd` — sempre com `expires_at` ≤ 24 h |

## 4. Segregação de funções (SoD) — regras verificadas no servidor

| Regra | Implementação |
|-------|---------------|
| SoD-1 | Quem é autor de um changeset não aprova exceção nem rollback desse changeset |
| SoD-2 | Quem solicita rollback/exceção/promoção/alteração de política não aprova a própria solicitação (check no banco) |
| SoD-3 | "Revisores não fazem deploy · quem faz deploy não aprova": um binding não pode ter `deploy:prd` e `exception:approve` simultaneamente no mesmo escopo — a UI avisa, a API recusa com `LQM-010` |
| SoD-4 | Concessões em escopo PRD exigem `approver` e `expires_at` (máx. 90 dias) |
| SoD-5 | Toda alteração de acesso gera `audit_event` com before/after |

## 5. Segredos

- Provedor `ENV` (**apenas desenvolvimento**): `secret_ref` nomeia um prefixo de variável de ambiente, e `<PREFIXO>_USERNAME`/`<PREFIXO>_PASSWORD` guardam os valores. Existe para exercitar "testar conexão" sem um Vault; não consta na lista de provedores de produção (Vault, Consul KV, Azure Key Vault) e não deve ser usado em HML/PRD.
- `db_connection.secret_ref` aponta para o provedor; a API/worker/runner resolvem em memória no momento do uso (sem cache em disco; cache em memória ≤ 5 min).
- Painel "Gestão de segredos" (tela 08): conexão, provedor, idade da última rotação (vermelho/laranja > 14 dias, configurável),
  e "N em changelogs" = achados do check `SecretInChangelog` no workspace.
- O Vault entra com token estático ou, em produção, com **AppRole** (§ 8.5): a API faz o login sozinha, renova o token e entra de novo se for recusada.
- Nunca logar JDBC URL com `password=`; sanitizador de logs obrigatório nos três coletores.

## 6. Auditoria

Eventos mínimos: login, `access.grant/revoke`, `policy.create/update/disable`, `package.*`, `exception.request/approve/reject/revoke/expire`,
`rollback.request/approve/execute`, `connection.create/update/test`, `token.create/revoke`, `drift.accept`, `ai.analyze`, `audit.export`.
Painel "Evidências para auditoria" mostra as últimas 24 h. Export CSV/PDF assinado (hash do arquivo registrado no próprio log).

## 7. Ameaças e mitigação (resumo)

| Ameaça | Mitigação |
|--------|-----------|
| Token de coletor vazado permite forjar operações | Escopo `ingest` só escreve operações do(s) projeto(s) do token; rotação; alerta de uso por IP novo |
| Usuário de DEV executa em PRD | Permissões por ambiente + SoD + runner só aceita jobs aprovados |
| Adulteração da auditoria | Append-only + hash encadeado + verificação diária + export periódico |

## 8. Estado da implementação (fase 2, E11)

**Aplicado:** todas as rotas de usuário passam por `RbacRules` (uma tabela) + `RbacInterceptor`. Negação vence concessão, de qualquer
binding. Bindings vencidos não valem. `GET /me` devolve o que o chamador de fato tem.

**SoD no servidor:** SoD-1 e SoD-2 nas exceções (E10); **SoD-3** na concessão (`LQM-010`), a menos que o escopo seja um ambiente
que não é de produção; **SoD-4** exige aprovador e prazo (90 dias; break-glass 24 h) em escopo de produção; **SoD-5**, todo grant e
revogação gera `audit_event` com before/after.

**Feito:** permissão por escopo de CONNECTION, PIPELINE e ENVIRONMENT (§ 8.2), **leitura filtrada por escopo** (§ 8.3), a conta
break-glass com MFA (§ 8.4) e a sincronização de principais com o Keycloak (§ 8.0). O `X-LQM-Workspace` ausente vale o workspace
`default` por decisão (D-23): não há leitura que junte vários workspaces.

**Limites que restam:**

- No dashboard de quem lê só parte do workspace, as faixas de Políticas e de Drift, que somam tudo numa tabela diária, não aparecem (o cabeçalho e os indicadores de deploy valem só para o escopo).
- **Ambientes e times são um catálogo comum** (D-24): quem pode ler algo em algum escopo vê a lista inteira deles. São nomes (DEV, HML, PRD; os times), não dados de nenhum projeto; as telas precisam deles para mostrar o ambiente de uma linha que a pessoa pode ler.
- Criar um pedido de exceção (`POST /policy-exceptions`) ainda exige `exception:request` no workspace inteiro: o escopo do pedido vem do corpo, não do caminho.
- O feed ao vivo calcula o que a pessoa pode ler quando ela conecta; uma mudança de binding vale na próxima conexão (até 30 min). Com mais de uma réplica, cada uma calcula para os seus assinantes (docs/04, "mais de uma réplica").
- `lqm.security.bootstrap-admins` (lista de nomes) tem todas as permissões, para a primeira concessão de uma instalação nova.

### 8.0 Login com Keycloak

**Aplicado:** a SPA entra por OIDC Authorization Code + PKCE (S256, cliente público `lqm-web`, sem segredo), com `oidc-client-ts`; a
sessão fica em `sessionStorage` e se renova com o refresh token, e uma renovação que falha leva a um novo login. Toda chamada leva
`Authorization: Bearer`; o feed ao vivo (SSE) é lido com `fetch` (o `EventSource` não envia cabeçalho) e os downloads de CSV também
(`fetch` + blob). A API valida o JWT como servidor de recursos, confere o emissor, a audiência `lqm-api` e usa `preferred_username`,
`email` e `groups`. O realm (clients, grupos e usuários de desenvolvimento) está em `deploy/keycloak/liquimanager-realm.json`; veja
`deploy/keycloak/README.md` e `deploy/docker-compose.oidc.yml`. Sem `VITE_OIDC_AUTHORITY` o front não pede login e, sem emissor, a
API segue na cadeia de desenvolvimento.

**Nome do chamador:** com JWT o chamador se chama pelo `preferred_username` (reserva: o `sub`), por `UsernameJwtConverter`. O Spring usa
o `sub`, que no Keycloak é um UUID, e é por `authentication.getName()` que a auditoria, o "criado por" e os SoD comparam e gravam: sem
isso eles guardariam UUIDs e a tela não reconheceria "o meu pedido".

**Principais sincronizados:** quem entra com um token (e cada grupo do claim `groups`) vira `principal` na primeira vez que a API
o vê (`PrincipalSync`, no `RbacInterceptor`, antes de qualquer regra), então o grupo aparece no seletor da tela de Acessos sem
ninguém digitar o nome. **Isso não concede nada**: um principal novo não tem binding, e um grupo novo continua sem ver nada até
alguém ligá-lo a um papel. O tipo de um principal cadastrado à mão não muda; só nome e e-mail se atualizam. A sincronização roda no
máximo a cada 5 minutos por pessoa e grupos, só vale para token JWT (o cabeçalho `X-LQM-User` do desenvolvimento não cria
principais) e só a **criação** é auditada (`access.principal.sync`).

**Botões conforme a permissão:** o front lê as permissões de `GET /me` e esconde o que a pessoa não pode fazer (criar, editar,
arquivar, promover, aprovar, aceitar drift, exportar auditoria). É conveniência: quem decide é a API, que responde 403 ao que
passar por um botão escondido. Enquanto `/me` não responde, tudo aparece, para a tela não piscar vazia. `/me` devolve a união dos
bindings de qualquer escopo, então um botão pode aparecer para uma ação que a API ainda nega num projeto específico.

Quem entra sem nenhum papel (sem binding nem grupo) vê, em vez de telas vazias, uma tela de **boas-vindas** (`NoAccess`): "você ainda não tem acesso", com o **login, o e-mail e os grupos** exatos
que o administrador precisa para conceder o acesso, e o botão **Pedir acesso** (com um recado opcional de até 500 caracteres). O pedido chega a **quem administra os acessos** (`assignment:admin`)
**só no sino** (a pendência "Pedido de acesso", ao vivo): não há e-mail nem Teams, nem evento de notificação. O pedido **não concede nada**: o administrador escolhe o papel e o escopo, e na tela
**Acessos e Permissões** o painel "Pedidos de acesso" tem **Conceder acesso** (o diálogo abre com a pessoa já escolhida) e **Dispensar**. Um pedido aberto por pessoa: pedir de novo em menos de 24 horas
responde com o que já está aberto; depois disso a tela oferece "Lembrar o administrador". O pedido se fecha sozinho quando a pessoa, ou um grupo dela, recebe qualquer papel. O padrão continua sendo **nenhum acesso** (menor privilégio). Conceder o papel ao **grupo** do
Keycloak é o jeito mais simples: quem entra no grupo herda o acesso (um principal de grupo nasce na primeira entrada de alguém dele). Qualquer 403 de consulta ou ação ainda mostra o aviso;
quem tem algum papel, por pouco que seja, não vê essa tela. Antes disso, a pessoa recebia 403 em tudo; para não ficar diante de telas vazias, qualquer 403 de consulta ou ação mostra no topo o aviso **"Permissão negada"** (`AccessDeniedBanner`, dispensável, volta no próximo 403). Um 403 não é repetido: a tela não espera três tentativas para avisar.

**Não coberto:** teste automatizado do caminho com JWT de verdade (a verificação foi manual: login, navegação, SSE e logout contra o
Keycloak local) e logout federado em todas as abas.
### 8.2 Escopos que decidem uma escrita

Um binding em PROJECT, CONNECTION, PIPELINE ou ENVIRONMENT vale para uma ação quando ela **trata daquele recurso**. `ScopeResolver` traduz
a rota (ou o recurso que o controller carrega) em um `ScopeTarget`, e `PermissionService.allowed` aplica os bindings cujo escopo está nele:

| A ação trata de | O alvo contém |
|-----------------|---------------|
| um projeto (`/projects/{id}/…`) | o projeto |
| uma conexão (`/connections/{id}/…`, drift, scanner, uma operação e seus rollbacks) | a conexão, o ambiente dela, os projetos ligados a ela e os pipelines que fazem deploy nela |
| um pipeline inteiro (`/pipelines/{id}`) | o pipeline e o seu projeto, **não** os ambientes nem as conexões dos estágios |
| **um estágio** (`…/stages/{id}`, promover, uma promoção) | o pipeline, o projeto e **o ambiente e a conexão desse estágio** |
| um ambiente (`/environments/{id}`) | o ambiente |

A linha do estágio é a que fecha uma brecha: `promote:prd` concedida no ambiente DEV **não** vale para promover a PRD, porque o alvo de
um estágio de PRD contém o ambiente PRD e não os outros do pipeline. Uma rota que o resolvedor não reconhece, ou um id que não existe, não trata
de nada em particular: só vale um binding do workspace inteiro (a resposta estrita). Negação continua vencendo, em qualquer escopo que se
aplique. A **leitura** segue como antes: `assignment:view` em algum escopo libera as listas (limite conhecido, § 8).



### 8.3 Leitura por escopo

Uma lista mostra só o que os bindings da pessoa cobrem, e o detalhe de um recurso responde 403 fora deles. A regra é a mesma da escrita
(§ 8.2): `ReadScope` avalia cada projeto, conexão e pipeline do workspace com o `ScopeTarget` que o `ScopeResolver` dá a ele. Um binding
de PROJECT cobre o projeto, as conexões ligadas a ele (ou usadas por pipelines dele), os pipelines e as operações dessas conexões; um de
CONNECTION cobre a conexão e suas operações; um de PIPELINE, o pipeline, o projeto e as conexões dos seus estágios; um de ENVIRONMENT, as
conexões do ambiente. Um binding do workspace inteiro lê tudo, e uma negação de leitura num escopo esconde o que ele cobre.

Filtradas: projetos, conexões, pipelines, operações (lista e KPIs), atividade do dashboard, pedidos de rollback, promoções, rodadas de
drift, **exceções de política** (as de um projeto são lidas por quem lê o projeto; uma sem projeto vale para o workspace e todos a leem), a **tela de Enforcement** (`/enforcement?pipeline=` responde 403 para um pipeline fora do escopo) e o feed ao vivo (SSE, por assinante). Decidir ou revogar uma exceção de um projeto passa a valer também para quem tem `exception:approve` naquele projeto. Um GET com id no caminho (`/projects/{id}`, `/connections/{id}`, `/pipelines/{id}`,
`/operations/{id}`, `/changesets/{id}`, promoções, drift) é julgado no escopo do recurso. Sem identidade (desenvolvimento sem emissor) e
bootstrap admins leem tudo. Código: `ReadScope`, `RbacInterceptor`.

### 8.4 Conta break-glass

Para quando o provedor de identidade cai e alguém ainda precisa agir. **Desligada por padrão** (`lqm.security.break-glass.enabled`); uma
conta só, e entrar exige os três juntos: o nome (`username`), a senha (`password-hash`, um hash com prefixo do Spring, por exemplo
`{bcrypt}$2a$12$...`) e um código TOTP de um autenticador (`totp-secret`, Base32, 80 bits ou mais). Ligada e mal configurada, a API **não
sobe**. `POST /auth/break-glass` com `{username, password, code}` devolve um token opaco `lqm_bg_...` (guardado só como hash) que vale
`lqm.security.break-glass.ttl` (1 h; nunca mais que 24 h) e é usado como `Authorization: Bearer`; `POST /auth/break-glass/logout` o revoga.

O que a impede de virar porta dos fundos: cinco tentativas erradas seguidas bloqueiam por 15 min, mesmo com as credenciais certas
(429, `LQM-081`); um código TOTP vale **uma vez** (o último passo aceito é guardado, então reusá-lo, ainda que ao mesmo tempo, falha); toda
recusa é a mesma resposta (401, `LQM-080`), sem dizer qual dos três errou; e **tudo é auditado**: `auth.break-glass.login`, `.failed`,
`.locked`, `.logout` e, para cada requisição feita com o token, `auth.break-glass.use` (método, caminho e status). Com o token a pessoa
tem todas as permissões, como um bootstrap admin, e é reconhecida pela forma como entrou, nunca pelo nome: um usuário do Keycloak
chamado `break-glass:...` não é a conta. Só existe na cadeia com emissor OIDC; na de desenvolvimento a rota responde 404 como se não
existisse. Para preparar a conta, gere um segredo TOTP em base32 (RFC 6238, SHA-1, 6 dígitos, 30 s), cadastre-o no autenticador e informe o mesmo valor em `lqm.security.break-glass.totp-secret`.
**Tela:** `/break-glass` fica fora do portão do OIDC (existe para quando o provedor está fora): conta, senha e o código de 6 dígitos; a recusa não diz qual dos três errou. O token
fica em `sessionStorage` (fechar a aba encerra) e vai como `Authorization: Bearer`; enquanto a sessão vale, **toda tela** mostra uma faixa vermelha ("Conta de emergência em uso", com a hora em que
termina) e o botão "Encerrar sessão", que revoga o token no servidor. Um 401 com o token de emergência leva de volta à tela de entrada. A URL não é linkada de lugar nenhum: quem precisa dela a conhece.

### 8.5 Vault com AppRole

O provedor `VAULT` lê KV v1 e v2. A autenticação é por token estático (`lqm.secrets.vault.token`, para experimentar) ou por **AppRole**
(`role-id` e `secret-id`, com `approle-path` se o método não está em `approle`): o `SecretResolver` faz o login sozinho, guarda o token
até 80% do lease, entra de novo quando o Vault responde 403 (token expirado ou revogado) e, se o login falha, é `LQM-050` com só o
status. `lqm.secrets.vault.namespace` envia `X-Vault-Namespace` (Vault Enterprise, HCP). Com `role-id` e `secret-id` preenchidos o token
estático é ignorado. Um Vault de desenvolvimento está em `deploy/vault` (profile `vault`), e o teste contra um Vault de verdade,
`VaultIntegrationTest`, roda com `LQM_VAULT_TEST_URL`.

**Segredos:** `SecretResolver` resolve em memória, no momento do uso, com cache de **no máximo 5 minutos** (um valor maior é
reduzido a 5). Provedores: `NONE`, `ENV` (só desenvolvimento), **`VAULT`** (KV v1 e v2, `lqm.secrets.vault.*`), **`CONSUL`**
(`lqm.secrets.consul.*`) e **`AZURE_KV`** (`lqm.secrets.azure.*`). O Azure recebe o token **por configuração**: trocar uma
identidade gerenciada por token não está implementado. Um erro do provedor é `LQM-050` e a mensagem só leva o status, nunca o corpo
da resposta. O painel (`GET /secrets/status`) mostra conexão, provedor e idade; a rotação acontece no cofre, e
`POST /secrets/{conexão}/rotated` avisa o LiquiManager (e limpa o cache). Nunca rotacionado conta como o pior caso.
Limiares: `lqm.secrets.rotation-warn-days` (14) e `rotation-danger-days` (30). "N em changelogs" = changesets distintos
acusados por `SecretInChangelog`.

**Auditoria:** uma **única** cadeia de hashes para a tabela inteira (uma cadeia por workspace deixaria apagar a cauda de um
sem que nada notasse). O hash é calculado sobre a forma canônica do JSON, com chaves ordenadas em todos os níveis, porque o
`jsonb` do Postgres não preserva o texto. Escritas se serializam num lock consultivo; não audite o que é quente (a ingestão não é
auditada). `GET /audit`, `/audit/verify` e `/audit/export` (CSV ou PDF). **"Assinado" aqui é o hash, não um certificado**: cada export
registra um evento `audit.export` com o SHA-256 dos bytes entregues, para casar uma cópia do arquivo com o log. O PDF traz ainda o
veredito da cadeia no momento da geração e o hash do último evento listado, que sela os anteriores. Assinatura digital com
certificado (PAdES) não existe, e a primeira página do PDF diz isso. A verificação diária roda como
job (`audit-verify`, 03:00, `lqm.jobs.audit-verify-cron`) e grava o resultado em `job_run`; **o alerta de uma cadeia quebrada
só chega ao log**, o canal de notificação é o E19.

