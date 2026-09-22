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
| `StatusRowLocator.kt` | **All** matching logic: view ids, labels, row shape |
| `OverlayController.kt` | The overlay window: add, move, repaint, remove |
| `StoryHiderService.kt` | Event filtering, node caching, when to show and hide |
| `MainActivity.kt` | A button that opens Accessibility settings |

## Finding the node

The row's attributes are not confirmed yet, so `StatusRowLocator` ships with
unverified id candidates plus a label-based fallback that does the real work.
To pin it down, with WhatsApp open on Chats:

```sh
adb shell uiautomator dump /sdcard/wa.xml && adb pull /sdcard/wa.xml
```

Find the element above the "Ask Meta AI or Search" bar that holds the avatar
circles, put it in `docs/wa-dump.xml`, and add its `resource-id` to the front of
`StatusRowLocator.ROW_VIEW_IDS`. That turns the lookup into a single call and
skips the tree scan entirely. If it has no `resource-id`, match on its
`content-desc` instead by extending `ROW_LABELS`.

While tuning, watch what the service sees:

```sh
adb shell uiautomator dump /sdcard/wa.xml   # after each WhatsApp update
```

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
* The fallback scan is breadth-first, capped at 800 nodes, and throttled to one
  run per 350 ms.
* The overlay window is only moved when its bounds actually change.
* No polling, no wake locks, no dependencies beyond the platform and Kotlin.

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
* Not verified on a device yet: it compiles and installs, but the id candidates
  and the exact cover colours need one pass with a real dump.
