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
  out at that moment instead of being lost.

Each completed session appends one line to:

```
~/.runelite/playtime-logger/sessions.csv
```

with columns `login,logout,duration_seconds,hops,worlds` (timestamps in UTC,
truncated to the second; `hops` is how many world hops happened during that
session; `worlds` is the semicolon-separated list of world numbers visited,
in order, e.g. `450;451;301`). The file is append-only — no rotation, since
a session-per-line log grows by well under 1MB/year even with daily play.

Nothing is sent anywhere; the log never leaves your machine.

## Why not the built-in played-time counter?

The game already exposes a played-minutes-this-session value (used by plugins
like [Playtime](https://github.com/Adam-/runelite-plugins/tree/playtime) to
show an on-screen overlay), but it's minute-granularity and its behavior
across world hops isn't something this plugin wants to depend on. Instead,
this plugin tracks the client's own `GameState` transitions directly and
measures wall-clock time between a real login and a real logout, which is
simpler and matches what "how long did I play" actually means.
