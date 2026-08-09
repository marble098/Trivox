# Trivox Community Worker v32.6

Managed Cloudflare Worker backend for Trivox community configurations.

- Scheduled refresh: every 10 minutes
- Public Simple feed: `https://trivoxworker.toriavolx.workers.dev/api/public/configs.txt?mode=simple`
- Public Advanced feed: `https://trivoxworker.toriavolx.workers.dev/api/public/configs.txt?mode=advanced`
- Public JSON/status endpoints under `/api/public/`
- Admin console: `https://trivoxworker.toriavolx.workers.dev/admin`

`ADMIN_SECRET` is a Cloudflare Worker secret and is intentionally **not** stored in this repository.
The Cloudflare API token used by the Termux installer is also never committed.
