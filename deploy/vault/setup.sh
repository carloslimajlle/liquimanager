#!/usr/bin/env bash
# Prepara o Vault de desenvolvimento (profile "vault"): KV v2, um segredo de exemplo, uma politica e um AppRole.
set -euo pipefail

VAULT="docker exec -e VAULT_ADDR=http://127.0.0.1:8200 -e VAULT_TOKEN=lqm-dev-root lqm-vault vault"

# O modo dev ja monta o KV v2 em secret/; so garante.
$VAULT secrets list | grep -q '^secret/' || $VAULT secrets enable -path=secret kv-v2
$VAULT kv put secret/lqm/pocdev username=poc password=poc >/dev/null

printf 'path "secret/data/lqm/*" {\n  capabilities = ["read"]\n}\n' | docker exec -i -e VAULT_ADDR=http://127.0.0.1:8200 -e VAULT_TOKEN=lqm-dev-root lqm-vault vault policy write lqm-read - >/dev/null

$VAULT auth list | grep -q '^approle/' || $VAULT auth enable approle
$VAULT write auth/approle/role/lqm token_policies=lqm-read token_ttl=1h token_max_ttl=4h >/dev/null

ROLE_ID=$($VAULT read -field=role_id auth/approle/role/lqm/role-id)
SECRET_ID=$($VAULT write -f -field=secret_id auth/approle/role/lqm/secret-id)
echo "LQM_SECRETS_VAULT_ROLE_ID=$ROLE_ID"
echo "LQM_SECRETS_VAULT_SECRET_ID=$SECRET_ID"
