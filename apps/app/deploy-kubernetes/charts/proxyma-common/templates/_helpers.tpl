{{/*
Names, labels and the image reference. Every template in both charts is included with the
application chart's own context, so .Chart and .Values are the application chart's.
*/}}

{{- define "proxyma-common.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "proxyma-common.fullname" -}}
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

{{- define "proxyma-common.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Selector labels of one component: (dict "ctx" $ "component" "app") */}}
{{- define "proxyma-common.selectorLabels" -}}
app.kubernetes.io/name: {{ include "proxyma-common.name" .ctx }}
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/* All labels of one component: (dict "ctx" $ "component" "app") */}}
{{- define "proxyma-common.labels" -}}
helm.sh/chart: {{ include "proxyma-common.chart" .ctx }}
{{ include "proxyma-common.selectorLabels" . }}
app.kubernetes.io/version: {{ .ctx.Chart.AppVersion | quote }}
app.kubernetes.io/part-of: proxyma
app.kubernetes.io/managed-by: {{ .ctx.Release.Service }}
{{- end -}}

{{/* repository:tag, or repository@digest when a digest is given: (dict "image" .Values.image "default" .Chart.AppVersion) */}}
{{- define "proxyma-common.image" -}}
{{- if .image.digest -}}
{{- printf "%s@%s" .image.repository .image.digest -}}
{{- else -}}
{{- printf "%s:%s" .image.repository (default .default .image.tag | toString) -}}
{{- end -}}
{{- end -}}

{{- define "proxyma-common.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "proxyma-common.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* The Secret the application's optional secrets are read from: the operator's, or the chart's. */}}
{{- define "proxyma-common.secretName" -}}
{{- default (printf "%s-secrets" (include "proxyma-common.fullname" .)) .Values.secrets.existingSecret -}}
{{- end -}}

{{/*
An environment variable read from the application Secret, optional so that a key the operator's
Secret does not carry simply leaves the variable unset: (dict "ctx" $ "name" "PROXYMA_X" "key" "x")
*/}}
{{- define "proxyma-common.secretEnv" -}}
- name: {{ .name }}
  valueFrom:
    secretKeyRef:
      name: {{ include "proxyma-common.secretName" .ctx }}
      key: {{ .key }}
      optional: true
{{- end -}}

{{/*
The sandbox token: the operator's value, the one already in the cluster (so an upgrade keeps it), or
a new one. helm template has no cluster to look in and makes a new one every time; the sandbox and
the application read the same Secret, so they still agree.
*/}}
{{- define "proxyma-common.sandboxToken" -}}
{{- $existing := lookup "v1" "Secret" .Release.Namespace (printf "%s-secrets" (include "proxyma-common.fullname" .)) -}}
{{- if .Values.office.token -}}
{{- .Values.office.token -}}
{{- else if and $existing $existing.data (hasKey $existing.data "sandbox-token") -}}
{{- index $existing.data "sandbox-token" | b64dec -}}
{{- else -}}
{{- randAlphaNum 48 -}}
{{- end -}}
{{- end -}}

{{/* The pod security context, with null entries dropped so OpenShift can null runAsUser and fsGroup. */}}
{{- define "proxyma-common.podSecurityContext" -}}
{{- $out := dict -}}
{{- range $k, $v := .Values.podSecurityContext -}}
{{- if not (kindIs "invalid" $v) -}}
{{- $_ := set $out $k $v -}}
{{- end -}}
{{- end -}}
{{- toYaml $out -}}
{{- end -}}
