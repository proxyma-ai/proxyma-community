{{/*
The Ingress, for both editions. Chat, the event stream and MCP are long-lived streams (SSE), so a
proxy that buffers responses turns a streaming answer into a page that looks hung, and one with a
60-second read timeout cuts a long turn off. The nginx preset says so to ingress-nginx; any other
controller gets the operator's own `ingress.annotations` (docs/development.html#build-homeserver).
*/}}
{{- define "proxyma-common.ingress" -}}
{{- if .Values.ingress.enabled -}}
{{- $fullname := include "proxyma-common.fullname" . -}}
{{- $preset := .Values.ingress.preset | default "auto" -}}
{{- $nginx := or (eq $preset "nginx") (and (eq $preset "auto") (eq (.Values.ingress.className | default "") "nginx")) -}}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ $fullname }}
  labels:
    {{- include "proxyma-common.labels" (dict "ctx" . "component" "app") | nindent 4 }}
  {{- if or $nginx .Values.ingress.annotations }}
  annotations:
    {{- if $nginx }}
    nginx.ingress.kubernetes.io/proxy-buffering: "off"
    nginx.ingress.kubernetes.io/proxy-request-buffering: "off"
    nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"
    nginx.ingress.kubernetes.io/proxy-send-timeout: "3600"
    nginx.ingress.kubernetes.io/proxy-body-size: "200m"
    {{- end }}
    {{- with .Values.ingress.annotations }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
  {{- end }}
spec:
  {{- with .Values.ingress.className }}
  ingressClassName: {{ . }}
  {{- end }}
  {{- with .Values.ingress.tls }}
  tls:
    {{- toYaml . | nindent 4 }}
  {{- end }}
  rules:
    {{- range .Values.ingress.hosts }}
    - host: {{ . | quote }}
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: {{ $fullname }}
                port:
                  name: http
    {{- end }}
{{- end -}}
{{- end -}}
