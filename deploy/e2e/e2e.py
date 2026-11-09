#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""End-to-end test of LiquiManager over a clean database: login, permissions, a pipeline that deploys, a rollback.

    python deploy/e2e/e2e.py reset          # clean database, sign-in on (Keycloak), GitLab POC connected. For you to drive.
    python deploy/e2e/e2e.py again          # run the walk-through again: empty target databases and LiquiManager's operations, and the
                                            # git project back to "001 on main, 002 waiting in an open merge request". Keeps users,
                                            # catalogue, tokens and the audit trail.
    python deploy/e2e/e2e.py runner TOKEN   # starts the rollback runner with the service token you issued (scopes runner, ingest)
    python deploy/e2e/e2e.py reset --dev    # the same on the development chain (no sign-in), for the scripted check below
    python deploy/e2e/e2e.py check          # --dev only: registers, promotes, waits for the deploy, rolls back, checks the result
    python deploy/e2e/e2e.py prepare [--four]  # --dev only: registers project, pipeline and tokens, starts the runner, opens the MR: for testing by hand

`reset` DELETES the LiquiManager database and the Keycloak data (the compose volumes). The GitLab POC is not touched: it is another
compose project. Read deploy/e2e/README.md for the walk-through.
"""
from http.client import HTTPException
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
POC = os.path.join(ROOT, 'deploy', 'gitlab-poc')
LQM_ENV_FILE = os.path.join(POC, '.lqm', 'lqm.env')
LQM = 'http://localhost:8080/api/v1'
GITLAB = 'http://localhost:8929'
PAT = 'glpat-poc-liquibase-token1'
TARGETS = ['pocdev', 'poctst', 'pochml', 'pocprd']

FILES = ['-f', 'deploy/docker-compose.yml', '-f', 'deploy/gitlab-poc/docker-compose.lqm.yml', '-f', 'deploy/e2e/docker-compose.e2e.yml']
OIDC = ['-f', 'deploy/docker-compose.oidc.yml']


def run(cmd, check=True, **kwargs):
    print('$', ' '.join(cmd))
    return subprocess.run(cmd, cwd=ROOT, check=check, **kwargs)


def compose(*args, dev=False, check=True, **kwargs):
    files = FILES + ([] if dev else OIDC)
    return run(['docker', 'compose'] + files + ['--env-file', LQM_ENV_FILE] + list(args), check=check, **kwargs)


def http(method, url, body=None, headers=None, form=None):
    data = None
    headers = dict(headers or {})
    if body is not None:
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
    except (urllib.error.URLError, HTTPException, OSError) as error:
        return 0, str(error)


def lqm(method, path, body=None, user=None):
    # On the development chain a call with no identity is allowed everything; one with X-LQM-User goes through the RBAC rules.
    return http(method, LQM + path, body, {'X-LQM-User': user} if user else {})


def must(result, *accepted):
    status, body = result
    if status not in accepted:
        sys.exit('unexpected %s: %s' % (status, body))
    return body


def gitlab(method, path, body=None):
    return http(method, GITLAB + '/api/v4' + path, body, {'PRIVATE-TOKEN': PAT})


def wait_for(what, probe, seconds=240, every=3):
    deadline = time.time() + seconds
    while time.time() < deadline:
        value = probe()
        if value:
            return value
        time.sleep(every)
    sys.exit('timed out waiting for ' + what)


def psql(database, sql, user='lqm'):
    return subprocess.run(['docker', 'exec', 'lqm-postgres', 'psql', '-U', user, '-d', database, '-tA', '-c', sql],
                          capture_output=True, text=True)


# ------------------------------------------------------------------------------------------------------------------- reset

def reset(dev):
    if gitlab('GET', '/version')[0] != 200:
        sys.exit('the GitLab POC is not answering at %s (docker compose -f deploy/gitlab-poc/docker-compose.yml up -d)' % GITLAB)
    run([sys.executable, os.path.join(POC, 'connect-lqm.py'), 'setup'])
    compose('down', '-v', '--remove-orphans', dev=dev)
    if subprocess.run(['docker', 'image', 'inspect', 'lqm-cli:local'], capture_output=True).returncode != 0:
        run(['docker', 'build', '-f', 'lqm-cli/Dockerfile', '-t', 'lqm-cli:local', '.'])
    services = ['postgres', 'api'] + ([] if dev else ['keycloak'])
    compose('up', '-d', '--build', *services, dev=dev)
    wait_for('the API', lambda: http('GET', 'http://localhost:8080/actuator/health/readiness')[0] == 200, seconds=300)
    psql('lqm', "create user poc password 'poc'")
    for database in TARGETS:
        psql('lqm', 'create database %s owner poc' % database)
    # The integration tests of lqm-api use this one (CONTRIBUTING.md); the volume is recreated here, so it would otherwise be gone.
    psql('lqm', 'create database lqm_test')
    run([sys.executable, os.path.join(POC, 'connect-lqm.py'), 'deploy-job'])
    # The token left in GitLab by an earlier run belongs to a database that no longer exists: a job would get 401 and, being
    # fail-open (ADR-004), still deploy and report nothing. Better that it has no token until you issue one (README, step 5).
    gitlab('DELETE', '/projects/1/variables/LQM_TOKEN')
    print()
    if dev:
        print('Development chain is up (no sign-in). Next: python deploy/e2e/e2e.py check')
    else:
        print('Ready, with a clean database. Open http://localhost:5173 (cd lqm-web && npm run dev:oidc) and sign in as admin.lqm.')
        print('The walk-through is in deploy/e2e/README.md.')


# -------------------------------------------------------------------------------------------------------------- the check

def environments():
    return {e['code']: e['id'] for e in must(lqm('GET', '/environments'), 200)}


def seed():
    """What a person would do in the screens, through the API: project, changelog, connections, pipeline, tokens."""
    env = environments()
    pid = must(gitlab('GET', '/projects?search=liquibase-poc'), 200)[0]['id']
    project = must(lqm('POST', '/projects', {
        'slug': 'liquibase-poc', 'name': 'Liquibase POC', 'defaultBranch': 'main',
        'repoUrl': 'http://host.docker.internal:8929/root/liquibase-poc.git'}), 201)
    must(lqm('POST', '/projects/%s/changelogs' % project['id'],
             {'name': 'master', 'path': 'db/changelog/db.changelog-master.yaml', 'format': 'YAML'}), 201)
    stages = []
    for order, code in enumerate(('DEV', 'HML'), start=1):
        connection = must(lqm('POST', '/connections', {
            'environmentId': env[code], 'slug': 'poc-%s' % code.lower(), 'displayName': 'POC %s' % code, 'engine': 'POSTGRESQL',
            'jdbcUrl': 'jdbc:postgresql://postgres:5432/poc%s' % code.lower(), 'secretProvider': 'ENV', 'secretRef': 'LQM_E2E_DB',
            'scannerEnabled': False, 'driftEnabled': False}), 201)
        stages.append((order, env[code], connection['id']))
    must(lqm('PUT', '/projects/%s/connections' % project['id'], {'connectionIds': [c for _, _, c in stages]}), 200)
    pipeline = must(lqm('POST', '/pipelines', {
        'name': 'POC-GATE', 'projectId': project['id'], 'gitlabProjectId': pid, 'gitlabTriggerRef': 'main'}), 201)
    for order, environment, connection in stages:
        must(lqm('POST', '/pipelines/%s/stages' % pipeline['id'], {
            'sortOrder': order, 'environmentId': environment, 'dbConnectionId': connection, 'requiresApproval': False}), 201)

    ci = must(lqm('POST', '/service-tokens', {
        'name': 'e2e-ci', 'scopes': ['ingest', 'checks:read'], 'expiresAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(time.time() + 20 * 86400))}), 201)
    run([sys.executable, os.path.join(POC, 'connect-lqm.py'), 'ci', '--token', ci['token']])
    runner = must(lqm('POST', '/service-tokens', {
        'name': 'e2e-runner', 'scopes': ['runner', 'ingest'], 'expiresAt': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(time.time() + 20 * 86400))}), 201)
    return {'project': project['id'], 'pipeline': pipeline['id'], 'runnerToken': runner['token']}


def check():
    if http('GET', LQM + '/me')[0] != 200:
        sys.exit('the API is not answering without sign-in: this is `reset --dev`')
    state = seed()
    stage = must(lqm('GET', '/pipelines/%s/stages' % state['pipeline']), 200)[-1]

    def pipelines():
        return {p['id'] for p in must(gitlab('GET', '/projects/1/pipelines?source=trigger'), 200)}

    def finish(before, what):
        def new_pipeline():
            found = [p for p in must(gitlab('GET', '/projects/1/pipelines?source=trigger'), 200) if p['id'] not in before]
            return found[0] if found else None
        pipeline = wait_for('the GitLab pipeline of ' + what, new_pipeline, seconds=60)
        print('GitLab pipeline', pipeline['id'], pipeline['web_url'])

        def finished():
            current = must(gitlab('GET', '/projects/1/pipelines/%d' % pipeline['id']), 200)
            return current if current['status'] in ('success', 'failed', 'canceled') else None
        return wait_for('the pipeline of %s to finish' % what, finished, seconds=600, every=5)['status']

    def cliente_in(database):
        return psql(database, "select count(*) from information_schema.tables where table_name = 'cliente'").stdout.strip()

    # D-22: what is in HML has to be in DEV first. Promoting to HML while DEV is empty must stop at the deploy.
    before = pipelines()
    must(lqm('POST', '/pipelines/%s/stages/%s/promote' % (state['pipeline'], stage['id']), {'release': 'e2e-1.0'}), 201)
    if finish(before, 'the promotion to HML with DEV empty') != 'failed':
        sys.exit('the deploy to HML should have been stopped, DEV has nothing')
    if cliente_in('pochml') != '0':
        sys.exit('HML was changed although DEV had nothing')
    print('OK: HML refused what DEV does not have')

    # DEV gets it (a push to main does this in the walk-through; here the same job is asked for through the trigger)
    token = open(LQM_ENV_FILE, encoding='utf-8').read().split('=', 1)[1].strip()
    before = pipelines()
    form = urllib.parse.urlencode({'token': token, 'ref': 'main', 'variables[LQM_PROMOTE_TO]': 'DEV'}).encode()
    urllib.request.urlopen(urllib.request.Request(GITLAB + '/api/v4/projects/1/trigger/pipeline', data=form), timeout=60).read()
    if finish(before, 'DEV') != 'success':
        sys.exit('the deploy to DEV failed')

    before = pipelines()
    promoted = must(lqm('POST', '/pipelines/%s/stages/%s/promote' % (state['pipeline'], stage['id']), {'release': 'e2e-1.0'}), 201)
    print('promoted:', json.dumps(promoted)[:200])
    if finish(before, 'the promotion to HML') != 'success':
        sys.exit('the deploy to HML failed although DEV has it')

    def deploy_operation():
        page = must(lqm('GET', '/operations?size=50'), 200)
        rows = page['content'] if isinstance(page, dict) else page
        return next((o for o in rows if o.get('command') == 'update' and o.get('status') == 'SUCCESS'
                     and 'hml' in str(o.get('connection', o.get('connectionKey', ''))).lower()), None)
    operation = wait_for('the deploy operation in LiquiManager', deploy_operation, seconds=60)
    print('operation', operation['id'], operation.get('connection', operation.get('connectionKey')), operation['status'])
    tables = psql('pochml', "select count(*) from information_schema.tables where table_name = 'cliente'").stdout.strip()
    if tables != '1':
        sys.exit('the deploy did not create the table cliente in pochml (%s)' % tables)

    request = must(lqm('POST', '/operations/%s/rollback-requests' % operation['id'], {
        'changeset': '001-create-cliente', 'reason': 'e2e: undo the last changeset to prove the rollback path'}), 201)
    print('rollback request', request['id'], request['status'])
    if request['status'] == 'REQUESTED':
        must(lqm('POST', '/rollback-requests/%s/approve' % request['id'], {}, user='dba.demo'), 200)

    compose('run', '--rm', '-e', 'LQM_TOKEN=' + state['runnerToken'], 'runner', 'lqm', 'runner', '--once', dev=True)

    def done():
        current = must(lqm('GET', '/rollback-requests/%s' % request['id']), 200)
        return current if current['status'] in ('DONE', 'FAILED') else None
    final = wait_for('the rollback', done, seconds=120)
    if final['status'] != 'DONE':
        sys.exit('the rollback ended %s: %s' % (final['status'], final.get('error')))
    tables = psql('pochml', "select count(*) from information_schema.tables where table_name = 'cliente'").stdout.strip()
    if tables != '0':
        sys.exit('the rollback said DONE but the table cliente is still in pochml')
    print('\nEND-TO-END OK: promotion -> GitLab pipeline -> lqm update -> operation in LiquiManager -> rollback request -> runner -> table gone')


CHANGE = 'db/changelog/changes/002-create-pedido.yaml'
BRANCH = 'feat/pedido-2'


def again():
    """Back to the starting line of the walk-through without touching users, catalogue or tokens."""
    pid = 1
    # 1. nothing from an earlier run may still be going
    for status in ('running', 'pending', 'created'):
        for pipeline in must(gitlab('GET', '/projects/%d/pipelines?status=%s' % (pid, status)), 200):
            gitlab('POST', '/projects/%d/pipelines/%d/cancel' % (pid, pipeline['id']))

    # 2. git: 001 on main, 002 in an open merge request. `[ci skip]` so that putting main back does not deploy to DEV.
    quoted = urllib.parse.quote(CHANGE, safe='')
    status, content = gitlab('GET', '/projects/%d/repository/files/%s/raw?ref=main' % (pid, quoted))
    if status == 200:
        must(gitlab('DELETE', '/projects/%d/repository/files/%s' % (pid, quoted),
                    {'branch': 'main', 'commit_message': 'Volta main para o changeset 001 [ci skip]'}), 204)
    elif status == 404:
        content = open(os.path.join(POC, 'sample-project', CHANGE), encoding='utf-8').read()
    else:
        sys.exit('could not read %s: %s' % (CHANGE, status))
    for mr in must(gitlab('GET', '/projects/%d/merge_requests?state=opened&source_branch=%s' % (pid, urllib.parse.quote(BRANCH, safe=''))), 200):
        gitlab('PUT', '/projects/%d/merge_requests/%d' % (pid, mr['iid']), {'state_event': 'close'})
    gitlab('DELETE', '/projects/%d/repository/branches/%s' % (pid, urllib.parse.quote(BRANCH, safe='')))
    must(gitlab('POST', '/projects/%d/repository/branches?branch=%s&ref=main' % (pid, urllib.parse.quote(BRANCH, safe=''))), 201)
    must(gitlab('POST', '/projects/%d/repository/files/%s' % (pid, quoted), {
        'branch': BRANCH, 'content': content, 'commit_message': 'Changeset 002: pedidos vinculados ao cliente'}), 201)
    mr = must(gitlab('POST', '/projects/%d/merge_requests' % pid, {
        'source_branch': BRANCH, 'target_branch': 'main', 'title': 'Changeset 002: pedidos', 'remove_source_branch': True}), 201)

    empty_databases()
    print('Ready to run again. Merge request:', mr['web_url'])
    print('main has changeset 001; DEV and HML are empty; 002 is in the merge request.')


def empty_databases():
    """The target databases empty, and LiquiManager forgetting what it learned from the runs. The audit trail stays: it is never rewritten."""
    for database in TARGETS:
        psql('postgres', "select pg_terminate_backend(pid) from pg_stat_activity where datname = '%s'" % database)
        psql('postgres', 'drop database if exists %s' % database)
        psql('postgres', 'create database %s owner poc' % database)
    psql('lqm', 'truncate operation, operation_changeset, changeset_state, changeset, policy_finding, promotion, rollback_request, '
         'drift_run, drift_item, schema_snapshot cascade')


SCENARIO = os.path.join(ROOT, 'deploy', 'e2e', 'scenario')
BRANCH4 = 'feat/telefone-4'
CHANGES = 'db/changelog/changes/'


def four():
    """Four changesets: 001, 002 and 003 on main, and 004 (a column on cliente, so it depends on 001) in an open merge request."""
    pid = 1
    q = lambda path: urllib.parse.quote(path, safe='')
    for status in ('running', 'pending', 'created'):
        for pipeline in must(gitlab('GET', '/projects/%d/pipelines?status=%s' % (pid, status)), 200):
            gitlab('POST', '/projects/%d/pipelines/%d/cancel' % (pid, pipeline['id']))

    def text(*parts):
        return open(os.path.join(*parts), encoding='utf-8').read()

    wanted = {
        CHANGES + '002-create-pedido.yaml': text(POC, 'sample-project', CHANGE),
        CHANGES + '003-create-produto.yaml': text(SCENARIO, '003-create-produto.yaml'),
    }
    # main has exactly 001, 002 and 003, in ONE commit and `[ci skip]` (putting it back must not deploy to DEV)
    tree = must(gitlab('GET', '/projects/%d/repository/tree?path=%s&ref=main&per_page=100' % (pid, q(CHANGES.rstrip('/')))), 200)
    present = {CHANGES + item['name'] for item in tree}
    actions = []
    for path, content in wanted.items():
        actions.append({'action': 'update' if path in present else 'create', 'file_path': path, 'content': content})
    for path in sorted(present):
        if path not in wanted and not path.endswith('001-create-cliente.yaml'):
            actions.append({'action': 'delete', 'file_path': path})
    must(gitlab('POST', '/projects/%d/repository/commits' % pid, {
        'branch': 'main', 'commit_message': 'Cenario: changesets 001, 002 e 003 na main [ci skip]', 'actions': actions}), 201)

    # the merge request with 004
    for mr in must(gitlab('GET', '/projects/%d/merge_requests?state=opened' % pid), 200):
        gitlab('PUT', '/projects/%d/merge_requests/%d' % (pid, mr['iid']), {'state_event': 'close'})
    for branch in (BRANCH4, BRANCH):
        gitlab('DELETE', '/projects/%d/repository/branches/%s' % (pid, q(branch)))
    must(gitlab('POST', '/projects/%d/repository/branches?branch=%s&ref=main' % (pid, q(BRANCH4))), 201)
    must(gitlab('POST', '/projects/%d/repository/files/%s' % (pid, q(CHANGES + '004-add-telefone-cliente.yaml')), {
        'branch': BRANCH4, 'content': text(SCENARIO, '004-add-telefone-cliente.yaml'),
        'commit_message': 'Changeset 004: telefone do cliente'}), 201)
    mr = must(gitlab('POST', '/projects/%d/merge_requests' % pid, {
        'source_branch': BRANCH4, 'target_branch': 'main', 'title': 'Changeset 004: telefone do cliente',
        'remove_source_branch': True}), 201)

    empty_databases()
    print('Ready: main has 001, 002 and 003; DEV and HML are empty; 004 is in the merge request:', mr['web_url'])


def prepare(scenario_four=False):
    """Everything the walk-through needs, for testing by hand: project, connections, pipeline, tokens, the runner and the merge request.

    With `--four`: 001, 002 and 003 on main and 004 in the merge request, instead of 001 on main and 002 in the merge request."""
    if http('GET', LQM + '/me')[0] != 200:
        sys.exit('the API is not answering without sign-in: run `reset --dev` first')
    state = seed()
    runner(state['runnerToken'], dev=True)
    four() if scenario_four else again()
    print()
    print('Ready to test by hand (development chain, no sign-in): http://localhost:5173 (cd lqm-web && npm run dev) or http://localhost:8088')
    print('Pipeline POC-GATE: stages DEV -> HML. Merge the merge request in GitLab, promote to HML in the matrix, request a rollback in the operation.')


def runner(token, dev=False):
    env = dict(os.environ, LQM_RUNNER_TOKEN=token)
    # Same files as the API was started with: another set would recreate it (and, on the wrong chain, change how it signs people in).
    compose('--profile', 'runner', 'up', '-d', 'runner', dev=dev, env=env)
    print('runner started; `docker logs -f liquimanager-runner-1` to watch it')


if __name__ == '__main__':
    command = sys.argv[1] if len(sys.argv) > 1 else ''
    if command == 'reset':
        reset('--dev' in sys.argv)
    elif command == 'again':
        again()
    elif command == 'check':
        check()
    elif command == 'prepare':
        prepare('--four' in sys.argv)
    elif command == 'runner' and len(sys.argv) > 2:
        runner(sys.argv[2])
    else:
        sys.exit(__doc__)
