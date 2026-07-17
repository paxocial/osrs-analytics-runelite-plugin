# Testing the Catherby Analytics plugin end to end

This is the concrete path from "nothing running" to "rows landing in Postgres and
showing in the dashboard." It uses only things that exist today — no placeholder
steps.

Two repos are involved:

- **Catherby backend** — `/home/austin/projects/runescape/catherby`
- **This plugin** — `/home/austin/projects/runescape/plugin-hub/osrs-analytics-runelite-plugin`

---

## 1. Start the backend

The API listens on `:8000` and mounts the plugin routes under `/api/v1/plugin`.
Database credentials come from `catherby/.env` (`DATABASE_URL` /
`POSTGRES_*` — already populated for local Postgres).

```bash
cd /home/austin/projects/runescape/catherby
uvicorn api.main:app --host 0.0.0.0 --port 8000
# (equivalently: python -m api.main — the __main__ block runs uvicorn on :8000 with reload)
```

Sanity check (no auth required for the health surface):

```bash
curl -s http://localhost:8000/api/v1/plugin/status
# 401/403 without a key is expected here — it proves the route is mounted.
```

---

## 2. Mint a plugin API token

A real mint command exists — do **not** insert rows by hand.

```bash
cd /home/austin/projects/runescape/catherby
python scripts/mint_plugin_token.py mint --label "RuneLite" \
    --scopes plugin:ingest,plugin:read
```

Copy the printed token. In RuneLite, open the **Catherby Analytics** config and paste
it into **API key**. The key is sent only as the `X-API-Key` header and is never
logged.

---

## 3. Register the RSN (required)

The plugin batch endpoint resolves the account by RSN and returns **404** for an
unknown one (`api/endpoints/plugin.py::_resolve_identity` →
`resolve_account_id`). There is no account-create on the plugin path (that is
`POST /accounts`, still `501` until BP-C5).

The honest way to register today is to take one snapshot for the account —
`POST /snapshots/run` auto-creates the account row via
`snapshot_ingest._ensure_account`:

```bash
curl -s -X POST http://localhost:8000/snapshots/run \
    -H "Content-Type: application/json" \
    -d '{"player": "YOUR_RSN"}'
```

(That endpoint pulls the account's stats from the live OSRS hiscores, so use a real
display name.) Confirm the account exists:

```bash
psql "$DATABASE_URL" -c \
  "SELECT display_name FROM catherby.accounts WHERE normalized_lookup_name = lower('YOUR_RSN');"
```

If you skip this step the plugin does **not** lose your data: on 404 it keeps the
batch queued behind a long backoff and resumes automatically once the account
exists, then posts a one-time "connected — telemetry flowing" chat notice. The
panel shows **RSN not registered** in the meantime.

---

## 4. Launch RuneLite with the plugin

### Path A — dev harness (`./gradlew run`)

```bash
cd /home/austin/projects/runescape/plugin-hub/osrs-analytics-runelite-plugin
./gradlew run
```

This starts a real RuneLite client with the plugin loaded as a built-in (the
`AnalyticsPluginTest` harness on the test classpath). The task already passes the
two flags RuneLite requires for a built-in plugin: `-ea` (assertions) and
`--developer-mode`.

- **From WSL2:** needs an X server. WSLg (Windows 11) provides one automatically —
  the client window just opens. On older setups, run an X server on Windows (e.g.
  VcXsrv) and `export DISPLAY=:0` before `./gradlew run`. Verified locally: the
  client boots to the login screen under WSLg (llvmpipe GPU), plugin loaded, no
  exceptions.
- **From a Windows checkout:** run the same `gradlew run` in the Windows shell; the
  window opens natively with no display setup.

### Path B — `catherby plugin deploy` + the Desktop launcher (current operator flow)

```bash
cd /home/austin/projects/runescape/catherby
catherby plugin deploy
```

