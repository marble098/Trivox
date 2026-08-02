# Security and privacy

Trivox collects and transmits no analytics, telemetry, advertising identifiers, crash reports, contacts, messages, location, media, or credentials. Configurations, subscription metadata, settings, generated JSON, logs, and session counters stay in app-private storage unless the user explicitly exports a sanitized diagnostic file.

The app does not disable TLS verification, accept all certificates, use WebView networking, silently download executable updates, or store GitHub credentials. Subscription URLs must be HTTPS; non-HTTPS redirects, HTML login/error responses, empty responses, and responses over 4 MiB are rejected. A failed refresh leaves previous configurations untouched.

Core preparation is outside the app. The scripts use official repositories, bounded retries/timeouts, exact release assets, digest verification when supplied, SHA-256 recording, safe ZIP inspection, environment-only `GITHUB_TOKEN`, and temporary-directory cleanup.

Diagnostics redact share URIs, UUIDs, passwords, tokens, and key-like fields. Debug logging is disabled by default and the log is capped at 256 KiB.

Report security issues privately to the repository owner. Include the Trivox version, Android version, sanitized diagnostics, and reproducible steps; do not include live configurations or credentials.
