import { connect } from 'cloudflare:sockets';

const VERSION = '32.6.0';
const SNAPSHOT_KEY = 'snapshot:v34';
const CHANNELS_KEY = 'channels:v34';
const DEFAULT_CHANNELS = ['farahvpn'];
const SUPPORTED_SCHEMES = [
  'vless', 'vmess', 'trojan', 'ssr', 'ss', 'shadowsocks',
  'hy2', 'hysteria2', 'hysteria', 'tuic', 'wg', 'wireguard',
  'ssh', 'openssh'
];
const UDP_NATIVE = new Set(['hy2', 'hysteria2', 'hysteria', 'tuic', 'wg', 'wireguard']);
const TCP_CONCURRENCY = 4;
const GEO_TTL_SECONDS = 7 * 24 * 60 * 60;

export default {
  async fetch(request, env, ctx) {
    try {
      const url = new URL(request.url);
      if (request.method === 'OPTIONS') return cors(new Response(null, { status: 204 }));

      if (url.pathname === '/' || url.pathname === '/health') {
        return json({
          ok: true,
          service: 'Trivox Community Worker',
          version: VERSION,
          admin: '/admin',
          public: {
            text: '/api/public/configs.txt?mode=advanced',
            json: '/api/public/configs.json?mode=advanced',
            status: '/api/public/status'
          }
        });
      }

      if (url.pathname === '/admin') return html(adminPage());

      if (url.pathname === '/api/public/status') {
        const snapshot = await loadSnapshot(env);
        const channels = await getChannels(env);
        return publicJson({
          ok: true,
          version: VERSION,
          channels,
          refreshedAt: snapshot?.refreshedAt || null,
          lastAttemptAt: snapshot?.lastAttemptAt || null,
          count: snapshot?.items?.length || 0,
          reports: snapshot?.reports || [],
          stale: Boolean(snapshot?.stale),
          lastError: snapshot?.lastError || ''
        });
      }

      if (url.pathname === '/api/public/configs.txt') {
        const snapshot = await ensureSnapshot(env);
        const mode = normalizeMode(url.searchParams.get('mode'));
        const channel = normalizeOptionalChannel(url.searchParams.get('channel'));
        const items = selectPublicItems(snapshot, mode, channel, env);
        return cors(new Response(items.map(x => x.uri).join('\n') + (items.length ? '\n' : ''), {
          headers: {
            'content-type': 'text/plain; charset=utf-8',
            'cache-control': 'no-store, max-age=0',
            'x-trivox-count': String(items.length),
            'x-trivox-refreshed-at': snapshot?.refreshedAt || ''
          }
        }));
      }

      if (url.pathname === '/api/public/configs.json') {
        const snapshot = await ensureSnapshot(env);
        const mode = normalizeMode(url.searchParams.get('mode'));
        const channel = normalizeOptionalChannel(url.searchParams.get('channel'));
        const items = selectPublicItems(snapshot, mode, channel, env);
        return publicJson({
          ok: true,
          version: VERSION,
          mode,
          refreshedAt: snapshot?.refreshedAt || null,
          count: items.length,
          items
        });
      }

      if (url.pathname.startsWith('/api/admin/')) {
        if (!isAdmin(request, env)) return json({ ok: false, error: 'unauthorized' }, 401);

        if (url.pathname === '/api/admin/channels' && request.method === 'GET') {
          return json({ ok: true, channels: await getChannels(env) });
        }

        if (url.pathname === '/api/admin/channels' && request.method === 'PUT') {
          const body = await request.json().catch(() => ({}));
          const channels = normalizeChannels(body.channels || []);
          if (!channels.length) return json({ ok: false, error: 'at_least_one_channel_required' }, 400);
          await env.TRIVOX_KV.put(CHANNELS_KEY, JSON.stringify(channels));
          ctx.waitUntil(refreshAll(env, 'channels_changed'));
          return json({ ok: true, channels });
        }

        if (url.pathname === '/api/admin/sync' && request.method === 'POST') {
          const snapshot = await refreshAll(env, 'manual');
          return json({
            ok: true,
            refreshedAt: snapshot.refreshedAt,
            count: snapshot.items.length,
            reports: snapshot.reports,
            stale: Boolean(snapshot.stale)
          });
        }

        if (url.pathname === '/api/admin/snapshot' && request.method === 'GET') {
          return json({ ok: true, snapshot: await loadSnapshot(env) });
        }
      }

      return json({ ok: false, error: 'not_found' }, 404);
    } catch (error) {
      console.error('fetch handler failed', error);
      return json({ ok: false, error: compactError(error) }, 500);
    }
  },

  async scheduled(controller, env, ctx) {
    ctx.waitUntil(refreshAll(env, `cron:${controller.cron || 'unknown'}`));
  }
};

