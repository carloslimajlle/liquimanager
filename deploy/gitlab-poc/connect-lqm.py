#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Connects the GitLab POC to a LOCAL LiquiManager, so its three integrations run against a real GitLab:

  * Developer Mode reads the changelog files of the project through the repository API;
  * the Promote button fires the trigger pipeline of the project;
  * `lqm checks run` posts its summary on the merge request.

    python deploy/gitlab-poc/connect-lqm.py setup      # creates the trigger token, writes .lqm/lqm.env
    docker compose -f deploy/docker-compose.yml -f deploy/gitlab-poc/docker-compose.lqm.yml \\
        --env-file deploy/gitlab-poc/.lqm/lqm.env up -d api
    python deploy/gitlab-poc/connect-lqm.py register   # project, changelog, connections and pipeline in LiquiManager
    python deploy/gitlab-poc/connect-lqm.py verify     # reads the files, promotes, and checks what GitLab received
    python deploy/gitlab-poc/connect-lqm.py ci         # CI variables of the project, so `lqm checks run` works in a job
    python deploy/gitlab-poc/connect-lqm.py ci --token lqm_...   # the same, with a token you issued (API with sign-in on)
    python deploy/gitlab-poc/connect-lqm.py deploy-job # the job that deploys what a promotion asks for (end-to-end test)
    python deploy/gitlab-poc/connect-lqm.py webhook    # GitLab tells LiquiManager when a merge request is closed or reopened

The API must be on the development chain (no OIDC), because this script does not sign in. Local use only: the personal
access token is the one `setup-poc.sh` creates for the POC root user.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
# NOT `.work`: that is the clone setup-poc.sh deletes and recreates.
WORK = os.path.join(HERE, '.lqm')
GITLAB = os.environ.get('GITLAB_URL', 'http://localhost:8929')
PAT = os.environ.get('PAT', 'glpat-poc-liquibase-token1')
PROJECT = os.environ.get('PROJECT', 'liquibase-poc')
LQM = os.environ.get('LQM_URL', 'http://localhost:8080').rstrip('/') + '/api/v1'
# The address the API container uses for GitLab (the compose external_url), and the one stored as the repository URL.
GITLAB_FOR_API = os.environ.get('GITLAB_FOR_API', 'http://host.docker.internal:8929')


def http(method, url, body=None, headers=None, form=None):
    data = None
    headers = dict(headers or {})
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers['Content-Type'] = 'application/x-www-form-urlencoded'
    elif body is not None:
        data = json.dumps(body).encode()
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read().decode()
            return response.status, (json.loads(raw) if raw[:1] in ('[', '{') else raw)
    except urllib.error.HTTPError as error:
        raw = error.read().decode()
        try:
            return error.code, json.loads(raw)
        except ValueError:
            return error.code, raw


def gitlab(method, path, **kwargs):
    return http(method, GITLAB + '/api/v4' + path, headers={'PRIVATE-TOKEN': PAT}, **kwargs)


def lqm(method, path, body=None):
    return http(method, LQM + path, body=body)


def must(result, *accepted):
    status, body = result
    if status not in accepted:
        sys.exit('unexpected %s: %s' % (status, body))
    return body


def project_id():
    projects = must(gitlab('GET', '/projects?search=' + PROJECT), 200)
    found = [p for p in projects if p['path'] == PROJECT]
    if not found:
        sys.exit('project %s not found in GitLab; run deploy/gitlab-poc/setup-poc.sh first' % PROJECT)
    return found[0]['id']


def setup():
    pid = project_id()
    triggers = must(gitlab('GET', '/projects/%d/triggers' % pid), 200)
    trigger = next((t for t in triggers if t['description'] == 'liquimanager'), None)
    if trigger is None:
        trigger = must(gitlab('POST', '/projects/%d/triggers' % pid, form={'description': 'liquimanager'}), 201)
    os.makedirs(WORK, exist_ok=True)
    with open(os.path.join(WORK, 'lqm.env'), 'w', encoding='utf-8', newline='\n') as handle:
        handle.write('LQM_GITLAB_TRIGGER_TOKEN=%s\n' % trigger['token'])
    print('GitLab project id %d, trigger token written to .lqm/lqm.env (not committed)' % pid)


def find(items, key, value):
    return next((i for i in items if i.get(key) == value), None)


