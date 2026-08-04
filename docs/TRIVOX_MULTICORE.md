# Trivox multicore patch

This patch adds a selectable core model for Xray, sing-box, and mihomo, plus a GitHub Actions workflow that downloads the newest prerelease assets when available and places the binaries under `app/src/main/assets/cores/<abi>/`.

The Android source keeps Xray/libXray as the full VPN-capable backend. The standalone sing-box and mihomo adapters are prepared for local mixed-proxy execution and validation. Android TUN/VPN operation for standalone binaries needs native file-descriptor plumbing that is not equivalent to libXray reflection.

Run `tools/patch-trivox-multicore.py` once after applying the package. Then run the `Trivox multicore binaries` workflow from Actions or with `gh workflow run trivox-multicore-binaries.yml`.
