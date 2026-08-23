# Board Game Deal Checker (Android)

A floating bubble that sits over whatever you're browsing. Tap it and it reads the screen, finds
board game titles, and tells you whether each one is a good buy — using the same bars as the
`board-game-deal-tracker` dashboard.

**Good buy** = BGG rating ≥ 7.0 · BGG rank under 2,500 · at least 50% off the cross-store median ·
not already in your collection.

Anything 35–49% off is shown as a **near miss** rather than dropped, so you can see what nearly made it.

## Getting the APK

1. Create a new GitHub repo (private is fine) and push this folder to `main`.
2. The **Build APK** workflow runs on every push. When it's green, grab `deal-checker.apk` from
   the run's artifacts, or from the auto-updated **latest** release — the release link is the easy
   one to open on your phone.
3. On the phone: open the APK, allow installs from that source, install.

No signing key is set up — this is a debug build, which is what you want for sideloading. It will
not update over a Play Store copy, and Play Protect will ask you to confirm once.

## First run

1. Open **Deal Checker**.
2. Tap **1. Allow drawing over other apps** and flip the switch.
3. Tap **2. Start the bubble**. Android asks to let the app record the screen — that's the capture
   permission, granted once for as long as the bubble is running. The app minimises itself.
4. Browse. Tap the **BG** bubble to scan. Drag it to move it; it snaps to the nearest edge.
5. Stop it from the **Deal Checker is watching** notification, or from the app.

The screenshot never leaves the phone. Text recognition is ML Kit's on-device model, and the game
data is baked into the APK — the app only touches the network if you tap *Refresh price medians*.

## What's bundled

| File | What it is |
|---|---|
| `assets/games.tsv` | 31,131 ranked BGG titles (name, year, rank, rating, ratings count) — the 2026‑08‑21 ranking snapshot |
| `assets/owned.json` | Your 132 owned titles, from the deal tracker's `OWNED` array |
| `assets/medians.json` | 82 cross-store median prices, from the tracker's `ALL_DEALS` rows |

## How a verdict is reached

1. **OCR** — one frame of the screen goes through ML Kit; every text line comes back with a box.
2. **Title matching** — for each line, the longest run of words that resolves to a ranked BGG title
   wins. A line that is *nothing but* the title is trusted readily, since that's how store pages
   print them; a phrase buried inside a longer line has to be long or the game well known before it
   counts. A fuzzy pass catches OCR damage (`Wlngspan` → Wingspan) but only lands on games with a
   real audience behind them.
3. **Price** — dollar amounts near the title are collected. The lowest is taken as what you'd pay;
   a noticeably higher one alongside it is taken as the list price the store is comparing against.
4. **Baseline** — ranked by how much it deserves to be trusted:
   1. the bundled cross-store median — a real market price
   2. a labelled list price on the page (`MSRP`, `Was`, `Compare at`) — usually inflated
   3. an unlabelled higher price beside the sale price — probably the strike-through
   4. the store's own `-62%` badge — their arithmetic, against their own list price

   The card always names which one was used, because a 60% discount off invented MSRP is no deal.
5. **Verdict** — rating and rank first, then the discount.

`NO PRICE` means the game clears on pedigree but no price was found beside it. `NO BASELINE` means a
price was found but there was nothing to measure it against — no bundled median, no list price, no
discount badge. The two are separated deliberately: the first is a layout problem, the second is a
data problem, and they want different fixes.

`tools/test-matcher.js` and `tools/test-prices.js` exercise the matching and pricing logic against
the tracker's own deal rows and against synthetic store layouts. Run them with `node` from `tools/`
after changing either — they catch far more than a rebuild does.

## Keeping it current

- **Owned list**: re-run the `refresh-bgg-collection` job against the tracker, then copy the new
  `OWNED` array into `assets/owned.json` and push.
- **Medians**: mostly self-maintaining now — see *Live medians* below. To reseed the bundled set,
  take them from `ALL_DEALS` again. Or host a `medians.json` (`[["Game name", 53.99], …]`) anywhere
  reachable, paste the URL into the app, and hit **Refresh price medians**.

