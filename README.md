# Catherby Analytics RuneLite Plugin

RuneLite plugin that captures gameplay telemetry and streams it to a **local
Catherby analytics backend** over HTTP. Everything is config-gated: disabling the
plugin (or the master toggle) produces zero network traffic.

The plugin is a clean-room implementation written against the Catherby plugin API
contract (`/api/v1/plugin`). It does not automate gameplay — it only reports
telemetry, in line with Jagex's third-party client guidelines.

For a complete, concrete run/verify walkthrough see [`TESTING.md`](TESTING.md).

## What it collects

| Category | Status | Notes |
|---|---|---|
| Sessions | Full | login / logout (with duration) / world-hop |
| XP | Full | periodic snapshot of all 24 skills (incl. `sailing`), interval configurable |
| Quests | Full | per-quest state for the full Quest enum (incl. `not_started`), plus quest points (varp 101) and a quest/miniquest/subquest type derived from the OSRS Wiki miniquest list; diff state resets per RSN |
| Achievement diaries | Full | per-tier completion for all 12 regions, source-verified varbits; Karamja's 3-state easy/medium/hard handled correctly; live-validated 12-for-12 against a real account (2026-07-17) |
| Combat achievements | Full | completed-task count per tier (easy → grandmaster), source-verified varbits |
| Equipment & inventory | Full | worn items (slot → id) + inventory (id, qty), debounced |
| Loot | Full | NPC / boss / chest / clue / minigame drops with GE values |
| Activity | Heuristic | coarse region-change signal, clearly labelled as heuristic |
| Collection log | Full | full-state walk of the log interface (real item ids/names/obtained) + a per-page completion summary (obtained/total slots + kill counts); incremental chat drops are recorded as append-only activity (the chat line has no item id) so they never collide in the keyed stream |
| Bank | Full | full bank snapshot (ids, quantities, values, total); `ge_then_ha_v1` valuation: GE price, high-alch fallback for untradeables, placeholders excluded |

Diary and combat-achievement varbit ids are **source-verified** against the
RuneLite client API (`net.runelite.api.Varbits`): diary completion flags at
L212-270, combat-task counts (`CA_TOTAL_TASKS_COMPLETED_*`, `COMBAT_TASK_*`) at
L970-975 / gameval VarbitID L8058-8063. Every id is cited in the collector
source, and `CollectorVarbitMapTest` guards the maps for completeness. See
`DiaryCollector.REGION_TIER_VARBITS` and
`CombatAchievementCollector.TIER_COUNT_VARBITS`.

Collection log `obtained_at` (and the page-summary timestamp) is the time the
plugin **first observed** the item/page, **not** the historical date it was
originally acquired — the collection log exposes no per-item acquisition dates,
so anything obtained before the plugin ran is stamped with its first-observed
time.

## Privacy

- Every category — including **bank** and **collection log** — is ON by default
  for this internal deployment. Both private categories keep clear labels in the
  config so opting out is always **one click**.
- The API key is sent only as the `X-API-Key` header and is **never logged**.
- With the master toggle off, no events are queued and nothing is sent.

## Side panel

The plugin adds a RuneLite side panel (toolbar bar-chart icon) with two tabs:

**Status tab** — live transport health, at a glance:

- **Connection state** — Connected / Idle / Rate limited / API key rejected /
  RSN not registered / Retrying, with a colored status dot.
- **Queue depth**, **last accepted** time, and **accepted (session)** total.
- **Accepted by category** — a per-category counter for the current session.
- **Backend URL** (never the key).
- **Flush now** — forces a flush on the transport executor (still rate-limited, so
  it can never breach the batch limit).

**Lookup tab** — a search box that queries the backend's WOM-style
`GET /players/{name}` envelope and renders the player's skills and activities.

The Status tab refreshes once a second on the Swing EDT from an atomic,
lock-guarded snapshot; lookups run on the transport executor and marshal their
result back onto the EDT. Nothing touches the client thread.

## Player lookup & name changes

- **Right-click lookup** — right-clicking a player (in the world, or in the
  friends/clan/chat lists) offers an **Analytics lookup** option that opens the
  panel's Lookup tab for that name. Config-gated (**Lookup & sync → Right-click
  player lookup**). The lookup hits the WOM-compat root API
  (`/players/{name}`), not the plugin ingest path.
- **Name-change submission** — when your display name changes (same account, new
  RSN — detected via `getAccountHash()` and a persisted last-seen name), the
  plugin submits the old/new pair once (authenticated) to
  `POST {base}/name-changes` — your configured plugin base URL plus
  `/name-changes`, with the same `X-API-Key`. The new name is persisted only on a
  final server answer, so a transient failure (e.g. the intake not yet deployed)
  retries on a later login. Config-gated (**Lookup & sync → Submit RSN name
  changes**).
