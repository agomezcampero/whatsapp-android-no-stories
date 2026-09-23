# No Stories

Hides the row of status/story avatars at the top of WhatsApp's Chats screen on
Android. The Updates tab still works; it just isn't in your face every time you
open the app, and you can't open a story by accident from the chat list.

WhatsApp itself is never modified. A small accessibility service watches for
that row and parks an opaque rectangle over it.

## How it works

`StoryHiderService` listens to WhatsApp's window and content events. When it
finds the avatar row it reads `getBoundsInScreen()` and places a
`TYPE_ACCESSIBILITY_OVERLAY` window over those bounds, painted in the toolbar
colour and clickable so it swallows taps. The overlay moves when the row moves
and is removed when the row is gone or WhatsApp leaves the foreground.

| File | Responsibility |
| --- | --- |
| `StatusRowLocator.kt` | **All** matching logic: shape, labels, id lookup |
| `RowIdMemory.kt` | Remembers the row's view id once something has found it |
| `OverlayController.kt` | The overlay window: add, move, repaint, remove |
| `StoryHiderService.kt` | Event filtering, node caching, when to show and hide |
| `MainActivity.kt` | A button that opens Accessibility settings |

## Finding the row

**1. A confirmed id.** `com.whatsapp:id/status_list` - a `RecyclerView` of
avatar tiles on the Chats tab, confirmed from a detection report. Not
`updates_list`, which is the Updates tab and stays visible. WhatsApp
obfuscates and reshuffles ids, so expect this to lapse on an update; the
passes below carry on until a new id is added to `ROW_VIEW_IDS`.

**The row is only ever resolved by id.** Nothing is covered on the strength of
a guess. Shape rules could not tell the row from the toolbar that appears when
you select a message, and the cost of being wrong is covering a button you
need, so they no longer decide anything.

**What gets covered is the tiles, not the row.** When the header collapses, the
row keeps its full-width layout while its contents shrink to a cluster of
circles - covering the view itself takes the search bar and the overflow menu
with it. The cover is the union of the visible tiles, kept clear of the search
bar as a backstop.

**When no known id resolves**, and only on the Chats screen, the shape and
label passes still run, but purely to write a report naming the likeliest
candidate. That name goes into `ROW_VIEW_IDS` and detection works again.

To see what it picked:

```sh
adb logcat -s NoStories
```

A dump is still the fastest way to understand a layout that defeats all three:

```sh
adb shell uiautomator dump /sdcard/wa.xml && adb pull /sdcard/wa.xml
```

Put the element above the "Ask Meta AI or Search" bar into `docs/wa-dump.xml`
and adjust the constants at the top of `StatusRowLocator` against it.

## Building and installing

Android Studio: open the project and Run. From the command line:

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then on the phone:

1. Settings → Apps → No Stories → ⋮ → **Allow restricted settings** (Android 13+;
   without this the service can't be switched on).
2. Settings → Accessibility → **No Stories** → enable.
3. Exempt it from battery optimisation. This matters on Xiaomi and Samsung,
   which otherwise kill the service after a while.

Sideload only. This is not for the Play Store.

## Performance

* Events are coalesced with `notificationTimeout="100"` and limited to
  `typeWindowStateChanged|typeWindowContentChanged|typeViewScrolled`.
* Foreign events are dropped after one string comparison.
* The found row is cached as an `AccessibilityNodeInfo` and re-read with
  `refresh()`, so the common case never walks the tree.
* After the first sighting the row resolves by its learned id: one call, no
  walk.
* The scans are breadth-first, capped at 800 nodes, throttled to one run per
  350 ms, and never descend below the top band of the screen.
* The overlay window is only moved when its bounds actually change.
* No wake locks, no dependencies beyond the platform and Kotlin.
* The row's position is re-checked every 500ms while WhatsApp is in front and
  the screen is on, and not otherwise. The plan said no polling and events
  ought to be enough, but WhatsApp rebuilds its list on its own, and when
  nothing moves on screen afterwards no event arrives and the stories come
  back for good. A tick can only put the cover up or move it; taking it down
  stays with events.

## Deliberate deviation: `packageNames`

The plan called for `packageNames="com.whatsapp"` in the service config. That
filter is not set, on purpose: it would also drop the `typeWindowStateChanged`
event that fires when WhatsApp goes to the background, and the cover would stay
on screen on top of whatever app came next. The filter lives in
`StoryHiderService.onAccessibilityEvent` instead — one `contentEquals` before
any work, which costs about as much as the framework's own check. The service
also re-checks `rootInActiveWindow.packageName`, so a WhatsApp event arriving
while another app is on top doesn't bring the overlay back.

## Known rough edges

* WhatsApp updates may change the row's attributes. Matching lives in one file.
* The overlay can lag a frame on fast scrolls.
* Some banking apps warn, or refuse to run, while any accessibility service is
  enabled.
* The cover colour is two constants (`story_row_cover` in `values/colors.xml`
  and `values-night/colors.xml`). If WhatsApp restyles its top bar, change them
  there.
* The shape constants at the top of `StatusRowLocator` were tuned by
  reasoning, not against a measured layout. They only matter once the ids
  stop resolving; if the cover then comes out the wrong size, they are what
  to adjust.
* The cover tracks the row through accessibility events, so it cannot be
  perfectly in step with WhatsApp's collapsing header. `notificationTimeout`
  is 0 to keep the gap small.
* The cover colours are a guess at WhatsApp's top bar and may want a nudge.
