# Trivox Part 1 — Foundation & Hardening

This patch is intentionally small and regression-resistant.

## Included

- Order-independent Android default-network handover for VPN mode.
- Unit-tested `NetworkHandoverCoordinator`.
- Removal of the stale `google.com` cleartext exception.
- Stronger diagnostics redaction for SSH/proxy URIs, authorization headers,
  tokens and key-like fields.
- Unit coverage for diagnostics redaction.
- Repository audit guards that prevent the old network race and cleartext
  exception from silently returning.

## Deliberately deferred

The following architectural work is intentionally **not** mixed into this
stability patch:

- full `LegacyLayoutBridge` removal;
- ViewModel/StateFlow UI migration;
- Room/DataStore migration;
- WorkManager subscription refresh;
- broad service/controller refactors.

Those belong in later isolated parts so any regression has a small search
surface.

Generated against base commit: `598d2a3dfe5879d1322f6634b535962a1f65ae83`