- **Update on logout** — logging out emits the session-end event and immediately
  flushes the queue (WOM's "update on logout"), rather than waiting for the next
  periodic flush. The batch itself is the update — the plugin never posts a
  redundant player-refresh call.

## Local backend setup

1. Run the Catherby API locally (default `http://localhost:8000`). The plugin
   routes are mounted at `/api/v1/plugin`.
2. Mint a plugin API **token** (in the Catherby repo):
   ```bash
   python scripts/mint_plugin_token.py mint --label "RuneLite" \
       --scopes plugin:ingest,plugin:read
   ```
   Paste the printed token into the plugin's **API key** config field.
3. The account's RSN must be **registered** with the backend first — the plugin
   API returns `404` for an unknown RSN. Registering is as simple as taking one
   snapshot for the account: `POST /snapshots/run {"player": "<RSN>"}` auto-creates
   the account row. If the plugin starts before the RSN is registered, it keeps the
   batch queued behind a long backoff and resumes automatically once the account
   exists (and posts a one-time "connected — telemetry flowing" chat notice).

See [`TESTING.md`](TESTING.md) for the exact end-to-end path.

## Build & test

Requires JDK 17 (compiles to release 11). The RuneLite client resolves from
`https://repo.runelite.net`.

```bash
./gradlew clean build   # compile + package
./gradlew test          # run the JUnit 4 test suite (72 tests)
./gradlew run           # launch RuneLite with the plugin loaded (dev harness)
```

Tests cover:
- **DTO serialization** — golden assertions that every payload matches the backend
  pydantic contract (field names, enum values, nesting, all 24 skills).
- **Transport** (`AnalyticsClient` with OkHttp MockWebServer) — batch shape and
  `X-API-Key` header, category grouping, retry with backoff and stable event ids,
  429 handling (including HTTP-date `Retry-After`), 401 auth pause, 404
  requeue-behind-long-backoff, the recovery notice, the bounded queue, the
  status-panel snapshot with per-category counts, a full-collection-log sync that
  spreads across bounded batches, and the "disabled = no traffic" guarantee.
- **Lookup** (`LookupClient` with MockWebServer) — parsing the WOM `/players`
  envelope, 404 → not-found, 5xx → error, the `/names/bulk` POST body, and API-root
  derivation from the plugin base URL.
- **Varbit maps** (`CollectorVarbitMapTest`) — the diary map covers all 12 regions
  × 4 tiers (48 distinct ids) and the combat-achievement map covers all 6 tiers,
  each matching the source-verified RuneLite ids.
- **Quest classification** (`QuestClassificationTest`) — the 19 wiki-listed
  miniquests, the ten RFD subquests, and Tutorial Island classify correctly
  against the live Quest enum; unknown names default to `quest`.
- **Name-change detection** (`NameChangeCollectorTest`) — the rename predicate
  never fires on first login, same name, case-only changes, or blanks.

## Dev launch

`./gradlew run` launches a RuneLite client with the plugin loaded as a built-in
(via the `AnalyticsPluginTest` harness on the test classpath). It runs with
`-ea` and `--developer-mode`, both of which RuneLite requires to load a built-in
plugin. You can also run `com.cortalabs.osrs.analytics.AnalyticsPluginTest#main`
directly from your IDE (add `-ea` to the VM options). A built jar can instead be
side-loaded — see [`TESTING.md`](TESTING.md).

## Configuration

Config group `osrsanalytics`:

- **Connection** — enable toggle, API base URL
  (`http://localhost:8000/api/v1/plugin`), API key (secret), flush interval
  (default 15s), XP snapshot interval (default 5 min).
- **Categories** — sessions, xp, quests, diaries, combat achievements, equipment,
  loot, activity (all on by default).
- **Private data** — collection log, bank (on by default for this deployment;
  turn off any time).
- **Lookup & sync** — right-click player lookup, submit RSN name changes (both on
  by default).

## How it sends

Events are queued on the client thread and flushed off-thread as a single
`POST /api/v1/plugin/batch` request. Sends are spaced to stay under the backend's
batch rate limit (10/min). Failure handling:

- **429 / 5xx / network** — requeue with exponential backoff (429 honors
  `Retry-After`, both delta-seconds and HTTP-date forms).
- **401 / 403** — pause sending until the key is fixed (one-time chat notice).
- **404 (unknown RSN)** — keep the batch and retry behind a long backoff, so a
  session start is not lost when the RSN is registered a few minutes late (one-time
  chat notice explaining how to fix it).
- **422 (invalid payload)** — drop the poison batch rather than hammer the server.

On the first success after any interruption, the plugin posts a single
"connected — telemetry flowing" chat notice. The queue is bounded so a long
outage cannot grow memory without bound (normal fill drops the oldest; a 404
requeue keeps the just-requeued events and drops the newest excess).

## Design notes

- Java DTOs mirror the backend pydantic models 1:1 via explicit `@SerializedName`,
  so the wire shape is the contract.
- Change-detecting collectors (quests, diaries, combat achievements) reset their
  diff state when the logged-in RSN changes, so account-hopping never
  cross-contaminates one player's progress onto another.
- `ItemManager` lookups (item names / GE prices) run only inside client-thread
  event handlers (loot events, `ItemContainerChanged`), never off-thread.
- Each queued event carries a client-side `event_id` (UUID) that is stable across
  retries. It is **not** serialized today (the live contract has no `event_id`
  field); it exists for client de-duplication and forward compatibility if the
  backend adopts event-id idempotency.

## License & acknowledgements

- License: BSD 2-Clause (see `LICENSE`).
- This repository was forked from the Wise Old Man RuneLite plugin
  (BSD 2-Clause): <https://github.com/wise-old-man/wiseoldman-runelite-plugin>.
  The telemetry implementation here is a clean-room rewrite against a different
  backend contract; attribution to the Wise Old Man project and community is
  retained with thanks: <https://wiseoldman.net>.
