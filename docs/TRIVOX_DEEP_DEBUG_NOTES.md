# Trivox Deep Debug Notes

## Root cause observed

- Version 0.2.95 shows native runtime startup breadcrumbs for sing-box/mihomo.
- The failure remains `no verified HTTPS traffic crossed the Android VPN route (socketexception)`.
- The app must be excluded from its own VPN when sing-box/mihomo are used as child processes; otherwise outbound sockets can loop into the VPN.
- Once the app is excluded, app-level `ACTIVE_NETWORK` probes are no longer a reliable VPN route proof.

## Fix strategy

- Native-core VPN uses a two-layer proof:
  1. Native core local mixed proxy is verified with SOCKS/HTTP proof.
  2. Xray TUN bridge is validated and started only as Android packet bridge.
- Xray-only VPN keeps the strict ACTIVE_NETWORK proof.
- Smart Core no longer causes self-bypass by itself. Bypass is derived from the resolved runtime core.
- A static repo doctor is added to catch recurring patch regressions.

## Manual smoke test

1. Settings → Core: sing-box
2. Mode: VPN
3. Connect a known-good VLESS/VMess profile
4. Open Diagnostics
5. Confirm:
   - `Starting real outbound core for VPN: sing-box`
   - `Starting Xray Android TUN bridge for sing-box`
   - `Native VPN bridge proof passed`

## v4 workflow normalization

GitHub Actions runs the older `tools/patch-trivox-multicore.py` before the deep debug doctor. That legacy patch may reinsert quick-core setup lines. v4 updates `tools/fix-quick-core-button.py` and re-runs `tools/apply-deep-debug-fix.py` immediately before the doctor so the runner checks normalized code, not the transient legacy-patched state.
