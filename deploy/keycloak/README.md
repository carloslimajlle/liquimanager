# Keycloak do ambiente local (D-03)

O realm `liquimanager` é importado de [`liquimanager-realm.json`](liquimanager-realm.json) na primeira subida do contêiner
(`start-dev --import-realm`). Como o Keycloak local guarda os dados dentro do contêiner, recriá-lo apaga o que foi feito pela
interface: **o arquivo é a fonte da verdade**. Para mudar o realm, altere-o no Keycloak, exporte e atualize o arquivo.

## Ligar o login

```bash
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.oidc.yml up -d --build
cd lqm-web && npm run dev:oidc
```

O `docker-compose.oidc.yml` aponta a API para o emissor do realm, exige que o token tenha `lqm-api` na audiência e faz de
`admin.lqm` administrador de bootstrap (todas as permissões, para a primeira concessão). Sem esse arquivo a API continua na cadeia
de desenvolvimento: nada é exigido e `X-LQM-User` representa um usuário.

## Clients

| Client | Tipo | Para quê |
|--------|------|----------|
| `lqm-web` | público, Authorization Code + PKCE (S256), sem segredo | A SPA. Redireciona de volta para `localhost:5173` e `localhost:5180`. Seus tokens levam o claim `groups` e a audiência `lqm-api`. |
| `lqm-api` | só portador (bearer-only) | O servidor de recursos: só valida tokens, ninguém faz login por ele. |

Coletores e pipelines não usam o Keycloak: autenticam com **tokens de serviço** `lqm_…` (tela Tokens).

## Grupos e usuários de desenvolvimento

| Usuário | Grupo | Observação |
|---------|-------|------------|
| `admin.lqm` | `lqm-admins` | Administrador de bootstrap (ver acima). |
| `dba.demo` | `dbas` | Sem permissão alguma até receber um binding. |
| `dev.demo` | `developers` | Idem. |

A senha dos três é `lqm-dev-2026`, só para o ambiente local; ela está no próprio arquivo do realm e **não deve existir** em
HML/PRD, onde o realm é o do SSO da organização.

Quem entra, e cada grupo do token, vira `principal` sozinho (docs/09 § 8.0). Isso **não dá permissão alguma**: para um grupo
valer, entre como `admin.lqm`, abra Acessos e Permissões e ligue o grupo a um papel.
