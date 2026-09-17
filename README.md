# Playtime Logger

A [RuneLite](https://runelite.net/) plugin that logs your actual logged-in play
sessions to a local file — not client-open time, and not the game's own
played-minutes counter, but real login-to-logout wall-clock duration.

## What it does

- Starts a session on a genuine login (transition to `LOGGED_IN` from the
  login screen).
- Ends a session on a genuine logout (transition back to the login screen).
- A world hop does **not** split a session — hopping passes through
  `LOGGED_IN` again, but the plugin only starts a new session if one isn't
  already open, so a hop is treated as a continuation of the same session.
  The number of hops during the session, and which worlds you were on, are
  recorded instead.
- If the client is closed while still logged in, the open session is closed
  out at that moment instead of being lost (via RuneLite's `ClientShutdown`
  event — `shutDown()` alone is not enough, since it isn't called when the
  whole client exits, only when this plugin is individually disabled).
- If the plugin is disabled and re-enabled while already logged in, a new
  session starts from the moment it's re-enabled rather than silently
  missing everything until the next real login.

Each completed session appends one line to:

```
~/.runelite/plugin-data/playtime-logger/sessions.csv
```

(on Windows, `%USERPROFILE%\.runelite\plugin-data\playtime-logger\sessions.csv`)

with columns `login,logout,duration_seconds,hops,worlds,player` (timestamps
in UTC, truncated to the second, and `duration_seconds` is exactly `logout`
minus `login` as printed; `hops` is how many world hops happened during that
session; `worlds` is the semicolon-separated list of world numbers visited, in
order, e.g. `450;451;301`, with a reconnect to the same world adding nothing;
`player` is the display name
of the account that session belongs to, so sessions from different accounts
on the same PC don't get mixed together). The file is append-only — no
rotation, since a session-per-line log grows by well under 1MB/year even
with daily play.

Nothing is sent anywhere; the log never leaves your machine.

## Updating from an older version: the file moved

Versions before September 2026 wrote to
`~/.runelite/playtime-logger/sessions.csv`. The plugin now uses RuneLite's
`Filepath` API, which keeps every plugin's files under
`~/.runelite/plugin-data/`. You don't need to do anything: the first time the
updated plugin starts, it moves the old `playtime-logger` folder, history
included, to the new location and keeps appending to the same file. If you
have a script or spreadsheet pointed at the old path, point it at the new one.

If an old `~/.runelite/playtime-logger` folder shows up again after the
update, a second client was still running the old version and logged a
session there. Restart that client, paste those rows (not the header line)
into the new `sessions.csv`, and delete the old folder. The plugin only moves
the folder once, so it won't pick those rows up on its own.

If the old folder is still there and no new sessions are being logged, the
move is being blocked, usually because the old `sessions.csv` is open in
another program (Excel locks it on Windows). Close it and restart the client.
Your history is safe in the old folder in the meantime.

## Why not the built-in played-time counter?

The game already exposes a played-minutes-this-session value (used by plugins
like [Playtime](https://github.com/Adam-/runelite-plugins/tree/playtime) to
show an on-screen overlay), but it's minute-granularity and its behavior
across world hops isn't something this plugin wants to depend on. Instead,
this plugin tracks the client's own `GameState` transitions directly and
measures wall-clock time between a real login and a real logout, which is
simpler and matches what "how long did I play" actually means.
