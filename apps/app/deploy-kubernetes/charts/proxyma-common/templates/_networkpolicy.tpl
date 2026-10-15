{{/*
The application's NetworkPolicy, for both editions: (dict "ctx" $ "extraEgress" (list ...))

In: port 4246 from the ingress controller's namespace, from this release's own pods (helm test) and
from whatever networkPolicy.extraIngress adds. Out: DNS, this release's own components, and then
either everything except the cloud metadata address (the default - connectors and AI providers live
anywhere, on any port) or only what networkPolicy.egress.extra lists. A NetworkPolicy needs a network
plugin that enforces it; without one it is accepted and does nothing.
*/}}
{{- define "proxyma-common.networkPolicy" -}}
{{- $ := .ctx -}}
{{- if $.Values.networkPolicy.enabled -}}
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ include "proxyma-common.fullname" $ }}
  labels:
    {{- include "proxyma-common.labels" (dict "ctx" $ "component" "app") | nindent 4 }}
spec:
  podSelector:
    matchLabels:
      {{- include "proxyma-common.selectorLabels" (dict "ctx" $ "component" "app") | nindent 6 }}
  policyTypes: [Ingress, Egress]
  ingress:
    - ports:
        - port: 4246
          protocol: TCP
      from:
        {{- with $.Values.networkPolicy.ingressNamespaceSelector }}
        - namespaceSelector:
            matchLabels:
              {{- toYaml . | nindent 14 }}
        {{- end }}
        - podSelector:
            matchLabels:
              app.kubernetes.io/instance: {{ $.Release.Name }}
    {{- with $.Values.networkPolicy.extraIngress }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
  egress:
    - ports:
        - port: 53
          protocol: UDP
        - port: 53
          protocol: TCP
      to:
        - namespaceSelector: {}
          podSelector:
            matchLabels:
              k8s-app: kube-dns
    - to:
        - podSelector:
            matchLabels:
              app.kubernetes.io/instance: {{ $.Release.Name }}
    {{- if $.Values.networkPolicy.egress.allowInternet }}
    - to:
        - ipBlock:
            cidr: 0.0.0.0/0
            {{- with $.Values.networkPolicy.egress.denyCidrs }}
            except:
              {{- toYaml . | nindent 14 }}
            {{- end }}
    {{- end }}
    {{- with .extraEgress }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
    {{- with $.Values.networkPolicy.egress.extra }}
    {{- toYaml . | nindent 4 }}
    {{- end }}
{{- end -}}
{{- end -}}