## Live medians (Board Game Oracle)

Board Game Oracle is a tRPC app, so there is a plain JSON API behind the site:

```
GET /api/trpc/boardgame.list?input={"region":"us","q":"<name>"}   → candidates: title, year, key
GET /api/trpc/boardgame.get?input={"region":"us","key":"<key>"}   → bgg_id + price_stats
```

`price_stats.discount_median_compare_price` is the cross-store median — the same number the tracker
computes by hand. When a scan turns up a game with no median, the app asks Oracle in the background
and caches the answer for a week (misses for three days, so untracked games are not re-queried on
every scan). Verdicts firm up in place a second or two after the panel appears.

The candidate is only accepted when **its `bgg_id` equals the one in our index**. Name matching
alone is not safe: an Oracle search for "Terra Nova" returns both the 2006 and 2022 games, and the
tracker has picked the wrong one before. Verified against 11 games — all resolved on the first
candidate.

A median built on fewer than three offers is discarded rather than trusted.

Turn the whole thing off with the checkbox on the main screen; the app then stays fully offline.
- **BGG index**: swap in a newer dump from
  `raw.githubusercontent.com/beefsack/bgg-ranking-historicals/master/YYYY-MM-DD.csv` and rebuild
   `games.tsv` with `tools/build-index.js`.

## Scrolling with the panel open

The overlay is two windows, not one. The highlight layer is `FLAG_NOT_TOUCHABLE`, so touches fall
straight through and the page scrolls as usual; only the bottom panel takes input.

Boxes are drawn at pixel positions from one frozen frame, so they are wrong the instant the page
moves. A mirrored virtual display only produces a frame when the screen actually changes, which
makes `acquireLatestImage() != null` a free scroll detector — no pixel diffing needed. While the
panel is open the service polls it every 220 ms: any movement clears the boxes immediately, and
550 ms after the screen settles it re-reads and redraws them against the new content. Frames are
ignored for 400 ms after the app changes its own overlay, so it does not chase its own redraws.

Rescans skip any OCR line intersecting the panel — the panel is full of game names and dollar
amounts, and reading its own output back in would let the app score itself.

## The MediaProjection start-up order

These two calls each appear to require the other to have happened first:

- `startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` needs the `android:project_media`
  app op, or it throws `SecurityException`.
- `getMediaProjection(resultCode, data)` can refuse when no mediaProjection foreground service is
  running yet.

Both orderings have been observed failing on real hardware (Android 15, moto g stylus 5G), so the
service no longer assumes one. It tries the typed foreground service, falls back to an untyped one
if that is refused, gets the projection, then upgrades the type — and whichever leg failed is
written to `last-crash.txt` and shown on the main screen. `createVirtualDisplay` comes last either
way, since it definitely requires a running foreground service.

The `specialUse` type exists only for those fallback and give-up paths: a service started with
`startForegroundService` must reach `startForeground` within a few seconds regardless.

The service returns `START_NOT_STICKY`. A consent token is single-use, so a system-initiated restart
would come back holding a spent one; better to be plainly gone. Starting the bubble also stops any
previous instance first, for the same reason.

## Known limits

- Ranks and ratings are a snapshot, so a game sitting right on the 2,500 line may read differently
  from the dashboard, which was hand-verified on various dates.
- Games BGG hasn't ranked don't exist in the index. They'd fail the rank bar anyway.
- Titles with fewer than 150 ratings are ignored to keep site furniture from matching obscure
  namesakes. Those also can't clear the rank bar.
- Expansions are filtered by keyword (`Expansion`, `Promo`, `Playmat`, `Sleeves`, …), and an add-on
  row also blocks its price from drifting up to the base game above it. Expansions whose names carry
  no such word — *Ark Nova: Marine Worlds*, *Terraforming Mars Prelude* — still match the base game,
  because the index holds ranked base games only and has nothing to recognise them by.
- The panel blocks touches only over itself. Everything above it scrolls normally.
