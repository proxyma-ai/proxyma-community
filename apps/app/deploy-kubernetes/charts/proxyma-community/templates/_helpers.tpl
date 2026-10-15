{{- define "proxyma.fullname" -}}{{ include "proxyma-common.fullname" . }}{{- end -}}

{{/* The Host allow-list: the operator's, or the ingress hosts. Loopback names always pass. */}}
{{- define "proxyma.allowedHosts" -}}
{{- if .Values.access.allowedHosts -}}
{{- .Values.access.allowedHosts -}}
{{- else if .Values.ingress.enabled -}}
{{- join "," .Values.ingress.hosts -}}
{{- end -}}
{{- end -}}

{{/*
A probe. Host: localhost, because the application answers loopback names and the allow-list only,
and the kubelet would otherwise send the pod's IP address and get 403. The health endpoints need no
access code.
*/}}
{{- define "proxyma.probe" -}}
httpGet:
  path: {{ .path }}
  port: http
  httpHeaders:
    - name: Host
      value: localhost
periodSeconds: {{ .periodSeconds }}
timeoutSeconds: {{ .timeoutSeconds }}
failureThreshold: {{ .failureThreshold }}
{{- end -}}