async function ensureSnapshot(env) {
  const existing = await loadSnapshot(env);
  if (existing?.items?.length) return existing;
  return await refreshAll(env, 'bootstrap');
}

async function refreshAll(env, reason) {
  const startedAt = Date.now();
  const channels = await getChannels(env);
  const previous = await loadSnapshot(env);
  const previousByChannel = new Map();
  for (const item of previous?.items || []) {
    const key = item.sourceChannel || '';
    if (!previousByChannel.has(key)) previousByChannel.set(key, []);
    previousByChannel.get(key).push(item);
  }

  const allItems = [];
  const reports = [];
  const resolveCache = new Map();
  const geoCache = new Map();

  for (const channel of channels) {
    const report = {
      channel,
      posts: 0,
      discovered: 0,
      parsed: 0,
      tcpHealthy: 0,
      resolveOnly: 0,
      udpSyntaxVerified: 0,
      keptFromPrevious: 0,
      error: ''
    };

    try {
      const posts = await fetchLastTelegramPosts(channel, numberEnv(env.MAX_POSTS_PER_CHANNEL, 30, 10, 30));
      report.posts = posts.length;
      const rawCandidates = [];
      for (const post of posts) {
        for (const uri of extractProxyUris(post.raw)) {
          rawCandidates.push({ uri, postId: post.id, sourceChannel: channel });
        }
      }

      const deduped = dedupeCandidates(rawCandidates)
        .slice(0, numberEnv(env.MAX_CONFIGS_PER_CHANNEL, 100, 10, 180));
      report.discovered = deduped.length;

      const parsed = deduped
        .map(candidate => ({ ...candidate, parsed: parseProxy(candidate.uri) }))
        .filter(x => x.parsed?.host && x.parsed?.port);
      report.parsed = parsed.length;

      const checked = await mapLimit(parsed, TCP_CONCURRENCY, async candidate => {
        const p = candidate.parsed;
        const udpOnly = isUdpTransport(p);
        let probe = { ok: false, ms: null, kind: udpOnly ? 'udp-unavailable' : 'tcp-failed', remoteAddress: '' };
        if (!udpOnly) {
          probe = await tcpProbe(p.host, p.port, numberEnv(env.TCP_TIMEOUT_MS, 2200, 500, 5000));
        }

        let ip = remoteAddressIp(probe.remoteAddress);
        if (!ip) {
          if (!resolveCache.has(p.host)) {
            resolveCache.set(p.host, resolveTargetIp(p.host));
          }
          ip = await resolveCache.get(p.host);
        }
        if (!ip) return null;

        if (!geoCache.has(ip)) {
          geoCache.set(ip, geoForIp(env, ip));
        }
        const geo = await geoCache.get(ip);
        const flag = countryFlag(geo.countryCode);
        const originalRemark = compactRemark(p.remark || `${p.protocol.toUpperCase()} ${p.host}`);
        const remark = buildRemark(flag, geo.country, ip, originalRemark, channel);
        const uri = rewriteRemark(candidate.uri, p.protocol, remark);
        const health = udpOnly ? 'syntax-resolve' : (probe.ok ? 'tcp-ok' : 'resolve-only');
        const score = scoreItem(health, probe.ms, p);

        if (udpOnly) report.udpSyntaxVerified += 1;
        else if (probe.ok) report.tcpHealthy += 1;
        else report.resolveOnly += 1;

        return {
          sourceChannel: channel,
          postId: candidate.postId,
          protocol: p.protocol,
          host: p.host,
          port: p.port,
          ip: ip || '',
          country: geo.country || '',
          countryCode: geo.countryCode || '',
          flag,
          health,
          tcpMs: probe.ms,
          transport: p.transport || '',
          remark,
          uri,
          score,
          checkedAt: new Date().toISOString()
        };
      });

      allItems.push(...checked.filter(Boolean));
    } catch (error) {
      report.error = compactError(error);
      const fallback = previousByChannel.get(channel) || [];
      report.keptFromPrevious = fallback.length;
      allItems.push(...fallback.map(item => ({ ...item, staleSource: true })));
    }

    reports.push(report);
  }

  const finalItems = dedupeItems(allItems)
    .sort((a, b) => (b.score || 0) - (a.score || 0));

  const hasFreshSuccess = reports.some(r => !r.error && (r.tcpHealthy + r.resolveOnly + r.udpSyntaxVerified) > 0);
  const snapshot = {
    version: VERSION,
    reason,
    refreshedAt: hasFreshSuccess ? new Date().toISOString() : (previous?.refreshedAt || null),
    lastAttemptAt: new Date().toISOString(),
    durationMs: Date.now() - startedAt,
    channels,
    reports,
    stale: !hasFreshSuccess,
    lastError: hasFreshSuccess ? '' : reports.map(r => r.error).filter(Boolean).join(' | ').slice(0, 600),
    items: finalItems.length ? finalItems : (previous?.items || [])
  };

  await env.TRIVOX_KV.put(SNAPSHOT_KEY, JSON.stringify(snapshot));
  return snapshot;
}

