{{/* Nome base e nome completo dos recursos. */}}
{{- define "lqm.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "lqm.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "lqm.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Rotulos comuns e de selecao. O componente (api, web, worker, runner) entra em selecao. */}}
{{- define "lqm.labels" -}}
helm.sh/chart: {{ include "lqm.chart" . }}
app.kubernetes.io/name: {{ include "lqm.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "lqm.selectorLabels" -}}
app.kubernetes.io/name: {{ include "lqm.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/* Imagem: [registry/]repositorio:tag. A tag padrao e o appVersion. */}}
{{- define "lqm.image" -}}
{{- $registry := .root.Values.images.registry | default "" | trimSuffix "/" -}}
{{- $tag := .image.tag | default .root.Chart.AppVersion -}}
{{- if $registry -}}
{{- printf "%s/%s:%s" $registry .image.repository $tag -}}
{{- else -}}
{{- printf "%s:%s" .image.repository $tag -}}
{{- end -}}
{{- end -}}

{{- define "lqm.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "lqm.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "lqm.imagePullSecrets" -}}
{{- with .Values.images.pullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end -}}

{{/* Secret do banco: o do usuario, ou o que o chart cria. */}}
{{- define "lqm.dbExisting" -}}
{{- if .Values.database.existingSecret -}}true{{- end -}}
{{- end -}}

{{- define "lqm.dbSecretName" -}}
{{- if include "lqm.dbExisting" . -}}
{{- .Values.database.existingSecret -}}
{{- else -}}
{{- printf "%s-db" (include "lqm.fullname" .) -}}
{{- end -}}
{{- end -}}

{{- define "lqm.dbUserKey" -}}
{{- if include "lqm.dbExisting" . -}}{{ .Values.database.existingSecretUserKey }}{{- else -}}username{{- end -}}
{{- end -}}

{{- define "lqm.dbPasswordKey" -}}
{{- if include "lqm.dbExisting" . -}}{{ .Values.database.existingSecretPasswordKey }}{{- else -}}password{{- end -}}
{{- end -}}

{{- define "lqm.dbUrl" -}}
{{- required "database.url e obrigatorio (jdbc:postgresql://host:5432/lqm)" .Values.database.url -}}
{{- end -}}

{{- define "lqm.s3SecretName" -}}
{{- if .Values.storage.s3.existingSecret -}}{{ .Values.storage.s3.existingSecret }}{{- else -}}{{ printf "%s-s3" (include "lqm.fullname" .) }}{{- end -}}
{{- end -}}

{{- define "lqm.gitlabSecretName" -}}
{{- .Values.gitlab.existingSecret -}}
{{- end -}}

{{/*
Variaveis que a API e o worker compartilham (a mesma imagem, o mesmo banco, a mesma configuracao).
Recebe o contexto raiz. `LQM_JOBS_ENABLED` fica de fora: quem chama decide.
*/}}
{{- define "lqm.commonEnv" -}}
- name: LQM_DB_URL
  value: {{ include "lqm.dbUrl" . | quote }}
- name: LQM_DB_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ include "lqm.dbSecretName" . }}
      key: {{ include "lqm.dbUserKey" . }}
- name: LQM_DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ include "lqm.dbSecretName" . }}
      key: {{ include "lqm.dbPasswordKey" . }}
{{- if .Values.publicUrl }}
- name: LQM_PUBLIC_URL
  value: {{ .Values.publicUrl | quote }}
{{- end }}
{{- if .Values.oidc.enabled }}
- name: SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI
  value: {{ required "oidc.issuerUri e obrigatorio com oidc.enabled=true" .Values.oidc.issuerUri | quote }}
{{- if .Values.oidc.jwkSetUri }}
- name: SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI
  value: {{ .Values.oidc.jwkSetUri | quote }}
{{- end }}
{{- if .Values.oidc.audiences }}
- name: SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES
  value: {{ .Values.oidc.audiences | quote }}
{{- end }}
{{- if .Values.oidc.bootstrapAdmins }}
- name: LQM_SECURITY_BOOTSTRAP_ADMINS
  value: {{ .Values.oidc.bootstrapAdmins | quote }}
{{- end }}
{{- end }}
- name: LQM_STORAGE_TYPE
  value: {{ .Values.storage.type | quote }}
{{- if eq .Values.storage.type "s3" }}
- name: LQM_STORAGE_S3_ENDPOINT
  value: {{ required "storage.s3.endpoint e obrigatorio com storage.type=s3" .Values.storage.s3.endpoint | quote }}
- name: LQM_STORAGE_S3_BUCKET
  value: {{ .Values.storage.s3.bucket | quote }}
- name: LQM_STORAGE_S3_REGION
  value: {{ .Values.storage.s3.region | quote }}
- name: LQM_STORAGE_S3_ACCESS_KEY
  valueFrom:
    secretKeyRef:
      name: {{ include "lqm.s3SecretName" . }}
      key: accessKey
- name: LQM_STORAGE_S3_SECRET_KEY
  valueFrom:
    secretKeyRef:
      name: {{ include "lqm.s3SecretName" . }}
      key: secretKey
{{- end }}
{{- with .Values.secretsProvider }}
- name: LQM_SECRETS_CACHE_TTL
  value: {{ .cacheTtl | quote }}
{{- if .vault.enabled }}
- name: LQM_SECRETS_VAULT_URL
  value: {{ required "secretsProvider.vault.url e obrigatorio" .vault.url | quote }}
- name: LQM_SECRETS_VAULT_APPROLE_PATH
  value: {{ .vault.approlePath | quote }}
{{- if .vault.namespace }}
- name: LQM_SECRETS_VAULT_NAMESPACE
  value: {{ .vault.namespace | quote }}
{{- end }}
{{- if .vault.existingSecret }}
- name: LQM_SECRETS_VAULT_TOKEN
  valueFrom: { secretKeyRef: { name: {{ .vault.existingSecret }}, key: token, optional: true } }
- name: LQM_SECRETS_VAULT_ROLE_ID
  valueFrom: { secretKeyRef: { name: {{ .vault.existingSecret }}, key: roleId, optional: true } }
- name: LQM_SECRETS_VAULT_SECRET_ID
  valueFrom: { secretKeyRef: { name: {{ .vault.existingSecret }}, key: secretId, optional: true } }
{{- end }}
{{- end }}
{{- if .consul.enabled }}
- name: LQM_SECRETS_CONSUL_URL
  value: {{ required "secretsProvider.consul.url e obrigatorio" .consul.url | quote }}
{{- if .consul.existingSecret }}
- name: LQM_SECRETS_CONSUL_TOKEN
  valueFrom: { secretKeyRef: { name: {{ .consul.existingSecret }}, key: token } }
{{- end }}
{{- end }}
{{- if .azure.enabled }}
- name: LQM_SECRETS_AZURE_VAULT_URL
  value: {{ required "secretsProvider.azure.vaultUrl e obrigatorio" .azure.vaultUrl | quote }}
{{- if .azure.existingSecret }}
- name: LQM_SECRETS_AZURE_TOKEN
  valueFrom: { secretKeyRef: { name: {{ .azure.existingSecret }}, key: token } }
{{- end }}
{{- end }}
{{- end }}
{{- if .Values.gitlab.baseUrl }}
- name: LQM_GITLAB_BASE_URL
  value: {{ .Values.gitlab.baseUrl | quote }}
{{- if .Values.gitlab.existingSecret }}
- name: LQM_GITLAB_READ_TOKEN
  valueFrom: { secretKeyRef: { name: {{ .Values.gitlab.existingSecret }}, key: readToken, optional: true } }
- name: LQM_GITLAB_TRIGGER_TOKEN
  valueFrom: { secretKeyRef: { name: {{ .Values.gitlab.existingSecret }}, key: triggerToken, optional: true } }
{{- end }}
{{- end }}
{{- if .Values.notifications.email.url }}
- name: LQM_NOTIFICATIONS_EMAIL_URL
  value: {{ .Values.notifications.email.url | quote }}
{{- if .Values.notifications.email.existingSecret }}
- name: LQM_NOTIFICATIONS_EMAIL_TOKEN
  valueFrom: { secretKeyRef: { name: {{ .Values.notifications.email.existingSecret }}, key: token } }
{{- end }}
{{- end }}
{{- end -}}

{{/* Regras que o chart verifica antes de criar qualquer coisa. */}}
{{- define "lqm.validate" -}}
{{- if not .Values.oidc.enabled -}}
{{- fail "oidc.enabled=true e obrigatorio: este chart nao expoe a API sem login" -}}
{{- end -}}
{{- if not .Values.oidc.issuerUri -}}
{{- fail "oidc.issuerUri e obrigatorio (o iss dos tokens, ex.: https://sso.exemplo.com/realms/liquimanager)" -}}
{{- end -}}
{{- if and (gt (int .Values.api.replicaCount) 1) (ne .Values.storage.type "s3") -}}
{{- fail "api.replicaCount > 1 exige storage.type=s3: com diretorio local, um log gravado por uma replica nao abre na outra" -}}
{{- end -}}
{{- if and .Values.worker.enabled (ne .Values.storage.type "s3") -}}
{{- fail "worker.enabled=true exige storage.type=s3: o worker e a API nao dividem o diretorio local" -}}
{{- end -}}
{{- if and .Values.runner.enabled (not .Values.runner.tokenSecret.name) -}}
{{- fail "runner.tokenSecret.name e obrigatorio com runner.enabled=true" -}}
{{- end -}}
{{- if and .Values.runner.enabled (not .Values.runner.gitUrlSecret.name) -}}
{{- fail "runner.gitUrlSecret.name e obrigatorio com runner.enabled=true" -}}
{{- end -}}
{{- if and (eq .Values.storage.type "s3") (not .Values.storage.s3.existingSecret) (not .Values.storage.s3.accessKey) -}}
{{- fail "storage.type=s3 exige storage.s3.existingSecret (chaves accessKey e secretKey) ou accessKey e secretKey" -}}
{{- end -}}
{{- if and (not .Values.database.existingSecret) (not .Values.database.password) -}}
{{- fail "informe database.existingSecret (recomendado) ou database.password" -}}
{{- end -}}
{{- end -}}