def register():
    pid = project_id()
    environments = must(lqm('GET', '/environments'), 200)
    env = {e['code']: e['id'] for e in environments}

    project = find(must(lqm('GET', '/projects?q=' + PROJECT), 200), 'slug', PROJECT)
    repo = '%s/root/%s.git' % (GITLAB_FOR_API, PROJECT)
    body = {'slug': PROJECT, 'name': 'Liquibase POC', 'repoUrl': repo, 'defaultBranch': 'main'}
    if project is None:
        project = must(lqm('POST', '/projects', body), 201)
    else:
        project = must(lqm('PUT', '/projects/%s' % project['id'], body), 200)

    changelogs = must(lqm('GET', '/projects/%s/changelogs' % project['id']), 200)
    changelog = find(changelogs, 'path', 'db/changelog/db.changelog-master.yaml')
    if changelog is None:
        changelog = must(lqm('POST', '/projects/%s/changelogs' % project['id'],
                             {'name': 'master', 'path': 'db/changelog/db.changelog-master.yaml', 'format': 'YAML'}), 201)

    connections = must(lqm('GET', '/connections'), 200)
    stage_connections = {}
    for code in ('DEV', 'HML'):
        slug = 'poc-%s' % code.lower()
        connection = find(connections, 'slug', slug)
        if connection is None:
            connection = must(lqm('POST', '/connections', {
                'environmentId': env[code], 'slug': slug, 'displayName': 'POC %s' % code, 'engine': 'POSTGRESQL',
                'jdbcUrl': 'jdbc:postgresql://localhost:5432/pocdb', 'scannerEnabled': False}), 201)
        stage_connections[code] = connection['id']

    pipeline = find(must(lqm('GET', '/pipelines'), 200), 'name', 'POC-GATE')
    pipeline_body = {'name': 'POC-GATE', 'projectId': project['id'], 'gitlabProjectId': pid, 'gitlabTriggerRef': 'main'}
    if pipeline is None:
        pipeline = must(lqm('POST', '/pipelines', pipeline_body), 201)
    else:
        pipeline = must(lqm('PUT', '/pipelines/%s' % pipeline['id'], pipeline_body), 200)
    stages = must(lqm('GET', '/pipelines/%s/stages' % pipeline['id']), 200)
    for order, code in enumerate(('DEV', 'HML'), start=1):
        if not find(stages, 'environmentId', env[code]):
            must(lqm('POST', '/pipelines/%s/stages' % pipeline['id'], {
                'sortOrder': order, 'environmentId': env[code], 'dbConnectionId': stage_connections[code],
                'requiresApproval': False}), 201)

    state = {'project': project['id'], 'changelog': changelog['id'], 'pipeline': pipeline['id'], 'gitlabProject': pid}
    os.makedirs(WORK, exist_ok=True)
    with open(os.path.join(WORK, 'lqm-state.json'), 'w', encoding='utf-8') as handle:
        json.dump(state, handle)
    print('registered:', json.dumps(state))


def verify():
    with open(os.path.join(WORK, 'lqm-state.json'), encoding='utf-8') as handle:
        state = json.load(handle)
    failures = []

    # 1. Developer Mode: the changelog and the files it names, read from GitLab by the API
    view = must(lqm('GET', '/projects/%s/changelogs/%s' % (state['project'], state['changelog'])), 200)
    files = list(view.get('files', []))
    print('developer view: %d file(s): %s' % (len(files), files))
    if not files:
        failures.append('Developer Mode returned no files')
    for path in files:
        status, body = lqm('GET', '/projects/%s/changelogs/%s/files?path=%s' % (state['project'], state['changelog'], urllib.parse.quote(path)))
        print('  %s -> %s, %d bytes' % (path, status, len(body) if isinstance(body, str) else len(json.dumps(body))))
        if status != 200:
            failures.append('file %s answered %s' % (path, status))

    # 2. Promote: the API fires the trigger pipeline; GitLab must have received it with our variables
    stages = must(lqm('GET', '/pipelines/%s/stages' % state['pipeline']), 200)
    hml = stages[-1]
    before = {p['id'] for p in must(gitlab('GET', '/projects/%d/pipelines?source=trigger' % state['gitlabProject']), 200)}
    status, promoted = lqm('POST', '/pipelines/%s/stages/%s/promote' % (state['pipeline'], hml['id']), {'release': 'poc-1.0'})
    print('promote ->', status, json.dumps(promoted)[:300])
    if status != 201:
        failures.append('promote answered %s' % status)
    else:
        time.sleep(2)
        pipelines = must(gitlab('GET', '/projects/%d/pipelines?source=trigger' % state['gitlabProject']), 200)
        new = [p for p in pipelines if p['id'] not in before]
        if not new:
            failures.append('GitLab did not create a pipeline from the trigger')
        else:
            variables = {v['key']: v['value'] for v in must(
                gitlab('GET', '/projects/%d/pipelines/%d/variables' % (state['gitlabProject'], new[0]['id'])), 200)}
            print('GitLab pipeline %d (%s) variables: %s' % (new[0]['id'], new[0]['status'], variables))
            if variables.get('LQM_PROMOTE_TO') != 'HML' or variables.get('LQM_PROMOTE_RELEASE') != 'poc-1.0':
                failures.append('trigger variables are not what was promoted: %s' % variables)

    if failures:
        sys.exit('FAILED:\n  ' + '\n  '.join(failures))
    print('ok')