async function fetchLastTelegramPosts(channel, limit) {
  const result = new Map();
  let before = null;
  let directError = '';

  try {
    for (let page = 0; page < 4 && result.size < limit; page += 1) {
      const url = new URL(`https://t.me/s/${encodeURIComponent(channel)}`);
      if (before) url.searchParams.set('before', String(before));
      url.searchParams.set('trivox', String(Date.now()));
      const response = await fetch(url, {
        headers: {
          'user-agent': 'Mozilla/5.0 (Android) TrivoxCommunityWorker/32.3',
          'accept': 'text/html,application/xhtml+xml'
        },
        cf: { cacheTtl: 0, cacheEverything: false }
      });
      if (!response.ok) throw new Error(`telegram_http_${response.status}`);
      const htmlText = await response.text();
      const posts = parseTelegramPostSegments(htmlText, channel);
      if (!posts.length) break;
      for (const post of posts) result.set(post.id, post);
      const minId = Math.min(...posts.map(x => x.id));
      if (!Number.isFinite(minId) || minId <= 1 || minId === before) break;
      before = minId;
    }
  } catch (error) {
    directError = compactError(error);
  }

  const ordered = [...result.values()].sort((a, b) => b.id - a.id).slice(0, limit);
  if (ordered.length) return ordered;

  // Bounded relay fallback for HTTP 451/filtering or Telegram preview changes.
  const relay = await fetch(`https://r.jina.ai/https://t.me/s/${encodeURIComponent(channel)}`, {
    headers: { 'user-agent': 'TrivoxCommunityWorker/32.3' },
    cf: { cacheTtl: 0, cacheEverything: false }
  });
  if (!relay.ok) {
    throw new Error(`telegram_routes_failed:${directError || 'direct_empty'};relay_http_${relay.status}`);
  }
  const text = await relay.text();
  return [{ id: Date.now(), raw: text }];
}

function parseTelegramPostSegments(htmlText, channel) {
  const markers = [];
  const escaped = escapeRegExp(channel);
  const re = new RegExp(`data-post=["']${escaped}\\/(\\d+)["']`, 'gi');
  let match;
  while ((match = re.exec(htmlText)) !== null) {
    markers.push({ id: Number(match[1]), index: match.index });
  }
  const out = [];
  for (let i = 0; i < markers.length; i += 1) {
    const start = markers[i].index;
    const end = i + 1 < markers.length ? markers[i + 1].index : Math.min(htmlText.length, start + 180000);
    out.push({ id: markers[i].id, raw: decodeHtmlEntities(htmlText.slice(start, end)) });
  }
  return out;
}

