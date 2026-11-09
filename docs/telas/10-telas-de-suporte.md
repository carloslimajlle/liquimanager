# Tela 10 — Telas de suporte (cadastros e configurações)

Estas telas não aparecem no datasheet. Seguem o mesmo shell e design system (sidebar, topbar, h1, cards, `DataTable`).
Os wireframes abaixo são a referência; não há print alvo — usar os componentes das telas 01–09.

| Rota | Tela | Requisito | Permissão |
|------|------|-----------|-----------|
| `/admin/projects` | Projetos | RF-CAD-02/03/07 | `catalog:edit` |
| `/admin/connections` | Conexões de banco | RF-CAD-05 | `connection:edit` |
| `/admin/environments` | Ambientes | RF-CAD-04 | `workspace:admin` |
| `/admin/pipelines` | Pipelines | RF-CAD-06 | `pipeline:edit` |
| `/admin/tokens` | Tokens de serviço | RF-ADM-03 | `token:manage` |
| `/admin/notifications` | Regras de notificação | RF-INT-05 | `workspace:admin` |
| `/admin/audit` | Auditoria completa | RF-ADM-04 | `audit:export` |
| `/settings` | Workspace (retenção, limites de exceção, IA on/off — off por padrão) | — | `workspace:admin` |

## Projetos

```
Administrar › Projetos                                        [ + Novo projeto ]
Projetos
┌KPI Projetos 12┐┌KPI Changelogs 14┐┌KPI Com coletor 9 de 12┐┌KPI Sob política 12 de 12┐
┌──────────────────────────────────────────────────────────────────────────────┐
│ PROJETO   TIME        REPOSITÓRIO            CHANGELOGS  CONEXÕES  COLETOR  ÚLTIMA OP. │
│ gate      Gate        backend/gate           1           4         starter  16:07 ✕   │
│ auditoria Auditoria   backend/auditoria      1           4         starter  15:47 ✓   │
└──────────────────────────────────────────────────────────────────────────────┘
```
Detalhe do projeto: abas `Geral`, `Changelogs` (caminho, formato, pacote), `Conexões`, `Coletor` (snippet de configuração
pronto para copiar com `lqm.project` e token), `Atividade`.

## Conexões de banco

```
Administrar › Conexões                                         [ + Nova conexão ]
┌──────────────────────────────────────────────────────────────────────────────────────┐
│ CONEXÃO        AMBIENTE  ENGINE      VERSÃO        SEGREDO          DRIFT     SCANNER  TESTE │
│ Core PRD   PRD       SQL Server  16.0.4135     Vault · 2d       6/6h ✓    15 min   ✓ 2h  │
└──────────────────────────────────────────────────────────────────────────────────────┘
```
Formulário: Nome, Slug (connectionKey), Ambiente, Engine, JDBC URL (validação: recusa `password=`/`user=` na URL),
Schema padrão, Provedor de segredo + caminho, Projetos vinculados, Drift (on/off + cron + referência D-06), Scanner (on/off + intervalo).
Botão `Testar conexão` → mostra versão do banco e latência, ou erro.

## Ambientes

Lista ordenável (drag-and-drop) com código, nome, cor (seletor dos tokens `env-*`), `Produção?`, `Exige aprovação?`.
Pré-visualização do `EnvBadge`.

## Pipelines

Editor de estágios em linha (cards com setas, como os gates da tela 06): para cada estágio escolher ambiente, conexão,
pacote de políticas, exige aprovação. Integração GitLab: id do projeto e ref do trigger.

## Tokens de serviço

Tabela: nome, prefixo (`lqm_a1b2c3d4…`), escopos, projetos, criado por, último uso, expira em, `Revogar`.
Criação mostra o token **uma única vez** com botão copiar e aviso.

## Critérios de aceite gerais

- [ ] Todas usam os mesmos componentes do Storybook.
- [ ] Toda gravação gera `audit_event`.
- [ ] Formulários com validação inline e mensagens em pt-BR.

## Estado da implementação: notificações (E19)

`/admin/notifications` (`workspace:admin`): regras (nome, evento, canal, destino, escopo por ambiente e por projeto, ativa), `Testar` (envia
na hora e diz se chegou), `Editar`, `Excluir`, e os últimos envios com status e tentativas.

- **Eventos:** `PRD_FAILURE` (falha de um deploy em ambiente de produção), `GATE_BLOCKED`, `DRIFT_DETECTED`, `EXCEPTION_EXPIRING`
  (exceção aprovada que vence em até 3 dias, avisada uma vez), `ROLLBACK_PENDING` (só em produção, onde alguém precisa aprovar) e
  `SYSTEM_ALERT` (cadeia de auditoria adulterada; lock do Liquibase preso há mais de 15 minutos).
- **Fila:** o evento só **enfileira**; um job a cada 30 segundos envia. Falha tenta de novo com espera crescente (2, 4, 8 e 16 minutos),
  e desiste na quinta, quando aparece como `falhou`. O motivo guardado é só o status, nunca a resposta nem a URL do endpoint. Uma mesma
  coisa vista duas vezes é avisada **uma** vez por regra (`dedupe_key`).