def ci():
    """The project variables the `lqm-checks` job reads. The tokens exist only in GitLab (masked), never in a file.

    With `--token lqm_...` the service token is the one given (the way to do it when the API asks for sign-in); without it one is
    issued here, which only works on the development chain."""
    pid = project_id()
    expires = time.strftime('%Y-%m-%d', time.gmtime(time.time() + 25 * 24 * 3600))

    # a service token for the collector side of the API (policies, ingestion)
    if '--token' in sys.argv:
        lqm_token = sys.argv[sys.argv.index('--token') + 1]
    else:
        stamp = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(time.time() + 25 * 24 * 3600))
        lqm_token = must(lqm('POST', '/service-tokens', {'name': 'gitlab-poc-ci', 'scopes': ['ingest', 'checks:read'], 'expiresAt': stamp}), 201)['token']

    # the token that writes the merge request comment: a project access token with the api scope, replacing the last one
    for token in must(gitlab('GET', '/projects/%d/access_tokens' % pid), 200):
        if token['name'] == 'lqm-mr-comment' and token.get('active'):
            gitlab('DELETE', '/projects/%d/access_tokens/%d' % (pid, token['id']))
    created = must(gitlab('POST', '/projects/%d/access_tokens' % pid, body={
        'name': 'lqm-mr-comment', 'scopes': ['api'], 'access_level': 40, 'expires_at': expires}), 201)

    variables = [
        ('LQM_URL', GITLAB_FOR_API.replace(':8929', ':8080') + '/api/v1', False),
        ('LQM_TOKEN', lqm_token, True),
        ('LQM_GITLAB_TOKEN', created['token'], True),
    ]
    for key, value, masked in variables:
        payload = {'key': key, 'value': value, 'masked': masked, 'protected': False}
        status, _ = gitlab('PUT', '/projects/%d/variables/%s' % (pid, key), form=payload)
        if status == 404:
            must(gitlab('POST', '/projects/%d/variables' % pid, form=payload), 201)
    print('variables set on project %d: %s' % (pid, ', '.join(k for k, _, _ in variables)))


DEPLOY_JOB = '''
# 4. O que uma promocao do LiquiManager pede: a promocao dispara este pipeline com LQM_PROMOTE_TO (o ambiente) e
# LQM_PROMOTE_RELEASE (o nome da release). Aplica o changelog no banco do ambiente com `lqm update`, que passa pelo gate de
# politicas e relata a operacao ao LiquiManager. O banco do ambiente `X` e `pocx`, e a chave da conexao e `poc-x`.
lqm-deploy:
  stage: apply
  image: lqm-cli:local
  variables:
    LQM_PROJECT: liquibase-poc
    LQM_CHANGELOG_FILE: db/changelog/db.changelog-master.yaml
    LQM_DB_USERNAME: poc
    LQM_DB_PASSWORD: poc
  script:
    - lower=$(echo "$LQM_PROMOTE_TO" | tr 'A-Z' 'a-z')
    - export LQM_ENV="$LQM_PROMOTE_TO"
    - export LQM_CONNECTION_KEY="poc-$lower"
    - export LQM_DB_URL="jdbc:postgresql://host.docker.internal:5432/poc$lower"
    - lqm update --tag "${LQM_PROMOTE_RELEASE:-release}"
  rules:
    - if: $CI_PIPELINE_SOURCE == "trigger" && $LQM_PROMOTE_TO
'''