function extractProxyUris(text) {
  const schemes = SUPPORTED_SCHEMES.join('|');
  const re = new RegExp(String.raw`(?:${schemes}):\/\/[^\s<>"']+`, 'gi');
  const found = [];
  let match;
  while ((match = re.exec(text)) !== null) {
    const cleaned = cleanUri(match[0]);
    if (cleaned.length >= 8 && cleaned.length <= 32768) found.push(cleaned);
  }
  return [...new Set(found)];
}

function cleanUri(value) {
  return decodeHtmlEntities(String(value || ''))
    .replace(/[`\)\]}>.,;]+$/g, '')
    .trim();
}

function dedupeCandidates(candidates) {
  const map = new Map();
  for (const item of candidates) {
    const key = stripRemark(item.uri);
    if (!map.has(key)) map.set(key, item);
  }
  return [...map.values()];
}

function dedupeItems(items) {
  const map = new Map();
  for (const item of items) {
    const key = stripRemark(item.uri);
    const old = map.get(key);
    if (!old || (item.score || 0) > (old.score || 0)) map.set(key, item);
  }
  return [...map.values()];
}

function parseProxy(uri) {
  try {
    const protocol = String(uri.split('://', 1)[0]).toLowerCase();
    if (!SUPPORTED_SCHEMES.includes(protocol)) return null;

    if (protocol === 'vmess') {
      const body = uri.slice('vmess://'.length).split('#')[0].trim();
      const obj = JSON.parse(base64ToUtf8(body));
      const host = normalizeHost(obj.add || obj.host || '');
      const port = toPort(obj.port);
      if (!host || !port) return null;
      return {
        protocol,
        host,
        port,
        remark: obj.ps || '',
        transport: String(obj.net || obj.type || '').toLowerCase(),
        rawObject: obj
      };
    }

    if (protocol === 'ssr') return parseSsr(uri);
    if (protocol === 'ss' || protocol === 'shadowsocks') return parseShadowsocks(uri, protocol);

    const u = new URL(uri);
    const host = normalizeHost(u.hostname);
    const port = toPort(u.port || defaultPort(protocol));
    if (!host || !port) return null;
    const transport = String(
      u.searchParams.get('type') ||
      u.searchParams.get('network') ||
      u.searchParams.get('net') ||
      u.searchParams.get('transport') || ''
    ).toLowerCase();
    return {
      protocol,
      host,
      port,
      remark: decodeURIComponentSafe(u.hash.replace(/^#/, '')),
      transport
    };
  } catch {
    return null;
  }
}

function parseShadowsocks(uri, protocol) {
  try {
    const withoutFragment = uri.split('#')[0];
    try {
      const u = new URL(withoutFragment);
      if (u.hostname && u.port) {
        return {
          protocol,
          host: normalizeHost(u.hostname),
          port: toPort(u.port),
          remark: decodeURIComponentSafe(new URL(uri).hash.replace(/^#/, '')),
          transport: 'tcp'
        };
      }
    } catch {}

    let payload = withoutFragment.replace(/^(?:ss|shadowsocks):\/\//i, '').split('?')[0];
    payload = base64ToUtf8(payload);
    const at = payload.lastIndexOf('@');
    if (at < 0) return null;
    const endpoint = payload.slice(at + 1);
    const parsed = splitHostPort(endpoint);
    if (!parsed) return null;
    return {
      protocol,
      host: parsed.host,
      port: parsed.port,
      remark: decodeURIComponentSafe(uri.includes('#') ? uri.slice(uri.indexOf('#') + 1) : ''),
      transport: 'tcp'
    };
  } catch {
    return null;
  }
}

function parseSsr(uri) {
  try {
    const decoded = base64ToUtf8(uri.slice('ssr://'.length));
    const main = decoded.split('/?')[0];
    const match = main.match(/^(.*):(\d+):([^:]+):([^:]+):([^:]+):(.+)$/);
    if (!match) return null;
    const host = normalizeHost(match[1]);
    const port = toPort(match[2]);
    if (!host || !port) return null;
    let remark = '';
    const query = decoded.includes('/?') ? decoded.split('/?')[1] : '';
    const params = new URLSearchParams(query);
    if (params.get('remarks')) remark = base64ToUtf8(params.get('remarks'));
    return { protocol: 'ssr', host, port, remark, transport: 'tcp' };
  } catch {
    return null;
  }
}

function splitHostPort(endpoint) {
  const v = endpoint.trim();
  if (v.startsWith('[')) {
    const end = v.indexOf(']');
    if (end < 0 || v[end + 1] !== ':') return null;
    const host = v.slice(1, end);
    const port = toPort(v.slice(end + 2));
    return port ? { host, port } : null;
  }
  const idx = v.lastIndexOf(':');
  if (idx <= 0) return null;
  const host = normalizeHost(v.slice(0, idx));
  const port = toPort(v.slice(idx + 1));
  return host && port ? { host, port } : null;
}

function isUdpTransport(parsed) {
  if (UDP_NATIVE.has(parsed.protocol)) return true;
  return ['udp', 'quic', 'kcp', 'mkcp'].includes(String(parsed.transport || '').toLowerCase());
}

async function tcpProbe(host, port, timeoutMs) {
  let socket;
  const started = Date.now();
  try {
    socket = connect({ hostname: host, port }, { secureTransport: 'off', allowHalfOpen: false });
    const info = await Promise.race([
      socket.opened,
      new Promise((_, reject) => setTimeout(() => reject(new Error('tcp_timeout')), timeoutMs))
    ]);
    const ms = Date.now() - started;
    await socket.close().catch(() => {});
    return { ok: true, ms, kind: 'tcp-ok', remoteAddress: info?.remoteAddress || '' };
  } catch (error) {
    if (socket) await socket.close().catch(() => {});
    return { ok: false, ms: null, kind: compactError(error), remoteAddress: '' };
  }
}

function remoteAddressIp(value) {
  const raw = String(value || '').trim();
  if (!raw) return '';
  if (raw.startsWith('[')) {
    const end = raw.indexOf(']');
    return end > 1 ? raw.slice(1, end) : '';
  }
  const v4 = raw.match(/^(\d{1,3}(?:\.\d{1,3}){3})(?::\d+)?$/);
  if (v4) return v4[1];
  if (raw.includes(':') && !raw.match(/:\d+$/)) return raw;
  return '';
}

async function resolveTargetIp(host) {
  const normalized = normalizeHost(host);
  if (isIpLiteral(normalized)) return normalized;
  const v4 = await doh(normalized, 'A');
  if (v4) return v4;
  return await doh(normalized, 'AAAA');
}

async function doh(host, type) {
  try {
    const response = await fetch(`https://cloudflare-dns.com/dns-query?name=${encodeURIComponent(host)}&type=${type}`, {
      headers: { accept: 'application/dns-json' },
      cf: { cacheTtl: 60, cacheEverything: true }
    });
    if (!response.ok) return '';
    const body = await response.json();
    const wanted = type === 'A' ? 1 : 28;
    const answer = (body.Answer || []).find(x => x.type === wanted && typeof x.data === 'string');
    return answer?.data || '';
  } catch {
    return '';
  }
}

