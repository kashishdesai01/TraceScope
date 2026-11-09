{{- define "tracescope.name" -}}
{{- printf "%s-tracescope" .Release.Name | trunc 45 | trimSuffix "-" -}}
{{- end -}}
{{- define "tracescope.labels" -}}
app.kubernetes.io/name: tracescope
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}
{{- define "tracescope.appSecurity" -}}
runAsNonRoot: true
runAsUser: 10001
runAsGroup: 10001
fsGroup: 10001
{{- end -}}
{{- define "tracescope.containerSecurity" -}}
allowPrivilegeEscalation: false
capabilities:
  drop: [ALL]
{{- end -}}