DEPLOY_DEV_JOB = '''
# 5. DEV recebe o que entra na main: o merge de um MR aplica o changelog no banco de DEV, com `lqm update`, e o relatorio vai ao
# LiquiManager. E isso que torna possivel promover para HML: o `lqm update` de HML recusa o que ainda nao esta em DEV.
lqm-deploy-dev:
  stage: apply
  image: lqm-cli:local
  variables:
    LQM_PROJECT: liquibase-poc
    LQM_ENV: DEV
    LQM_CONNECTION_KEY: poc-dev
    LQM_CHANGELOG_FILE: db/changelog/db.changelog-master.yaml
    LQM_DB_USERNAME: poc
    LQM_DB_PASSWORD: poc
    LQM_DB_URL: jdbc:postgresql://host.docker.internal:5432/pocdev
  script:
    - lqm update
  rules:
    - if: $CI_PIPELINE_SOURCE == "push" && $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
'''

OLD_RULES = '''  rules:
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
'''

NEW_RULES = '''  rules:
    - if: $CI_PIPELINE_SOURCE == "trigger"
      when: never
    - if: $CI_PIPELINE_SOURCE == "merge_request_event"
    - if: $CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH
'''


def deploy_job():
    """Adds the `lqm-deploy` job to the project's .gitlab-ci.yml (a commit on main) and keeps the ephemeral-database job from
    running on a promotion. Idempotent."""
    pid = project_id()
    path = urllib.parse.quote('.gitlab-ci.yml', safe='')
    status, text = gitlab('GET', '/projects/%d/repository/files/%s/raw?ref=main' % (pid, path))
    if status != 200:
        sys.exit('could not read .gitlab-ci.yml: %s %s' % (status, text))
    updated = text
    if 'lqm-deploy:' not in updated:
        if OLD_RULES not in updated:
            sys.exit('the rules of update-and-rollback are not what this script expects; add lqm-deploy by hand (see DEPLOY_JOB)')
        updated = updated.replace(OLD_RULES, NEW_RULES) + DEPLOY_JOB
    if 'lqm-deploy-dev:' not in updated:
        updated += DEPLOY_DEV_JOB
    if updated == text:
        print('lqm-deploy and lqm-deploy-dev are already in .gitlab-ci.yml')
        return
    must(gitlab('PUT', '/projects/%d/repository/files/%s' % (pid, path), body={
        'branch': 'main', 'content': updated,
        'commit_message': 'Jobs lqm-deploy (promocao) e lqm-deploy-dev (merge na main): aplicam pelo lqm e reportam ao LiquiManager'}), 200)
    print('lqm-deploy and lqm-deploy-dev committed to main')


def webhook():
    """One webhook for the whole instance, as a customer would register it once on the GROUP (a group webhook is a GitLab Premium feature;
    on a self-managed GitLab without it, a SYSTEM hook does the same for every project, which is what this POC uses).

    The merge request events of every project go to LiquiManager, which finds its own project by the repository path in the payload and
    closes (or reopens) the changesets that only that merge request had. The token is a service token with the `webhook` scope."""
    import subprocess
    name = 'gitlab-webhook'
    token = must(lqm('POST', '/service-tokens', {
        'name': name, 'scopes': ['webhook'],
        'expiresAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(time.time() + 90 * 86400))}), 201)['token']
    # GitLab's own hostname is host.docker.internal, so it cannot reach the API by that name: put the API on GitLab's network.
    subprocess.run(['docker', 'network', 'connect', 'gitlab-poc', 'lqm-api'], capture_output=True)
    url = 'http://lqm-api:8080/api/v1/integrations/gitlab/webhook'
    for hook in must(gitlab('GET', '/hooks'), 200):
        if hook.get('url') == url:
            gitlab('DELETE', '/hooks/%d' % hook['id'])
    must(gitlab('POST', '/hooks', body={
        'url': url, 'token': token, 'merge_requests_events': True, 'push_events': False, 'tag_push_events': False,
        'repository_update_events': False, 'enable_ssl_verification': False}), 201)
    print('system hook registered: merge request events -> %s (token %s, scope webhook)' % (url, name))


if __name__ == '__main__':
    command = sys.argv[1] if len(sys.argv) > 1 else ''
    {'setup': setup, 'register': register, 'verify': verify, 'ci': ci, 'deploy-job': deploy_job, 'webhook': webhook}.get(command, lambda: sys.exit(__doc__))()