async function geoForIp(env, ip) {
  if (!ip) return { countryCode: '', country: '' };
  const key = `geo:${ip}`;
  try {
    const cached = await env.TRIVOX_KV.get(key, 'json');
    if (cached?.countryCode) return cached;
  } catch {}

  let code = '';
  try {
    const response = await fetch(`https://api.country.is/${encodeURIComponent(ip)}`, {
      headers: { 'user-agent': 'TrivoxCommunityWorker/32.3' },
      cf: { cacheTtl: 3600, cacheEverything: true }
    });
    if (response.ok) {
      const body = await response.json();
      code = String(body.country || '').toUpperCase();
    }
  } catch {}

  const result = {
    countryCode: /^[A-Z]{2}$/.test(code) ? code : '',
    country: countryName(code)
  };
  if (result.countryCode) {
    await env.TRIVOX_KV.put(key, JSON.stringify(result), { expirationTtl: GEO_TTL_SECONDS }).catch(() => {});
  }
  return result;
}

function buildRemark(flag, country, ip, original, channel) {
  const geo = [flag || '🏳️', country || 'Unknown'].join(' ');
  const base = `${geo} • ${ip || 'unknown-ip'} • ${original || 'Config'}`;
  return compactRemark(`${base} • @${channel}`);
}

function rewriteRemark(uri, protocol, remark) {
  try {
    if (protocol === 'vmess') {
      const payload = uri.slice('vmess://'.length).split('#')[0].trim();
      const obj = JSON.parse(base64ToUtf8(payload));
      obj.ps = remark;
      return `vmess://${utf8ToBase64(JSON.stringify(obj))}`;
    }

    if (protocol === 'ssr') {
      const decoded = base64ToUtf8(uri.slice('ssr://'.length));
      const [main, query = ''] = decoded.split('/?');
      const params = new URLSearchParams(query);
      params.set('remarks', utf8ToBase64(remark, true));
      return `ssr://${utf8ToBase64(`${main}/?${params.toString()}`, true)}`;
    }

    try {
      const u = new URL(uri);
      u.hash = remark;
      return u.toString();
    } catch {
      return `${stripFragment(uri)}#${encodeURIComponent(remark)}`;
    }
  } catch {
    return uri;
  }
}

