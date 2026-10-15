{{/* The Services, the ServiceAccount and the PodDisruptionBudget - the same in both editions. */}}
{{- define "proxyma-common.services" -}}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "proxyma-common.fullname" . }}
  labels:
    {{- include "proxyma-common.labels" (dict "ctx" . "component" "app") | nindent 4 }}
  {{- with .Values.service.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  type: {{ .Values.service.type }}
  selector:
    {{- include "proxyma-common.selectorLabels" (dict "ctx" . "component" "app") | nindent 4 }}
  ports:
    - name: http
      port: {{ .Values.service.port }}
      targetPort: http
      protocol: TCP
---
# The StatefulSet's governing service: a stable name for the one pod.
apiVersion: v1
kind: Service
metadata:
  name: {{ include "proxyma-common.fullname" . }}-headless
  labels:
    {{- include "proxyma-common.labels" (dict "ctx" . "component" "app") | nindent 4 }}
spec:
  clusterIP: None
  selector:
    {{- include "proxyma-common.selectorLabels" (dict "ctx" . "component" "app") | nindent 4 }}
  ports:
    - name: http
      port: 4246
      targetPort: http
      protocol: TCP
{{- end -}}

{{- define "proxyma-common.serviceAccount" -}}
{{- if .Values.serviceAccount.create }}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "proxyma-common.serviceAccountName" . }}
  labels:
    {{- include "proxyma-common.labels" (dict "ctx" . "component" "app") | nindent 4 }}
  {{- with .Values.serviceAccount.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
automountServiceAccountToken: false
{{- end }}
{{- end -}}

{{- define "proxyma-common.pdb" -}}
{{- if .Values.podDisruptionBudget.enabled }}
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ include "proxyma-common.fullname" . }}
  labels:
    {{- include "proxyma-common.labels" (dict "ctx" . "component" "app") | nindent 4 }}
spec:
  maxUnavailable: {{ .Values.podDisruptionBudget.maxUnavailable }}
  selector:
    matchLabels:
      {{- include "proxyma-common.selectorLabels" (dict "ctx" . "component" "app") | nindent 6 }}
{{- end }}
{{- end -}}
