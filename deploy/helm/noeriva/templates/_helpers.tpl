{{- define "noeriva.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "noeriva.fullname" -}}
{{- default (printf "%s-%s" .Release.Name (include "noeriva.name" .)) .Values.fullnameOverride | trunc 50 | trimSuffix "-" -}}
{{- end -}}
{{- define "noeriva.labels" -}}
app.kubernetes.io/name: {{ include "noeriva.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | quote }}
{{- end -}}
{{- define "noeriva.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "noeriva.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
{{- define "noeriva.image" -}}
{{- if .digest -}}
{{- printf "%s@%s" .repository .digest -}}
{{- else -}}
{{- printf "%s:%s" .repository .tag -}}
{{- end -}}
{{- end -}}