function selectPublicItems(snapshot, mode, channel, env) {
  let items = [...(snapshot?.items || [])];
  if (channel) items = items.filter(x => x.sourceChannel === channel);
  if (mode === 'simple') {
    const tcp = items.filter(x => x.health === 'tcp-ok');
    return tcp.slice(0, numberEnv(env.SIMPLE_MAX_CONFIGS, 30, 5, 60));
  }
  return items.slice(0, numberEnv(env.ADVANCED_MAX_CONFIGS, 120, 10, 240));
}

async function getChannels(env) {
  try {
    const saved = await env.TRIVOX_KV.get(CHANNELS_KEY, 'json');
    const normalized = normalizeChannels(saved || []);
    return normalized.length ? normalized : DEFAULT_CHANNELS;
  } catch {
    return DEFAULT_CHANNELS;
  }
}

async function loadSnapshot(env) {
  try {
    return await env.TRIVOX_KV.get(SNAPSHOT_KEY, 'json');
  } catch {
    return null;
  }
}

function normalizeChannels(values) {
  const result = [];
  for (const raw of Array.isArray(values) ? values : []) {
    const v = normalizeChannel(raw);
    if (v && !result.includes(v)) result.push(v);
  }
  return result.slice(0, 12);
}

function normalizeChannel(value) {
  let v = String(value || '').trim();
  v = v.replace(/^https?:\/\/t\.me\//i, '').replace(/^t\.me\//i, '').replace(/^@/, '');
  v = v.split(/[/?#]/, 1)[0].toLowerCase();
  return /^[a-z0-9_]{5,32}$/.test(v) ? v : '';
}

function normalizeOptionalChannel(value) {
  if (!value || String(value).toLowerCase() === 'all') return '';
  return normalizeChannel(value);
}

function normalizeMode(value) {
  return String(value || '').toLowerCase() === 'simple' ? 'simple' : 'advanced';
}

function defaultPort(protocol) {
  if (protocol === 'trojan') return 443;
  if (protocol === 'ssh' || protocol === 'openssh') return 22;
  if (protocol === 'wireguard' || protocol === 'wg') return 51820;
  if (protocol === 'ss' || protocol === 'shadowsocks' || protocol === 'ssr') return 8388;
  return 443;
}

function toPort(value) {
  const p = Number(value);
  return Number.isInteger(p) && p >= 1 && p <= 65535 ? p : 0;
}

function normalizeHost(value) {
  return String(value || '').trim().replace(/^\[/, '').replace(/\]$/, '');
}

function isIpLiteral(value) {
  return /^\d{1,3}(?:\.\d{1,3}){3}$/.test(value) || value.includes(':');
}

function compactRemark(value) {
  return String(value || '').replace(/\s+/g, ' ').trim().slice(0, 180);
}

function stripRemark(uri) {
  return stripFragment(String(uri || '')).trim();
}

function stripFragment(uri) {
  const idx = uri.indexOf('#');
  return idx >= 0 ? uri.slice(0, idx) : uri;
}

function scoreItem(health, latency, parsed) {
  let score = health === 'tcp-ok' ? 100000 : (health === 'syntax-resolve' ? 10000 : 5000);
  if (Number.isFinite(latency)) score -= Math.min(latency, 5000) * 10;
  if (parsed.port === 443) score += 500;
  if (['ws', 'grpc', 'httpupgrade', 'xhttp'].includes(parsed.transport)) score += 120;
  return score;
}

function countryFlag(code) {
  const cc = String(code || '').toUpperCase();
  if (!/^[A-Z]{2}$/.test(cc)) return '🏳️';
  return String.fromCodePoint(...[...cc].map(ch => 127397 + ch.charCodeAt(0)));
}

function countryName(code) {
  const cc = String(code || '').toUpperCase();
  if (!/^[A-Z]{2}$/.test(cc)) return '';
  try {
    return new Intl.DisplayNames(['en'], { type: 'region' }).of(cc) || cc;
  } catch {
    return cc;
  }
}

function base64ToUtf8(input) {
  let s = String(input || '').replace(/-/g, '+').replace(/_/g, '/').replace(/\s+/g, '');
  while (s.length % 4) s += '=';
  const binary = atob(s);
  const bytes = Uint8Array.from(binary, ch => ch.charCodeAt(0));
  return new TextDecoder().decode(bytes);
}

function utf8ToBase64(value, urlSafe = false) {
  const bytes = new TextEncoder().encode(String(value));
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  let out = btoa(binary);
  if (urlSafe) out = out.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
  return out;
}

function decodeURIComponentSafe(value) {
  try { return decodeURIComponent(value); } catch { return value; }
}

function decodeHtmlEntities(value) {
  return String(value || '')
    .replace(/&amp;/gi, '&')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;|&apos;/gi, "'")
    .replace(/&lt;/gi, '<')
    .replace(/&gt;/gi, '>')
    .replace(/&nbsp;/gi, ' ')
    .replace(/&#(x?[0-9a-f]+);/gi, (_, raw) => {
      const n = raw.toLowerCase().startsWith('x') ? parseInt(raw.slice(1), 16) : parseInt(raw, 10);
      try { return Number.isFinite(n) ? String.fromCodePoint(n) : _; } catch { return _; }
    });
}

function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function numberEnv(value, fallback, min, max) {
  const n = Number(value);
  return Number.isFinite(n) ? Math.max(min, Math.min(max, Math.floor(n))) : fallback;
}

async function mapLimit(items, limit, worker) {
  const out = new Array(items.length);
  let cursor = 0;
  async function run() {
    while (true) {
      const index = cursor++;
      if (index >= items.length) return;
      out[index] = await worker(items[index], index);
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length || 1) }, run));
  return out;
}