This builds the plugin (`./gradlew build`, in WSL) and atomically deploys the
jar into the Windows side-load directory
(`%USERPROFILE%\.runelite\sideloaded-plugins\`, i.e.
`/mnt/c/Users/<you>/.runelite/sideloaded-plugins` from WSL): copy to a `.tmp`
name, verify size + checksum against the source, remove any other-versioned
`osrs-analytics-*.jar`, then rename into place — so the directory never holds
two versions of this plugin at once (RuneLite loads every jar under that
directory, and two would mean a duplicate plugin class and a broken load).
`catherby plugin status` compares the built jar's checksum against the
deployed one.

Then launch RuneLite via the Desktop shortcut **`RuneLite Dev (Catherby).bat`**.
It runs a PowerShell script that starts `net.runelite.client.RuneLite`
**directly** — not through RuneLite's production launcher — with
`--developer-mode`. This is required, not optional: RuneLite hard-disables
developer mode whenever `-Drunelite.launcher.version` is set, and the
production launcher always sets it, so side-loading (gated on
`--developer-mode` — `PluginManager.loadSideLoadPlugins`) only works via a
direct launch like this one. Jagex account sign-in still works because
`--insecure-write-credentials`, set in the production launcher's own client
arguments, makes a normal Jagex-launcher session persist `JX_*` credentials to
`credentials.properties`, which the direct launch then reads — so you still
need to have opened RuneLite through the normal Jagex launcher at least once
to seed that file.

**Manual side-load (no `catherby` CLI):** build with `./gradlew clean build`
(produces `build/libs/osrs-analytics-<version>.jar`), copy that jar into the
side-load folder above yourself, then launch with `--developer-mode` any way
you like (client arguments, or a direct launch as above). The `catherby`
CLI path is preferred because it's atomic and keeps built vs. deployed jars in
sync automatically.

### WSL2 → Windows networking

If the plugin runs in **Windows RuneLite** while the backend runs in **WSL2**,
point the plugin's API base URL at `http://localhost:8000` — Windows forwards
`localhost` into the WSL2 VM, so the Windows client reaches the WSL backend with no
extra config. (If you ever need the explicit VM IP, `wsl hostname -I` prints it.)

---

## 5. Verify data is flowing

**a. Watch the panel.** Open the **Catherby Analytics** side panel (bar-chart icon on
the RuneLite toolbar). Log in. Within a flush interval (default 15s) the status
dot should turn green — **Connected** — and **Accepted (session)** plus the
per-category counters start climbing (Sessions first, then XP, etc.). Click
**Flush now** to force a send.

**b. Confirm the sync log.** Each accepted batch writes a row:

```bash
psql "$DATABASE_URL" -c \
  "SELECT category, outcome, event_count, summary_text, created_at
   FROM catherby.plugin_sync_log ORDER BY created_at DESC LIMIT 10;"
```

Expect `outcome = accepted`, `category = batch`, and `summary_text` listing the
categories present in that batch (e.g. `sessions,xp`).

**c. Confirm per-category rows.** The batch fans out into the ten telemetry
tables. Count them (all in the `catherby` schema):

```bash
psql "$DATABASE_URL" -c "
  SELECT 'sessions'            AS tbl, count(*) FROM catherby.plugin_sessions
  UNION ALL SELECT 'xp',              count(*) FROM catherby.plugin_xp_snapshots
  UNION ALL SELECT 'quests',          count(*) FROM catherby.plugin_quests
  UNION ALL SELECT 'diaries',         count(*) FROM catherby.plugin_diaries
  UNION ALL SELECT 'combat_achv',     count(*) FROM catherby.plugin_combat_achievements
  UNION ALL SELECT 'equipment',       count(*) FROM catherby.plugin_equipment
  UNION ALL SELECT 'loot',            count(*) FROM catherby.plugin_loot
  UNION ALL SELECT 'activity',        count(*) FROM catherby.plugin_activity
  UNION ALL SELECT 'bank',            count(*) FROM catherby.plugin_bank
  UNION ALL SELECT 'collection_log',  count(*) FROM catherby.plugin_collection_log;"
```

Diaries and combat achievements populate on the collectors' 60s scan once you are
logged in (or immediately on any completion change). XP snapshots follow the XP
interval (default 5 min); a session/login row lands almost immediately.

**d. See it in the dashboard.** The Catherby web dashboard reads these tables
through the analytics endpoints — the account you registered in step 3 now has
live plugin telemetry attached to it.

---

## 6. Verify the parity features (lookup, collection log, name change)

**Player lookup.** Right-click a player (in the world, or in the friends/clan/chat
lists) and choose **Analytics lookup**, or open the panel's **Lookup** tab and
type a registered RSN. The panel shows the player's overall level, skills, and
activities. Under the hood this calls the WOM-compat root endpoint — confirm it
directly:

```bash
curl -s http://localhost:8000/players/YOUR_RSN | python3 -m json.tool | head
# 404 for an unregistered name (register via step 3 first).
```

**Collection log full-state walk.** Open the in-game Collection Log and click
through a few categories. Each category page you view syncs its obtained items
(real item ids + names). Confirm rows land and re-syncs are idempotent (the
backend upserts by `(account_id, item_id)`):

```bash
psql "$DATABASE_URL" -c \
  "SELECT item_name, source, quantity FROM catherby.plugin_collection_log
   ORDER BY updated_at DESC LIMIT 15;"
```

**Name change.** If you change your RSN, on your next login the plugin submits the
old/new pair once (authenticated) to `POST /api/v1/plugin/name-changes` — the
plugin base URL plus `/name-changes`, using your existing API key. It keys the
last-seen name by account hash, so switching between your own accounts is not
mistaken for a rename. The intake is being built server-side (BP-C20); until it
lands the plugin gets a 404 and quietly retries on a later login (it does not
persist the new name, so nothing is lost). Once live, confirm acceptance in the
plugin sync log:

```bash
psql "$DATABASE_URL" -c \
  "SELECT category, outcome, summary_text, created_at
   FROM catherby.plugin_sync_log WHERE category = 'name_change'
   ORDER BY created_at DESC LIMIT 5;"
```

**Logout flush.** Logging out emits the session-end event and flushes immediately;
you should see a fresh `plugin_sync_log` row within a second or two of logging out,
not one flush-interval later.

---

## Quick reference

| Thing | Value |
|---|---|
| Backend base URL | `http://localhost:8000/api/v1/plugin` |
| Batch endpoint | `POST /api/v1/plugin/batch` (X-API-Key header) |
| Batch rate limit | 10/min (plugin spaces sends ≥ 6s apart) |
| Token mint | `python scripts/mint_plugin_token.py mint --label "RuneLite" --scopes plugin:ingest,plugin:read` |
| Register RSN | `POST /snapshots/run {"player":"RSN"}` (API root, no /api/v1) |
| Dev launch | `./gradlew run` (adds `-ea --developer-mode`) |
| Deploy + launch | `catherby plugin deploy` (from the catherby repo), then the Desktop `RuneLite Dev (Catherby).bat` shortcut |
| Side-load dir | `~/.runelite/sideloaded-plugins/` (requires `--developer-mode`) |
| Built jar | `build/libs/osrs-analytics-1.1.0.jar` |
| Tests | `./gradlew test` — 72 tests |
