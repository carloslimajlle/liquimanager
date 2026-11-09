# Vault local (modo dev)

Serve para exercitar o provedor `VAULT` do LiquiManager sem o Vault da empresa. O servidor roda **em memória**, já aberto, com o token
raiz `lqm-dev-root`: nada daqui serve para segredo de verdade.

```bash
docker compose -f deploy/docker-compose.yml --profile vault up -d vault
bash deploy/vault/setup.sh
```

O `setup.sh` liga o KV v2 em `secret/`, grava um segredo de exemplo em `secret/lqm/pocdev` (`username=poc`, `password=poc`), cria uma
política de leitura só de `secret/data/lqm/*` e um AppRole `lqm` que a usa, e imprime o `role_id` e o `secret_id`.

## Ligar a API

Com token estático (o jeito mais curto, para experimentar):

```
LQM_SECRETS_VAULT_URL=http://host.docker.internal:8200
LQM_SECRETS_VAULT_TOKEN=lqm-dev-root
```

Com AppRole (o que se usa em produção: a API entra sozinha, renova o token antes de vencer e entra de novo se o Vault o recusar):

```
LQM_SECRETS_VAULT_URL=http://host.docker.internal:8200
LQM_SECRETS_VAULT_ROLE_ID=<role_id impresso pelo setup>
LQM_SECRETS_VAULT_SECRET_ID=<secret_id impresso pelo setup>
# LQM_SECRETS_VAULT_APPROLE_PATH=approle        (se o método foi montado em outro caminho)
# LQM_SECRETS_VAULT_NAMESPACE=time-a/lqm        (Vault Enterprise / HCP)
```

Na conexão, `secret_provider = VAULT` e `secret_ref = secret/data/lqm/pocdev`. Se o `role_id` e o `secret_id` estiverem preenchidos, valem
eles; o token estático só é usado sem AppRole.

O teste contra um Vault de verdade, `VaultIntegrationTest`, só roda com `LQM_VAULT_TEST_URL` (e `LQM_VAULT_TEST_ROLE_ID` /
`LQM_VAULT_TEST_SECRET_ID`).