- **Canais:** e-mail pelo serviço interno (`lqm.notifications.email.url`; o formato do corpo `{to, subject, body}` e o token são uma
  **suposição**, porque o contrato real do serviço `email` não estava disponível), Teams (message card) e Slack, por webhook **https**.
  O webhook é uma credencial: depois de salvo, a API só devolve o host, e editar a regra sem informar o destino mantém o atual.
- Os textos das mensagens vão **sem acentos**, porque o serviço de e-mail e alguns webhooks os estragam.
- `lqm.public-url` (opcional) vira o link para a operação dentro da mensagem.
**Severidade, agrupamento e silêncio (por regra):** `Severidade mínima` só avisa eventos daquela severidade para cima (vale para os que trazem uma: o gate bloqueado traz a do pior achado; os demais são sempre avisados, o que não se sabe julgar não se esconde); `Agrupamento` junta o que se acumula numa janela de 5 min a 24 h numa só mensagem (as janelas são alinhadas à época, então toda réplica concorda); `Horário de silêncio` (de, até, fuso) **segura** a mensagem até o fim da janela, que pode cruzar a meia-noite, e não a descarta. O silêncio vale para toda mensagem da regra: para o que é urgente, uma regra à parte sem silêncio. Código: `QuietHours`, `NotificationService`, `NotificationJobs`.

## Estado da implementação: cadastros (fase de acabamento)

Rotas entregues: `/admin/projects`, `/admin/connections`, `/admin/environments`, `/admin/pipelines`, `/admin/tokens`,
`/admin/audit` e `/settings`, todas no shell e com as abas de `AdminTabs`. Elas falam com os endpoints de cadastro que já existiam
(agora no contrato, tag `Catalogue`).

- **Projetos:** lista e formulário (slug, nome, time, repositório, branch, descrição; um time novo pode ser criado ali mesmo). Abaixo
  da lista, os changelogs do projeto selecionado, cada um com o **pacote de políticas** que o governa: trocar o pacote usa
  `POST /policy-packages/{id}/assign|unassign`, a via auditada. Editar um changelog reenvia o pacote atual, porque o `PUT` grava o que
  recebe e omiti-lo desatribuiria o pacote.
- **Conexões:** lista com ambiente, engine, versão, provedor de segredo, drift, scanner e último teste; formulário com a JDBC URL
  (a API recusa `user=`/`password=`), provedor e referência do segredo, drift e scanner. `Testar conexão` mostra versão e latência,
  ou o erro.
- **Ambientes:** código, nome, ordem, cor, produção e aprovação, com prévia do `EnvBadge`.
- **Pipelines:** lista com o gatilho do GitLab (`gitlabProjectId` e ref, os que o botão Promover usa) e, abaixo do selecionado, os
  **estágios**: ambiente, conexão (só as do ambiente escolhido; a API exige uma), pacote de políticas e aprovação.
- **Tokens:** lista com prefixo, escopos, projetos, último uso e estado (ativo, revogado, expirado); criação com escopos, projetos e
  validade; o valor aparece **uma única vez**, num diálogo próprio. Revogar pede confirmação.
- **Auditoria:** a trilha inteira, filtrável por autor e ação, paginada, com a verificação da cadeia de hashes e o export CSV.
- **Configurações:** o workspace `default` (nome, slug, criação, IA desligada por D-13) e a sessão (`GET /me`: usuário, grupos,
  permissões).

**Toda gravação gera `audit_event`**, com antes e depois: `team.*`, `project.create|update|archive`, `changelog.create|update`,
`connection.create|update|test|archive`, `environment.create|update`, `pipeline.*`, `pipeline-stage.*`, `token.create|revoke`. A
trilha nunca leva o valor de um token nem a mensagem de erro do driver de um teste de conexão.

As listas passam inteiras do servidor e são paginadas no cliente (25 por página): um workspace tem dezenas de projetos, mas pode ter
centenas de conexões.

**Não implementado:**

- O editor de estágios em cards com setas: os estágios são editados numa tabela, com o pacote de políticas escolhido na própria linha.
- O liga/desliga da IA em `/settings`: de propósito, a IA é feature futura e nasce desligada (D-13). Retenção e prazos já se salvam
  (`PUT /workspaces/{id}/settings`).

**Entregue depois:** a lista de projetos (`/projects`) traz os KPIs (projetos, changelogs, com coletor, sob política) e as colunas
changelogs, conexões, coletor e última operação (`GET /projects/overview`, só dos projetos que a pessoa pode ler); o detalhe do projeto
(`/admin/projects/:id`) ganhou as abas `Coletor` (snippet do starter e das variáveis do pipeline, prontos para copiar, e os tokens que valem
para o projeto — nunca o segredo) e `Atividade` (últimas operações); o formulário da conexão tem os **projetos vinculados** (`GET` e `PUT`
da conexão levam `projectIds`); as conexões e os ambientes já se reordenam arrastando, com setas.
- Esconder o que a permissão não deixa fazer está feito (ver docs/09 § 8.0), mas decide pela união dos escopos de `/me`: uma
  permissão só num projeto mostra o botão em todos, e a API recusa onde não vale.
