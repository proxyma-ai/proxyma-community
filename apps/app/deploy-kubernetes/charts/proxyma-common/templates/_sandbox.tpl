{{/*
The Office Files sandbox - the Python the assistant writes to make Word, Excel and PowerPoint files
runs here, never in the application's pod. The same confinement the compose packages give it:
a read-only system, memory-backed scratch space, no capabilities, no service account token, and a
NetworkPolicy that lets in the application alone and lets nothing out.

Its image is built from apps/app/deploy/sandbox/ and is not published, so office.image.repository
has to name a registry the cluster can pull from.
*/}}
{{- define "proxyma-common.sandbox" -}}
{{- if .Values.office.enabled -}}
{{- $fullname := include "proxyma-common.fullname" . -}}
{{- if not .Values.office.image.repository -}}
{{- fail "office.enabled needs office.image.repository: build apps/app/deploy/sandbox/ and push it where the cluster can pull it" -}}
{{- end -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $fullname }}-sandbox
  labels:
    {{- include "proxyma-common.labels" (dict "ctx" . "component" "sandbox") | nindent 4 }}
spec:
  replicas: 1
  selector:
    matchLabels:
      {{- include "proxyma-common.selectorLabels" (dict "ctx" . "component" "sandbox") | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "proxyma-common.selectorLabels" (dict "ctx" . "component" "sandbox") | nindent 8 }}
    spec:
      automountServiceAccountToken: false
      enableServiceLinks: false
      {{- with .Values.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      securityContext:
        runAsNonRoot: true
        runAsUser: 65534
        runAsGroup: 65534
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: sandbox
          image: {{ include "proxyma-common.image" (dict "image" .Values.office.image "default" .Chart.AppVersion) }}
          imagePullPolicy: {{ .Values.office.image.pullPolicy | default "IfNotPresent" }}
          ports:
            - name: http
              containerPort: 8080
          env:
            - name: SANDBOX_TOKEN
              valueFrom:
                secretKeyRef:
                  name: {{ include "proxyma-common.secretName" . }}
                  key: sandbox-token
          readinessProbe:
            httpGet:
              path: /health
              port: http
            periodSeconds: 15
          livenessProbe:
            httpGet:
              path: /health
              port: http
            periodSeconds: 30
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: [ALL]
          resources:
            {{- toYaml .Values.office.resources | nindent 12 }}
          volumeMounts:
            - name: tmp
              mountPath: /tmp
            - name: work
              mountPath: /work
      volumes:
        - name: tmp
          emptyDir:
            medium: Memory
            sizeLimit: 128Mi
        - name: work
          emptyDir:
            medium: Memory
            sizeLimit: 512Mi
---
apiVersion: v1
kind: Service
metadata:
  name: {{ $fullname }}-sandbox
  labels:
    {{- include "proxyma-common.labels" (dict "ctx" . "component" "sandbox") | nindent 4 }}
spec:
  type: ClusterIP
  selector:
    {{- include "proxyma-common.selectorLabels" (dict "ctx" . "component" "sandbox") | nindent 4 }}
  ports:
    - name: http
      port: 8080
      targetPort: http
{{- if .Values.networkPolicy.enabled }}
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: {{ $fullname }}-sandbox
  labels:
    {{- include "proxyma-common.labels" (dict "ctx" . "component" "sandbox") | nindent 4 }}
spec:
  podSelector:
    matchLabels:
      {{- include "proxyma-common.selectorLabels" (dict "ctx" . "component" "sandbox") | nindent 6 }}
  policyTypes: [Ingress, Egress]
  ingress:
    - from:
        - podSelector:
            matchLabels:
              {{- include "proxyma-common.selectorLabels" (dict "ctx" . "component" "app") | nindent 14 }}
      ports:
        - port: 8080
          protocol: TCP
  # No egress rule: the sandbox reaches nothing, DNS included.
  egress: []
{{- end }}
{{- end -}}
{{- end -}}
