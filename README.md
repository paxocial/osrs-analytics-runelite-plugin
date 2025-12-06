# OSRS Analytics RuneLite Plugin

Opt-in plugin that streams snapshots to `osrs.cortalabs.com/api` so Corta Labs can power player and clan analytics (xp, KC, quests/diaries, name changes, collection log progress, contests).

## Features
- Snapshot + sync: send skill/boss/activities deltas, name changes, and session stats to Corta Labs; optionally pull clan membership for alignment.
- Competitions: view ongoing/upcoming contests, add them to the canvas, and get notifications before start/end.
- Clan tools: import clan roster, open the group page, and sync membership from the clan settings UI.
- Lookup: right-click lookup to fetch a player profile from the analytics backend.
- UI overlays: codeword overlay for events and infoboxes for competition timers.

## Build & Run
- `./gradlew clean build` to compile and package (outputs version.ini).
- `./gradlew test` to run the JUnit harness.
- Dev launch: run `AnalyticsPluginTest` (test harness) inside RuneLite with the plugin class `com.cortalabs.osrs.analytics.AnalyticsPlugin`.

## Configuration Highlights
- Group ID / verification: use your Corta Labs analytics group and verification code.
- Menu options: toggle clan tab Import/Browse, player lookup, and competitions notifications.
- Sync button: adds “Sync Analytics Group” to clan settings; only updates when group ID and verification are set.
- Opt-in data: keep sensitive metrics (bank/collection log) behind explicit toggles when added.

## Contribution Notes
- Package root: `com.cortalabs.osrs.analytics`.
- Plugin metadata lives in `runelite-plugin.properties`; resources in `src/main/resources/com/cortalabs/osrs/analytics/`.
- License: BSD 2-Clause (upstream MIT-compatible). Keep attribution when modifying inherited files.

## Support
Issues and questions: https://cortalabs.com (or your current repo/Discord once set).
