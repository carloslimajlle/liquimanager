#!/usr/bin/env bash
# Prepara a POC do zero depois que o GitLab CE estiver respondendo:
# cria PAT do root, registra o runner, cria o projeto, envia o main e abre o MR.
#
#   bash deploy/gitlab-poc/setup-poc.sh
#
# Idempotente no que dá: reexecutar reaproveita token, runner e projeto existentes.
set -euo pipefail

GITLAB_URL="${GITLAB_URL:-http://localhost:8929}"
RUNNER_URL="${RUNNER_URL:-http://host.docker.internal:8929}"
PAT="${PAT:-glpat-poc-liquibase-token1}"
PROJECT="${PROJECT:-liquibase-poc}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${WORK:-$HERE/.work}"

api() { curl -sS --header "PRIVATE-TOKEN: $PAT" "$@"; }

echo "==> 1/6 Esperando o GitLab responder em $GITLAB_URL"
for i in $(seq 1 120); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$GITLAB_URL/users/sign_in")" = "200" ] && break
  sleep 10
done

echo "==> 2/6 Criando personal access token do root"
docker exec -i gitlab gitlab-rails runner - <<RUBY
u = User.find_by_username('root')
u.personal_access_tokens.where(name: 'poc-setup').delete_all
t = u.personal_access_tokens.build(scopes: ['api'], name: 'poc-setup', expires_at: 365.days.from_now)
t.set_token('$PAT')
t.save!
puts "token ok"
RUBY

echo "==> 3/6 Criando runner de instância e registrando"
TOKEN=$(docker exec -i gitlab gitlab-rails runner - <<'RUBY'
existing = Ci::Runner.instance_type.find_by(description: 'poc-docker')
puts existing ? existing.token : begin
  r = Ci::Runner.new(runner_type: :instance_type, description: 'poc-docker',
                     tag_list: ['docker'], run_untagged: true)
  r.save!
  r.token
end
RUBY
)
TOKEN=$(echo "$TOKEN" | tr -d '\r' | tail -1)

docker exec gitlab-runner gitlab-runner register \
  --non-interactive \
  --url "$RUNNER_URL" \
  --token "$TOKEN" \
  --name poc-docker \
  --executor docker \
  --docker-image alpine:latest \
  --docker-extra-hosts "host.docker.internal:host-gateway" \
  --docker-pull-policy if-not-present
docker restart gitlab-runner >/dev/null

echo "==> 4/6 Criando o projeto $PROJECT"
api --request POST "$GITLAB_URL/api/v4/projects" \
    --data "name=$PROJECT&path=$PROJECT&visibility=private&initialize_with_readme=false" \
    -o /dev/null -w "  http %{http_code}\n" || true
PID=$(api "$GITLAB_URL/api/v4/projects?search=$PROJECT" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
echo "  project id = $PID"

echo "==> 5/6 Enviando o main (changeset 001) e a branch do MR (changeset 002)"
rm -rf "$WORK"
mkdir -p "$WORK"
cp -r "$HERE/sample-project/." "$WORK/"
cd "$WORK"
MR_CHANGE="db/changelog/changes/002-create-pedido.yaml"
mv "$MR_CHANGE" ../002.hold

git init -q -b main
git config user.email "root@example.com"
git config user.name "Administrator"
git add -A
git commit -q -m "chore: estrutura do changelog com o changeset 001"
git remote add origin "http://root:$PAT@localhost:8929/root/$PROJECT.git"
git push -q -u origin main

git checkout -q -b feat/pedido
mv ../002.hold "$MR_CHANGE"
git add -A
git commit -q -m "feat(db): changeset 002 cria tabela pedido com FK para cliente"
git push -q -u origin feat/pedido

echo "==> 6/6 Abrindo o merge request"
api --request POST "$GITLAB_URL/api/v4/projects/$PID/merge_requests" \
    --data "source_branch=feat/pedido&target_branch=main&title=feat(db): changeset 002 cria tabela pedido" \
    --data-urlencode "description=POC: valida a migration Liquibase no MR. Veja o artefato migration.sql do job update-sql." \
    -o /dev/null -w "  http %{http_code}\n"

echo
echo "Pronto. GitLab: $GITLAB_URL/root/$PROJECT/-/merge_requests"
echo "Login: root / \$GITLAB_ROOT_PASSWORD do compose"