function isAdmin(request, env) {
  const auth = request.headers.get('authorization') || '';
  return Boolean(env.ADMIN_SECRET) && auth === `Bearer ${env.ADMIN_SECRET}`;
}

function compactError(error) {
  return String(error?.message || error || 'unknown_error').replace(/\s+/g, ' ').slice(0, 240);
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data, null, 2), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' }
  });
}

function publicJson(data, status = 200) {
  return cors(json(data, status));
}

function html(value) {
  return new Response(value, {
    headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' }
  });
}

function cors(response) {
  const headers = new Headers(response.headers);
  headers.set('access-control-allow-origin', '*');
  headers.set('access-control-allow-methods', 'GET,OPTIONS');
  headers.set('access-control-allow-headers', 'content-type');
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

function adminPage() {
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Trivox Community Worker</title>
<style>
:root{font-family:system-ui,-apple-system,Segoe UI,sans-serif;color-scheme:light dark}body{margin:0;background:#0b1220;color:#e8eef7}.wrap{max-width:760px;margin:auto;padding:20px}.card{background:#121c2d;border:1px solid #29364b;border-radius:18px;padding:16px;margin:12px 0}h1{font-size:24px;margin:8px 0 4px}h2{font-size:17px}input,button{font:inherit;border-radius:12px;border:1px solid #42516a;padding:11px 12px}input{background:#0c1524;color:#fff;width:100%;box-sizing:border-box}button{background:#147d9a;color:#fff;border:0;font-weight:700;cursor:pointer}.secondary{background:#29364b}.row{display:flex;gap:8px;flex-wrap:wrap}.row>*{flex:1}.chips{display:flex;gap:8px;flex-wrap:wrap;margin:10px 0}.chip{background:#243149;border-radius:999px;padding:7px 10px}.muted{color:#9fb0c7;font-size:13px}pre{white-space:pre-wrap;overflow:auto;background:#08101c;padding:12px;border-radius:12px}a{color:#64d4ef}</style></head>
<body><div class="wrap"><h1>Trivox Community Worker</h1><div class="muted">v${VERSION} · admin console</div>
<div class="card"><h2>Admin secret</h2><input id="secret" type="password" placeholder="Paste ADMIN_SECRET"><div class="row" style="margin-top:8px"><button onclick="saveSecret()">Save locally</button><button class="secondary" onclick="loadAll()">Connect</button></div><div class="muted">The secret is kept only in this browser's localStorage and sent as an Authorization header.</div></div>
<div class="card"><h2>Telegram channels</h2><div id="chips" class="chips"></div><div class="row"><input id="newChannel" placeholder="@FarahVPN"><button onclick="addChannel()">Add</button></div><div class="row" style="margin-top:8px"><button onclick="saveChannels()">Save channels</button><button class="secondary" onclick="syncNow()">Sync now</button></div></div>
<div class="card"><h2>Status</h2><pre id="status">Not connected</pre><div class="row"><a href="/api/public/configs.txt?mode=simple" target="_blank">Simple TXT</a><a href="/api/public/configs.txt?mode=advanced" target="_blank">Advanced TXT</a><a href="/api/public/configs.json?mode=advanced" target="_blank">JSON</a></div></div>
</div><script>
let channels=[];const secretEl=document.getElementById('secret');secretEl.value=localStorage.getItem('trivox_admin_secret')||'';
function auth(){return {'Authorization':'Bearer '+secretEl.value,'Content-Type':'application/json'}}
function saveSecret(){localStorage.setItem('trivox_admin_secret',secretEl.value);loadAll()}
function render(){document.getElementById('chips').innerHTML=channels.map((x,i)=>'<span class="chip">@'+x+' <b style="cursor:pointer" onclick="removeChannel('+i+')">×</b></span>').join('')}
function removeChannel(i){channels.splice(i,1);render()}
function addChannel(){let v=document.getElementById('newChannel').value.trim().replace(/^@/,'').replace(/^https?:\\/\\/t\\.me\\//i,'');if(v){channels.push(v);channels=[...new Set(channels)];render();document.getElementById('newChannel').value=''}}
async function api(path,opt={}){let r=await fetch(path,{...opt,headers:{...auth(),...(opt.headers||{})}});let j=await r.json();if(!r.ok)throw new Error(j.error||('HTTP '+r.status));return j}
async function loadAll(){try{let c=await api('/api/admin/channels');channels=c.channels||[];render();let s=await fetch('/api/public/status').then(r=>r.json());document.getElementById('status').textContent=JSON.stringify(s,null,2)}catch(e){document.getElementById('status').textContent='ERROR: '+e.message}}
async function saveChannels(){try{await api('/api/admin/channels',{method:'PUT',body:JSON.stringify({channels})});await loadAll()}catch(e){alert(e.message)}}
async function syncNow(){document.getElementById('status').textContent='Syncing…';try{let s=await api('/api/admin/sync',{method:'POST'});document.getElementById('status').textContent=JSON.stringify(s,null,2);await loadAll()}catch(e){document.getElementById('status').textContent='ERROR: '+e.message}}
</script></body></html>`;
}
