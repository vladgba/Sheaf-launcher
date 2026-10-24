# Sheaf Launcher

A minimal Android home-screen launcher with free-form widgets, a 2D page matrix and per-item z-order.
Kotlin, no external libraries, minSdk 26 / targetSdk 35.

## Features

| Requirement | How it works |
|---|---|
| Widgets | Real `AppWidgetHost`. Long-press empty space → **Add widget** opens a picker with live previews (the widget's preview layout on Android 12+, otherwise its preview image). Long-press a preview and drag it onto any page; it's placed where you drop it, at its default size. Bind permission and the widget's setup screen are handled after the drop. |
| Widget padding | Widget menu → **Remove padding** (on/off per widget, available while the layout is unlocked). Reports the full widget size to its app without Android's default padding deduction, so providers can use the full area. Position and size stay the same. The setting survives restart and backup/restore; it starts off for existing and new widgets. Padding, margins, or transparent borders built into a provider's own layout may remain. |
| All apps on the home screen | There is no app drawer. Every launchable app is placed on a page or inside a folder. New installs drop into the first free cell; uninstalls are removed. If pages run out of room, a column of pages is added automatically. Apps can't be "removed", only moved, foldered or uninstalled. |
| Folders on the home screen | App menu → **Move to folder…** (new or existing), or drag an app onto a folder. Folder menu: Open / Rename / Ungroup. Inside an open folder, long-press an app and drag it out onto the home screen (or onto another folder); long-press and release shows its shortcuts, **App info** and **Uninstall**. A folder disappears when its last app is dragged out. Folder grid (columns, and rows shown before it scrolls; 1–8 each) is set in **Home screen settings**. |
| Widgets resizable by manual size, ignoring the grid | Widget menu → **Resize / move**: drag any edge or corner to any size (live dp readout), drag inside to move, tap outside to finish. Or **Set exact size…** to type width/height in dp. Widgets also move freely (no snapping). |
| Grid changeable vertically/horizontally | Long-press empty space → **Home screen settings** (horizontal sliders): grid columns and rows (2–14 each), page counts, icon size, folder grid, labels. |
| Left/right *and* up/down page swipes | Pages form a matrix (horizontal × vertical count set in settings, up to 12×12). Swipe left/right for neighbours in a row, up/down for the row above/below. A dot-matrix indicator floats over the bottom of the page (no reserved space) and shows where you are. |
| Default page | Long-press empty space → **Set as default page**. Pressing Home (while already home) returns there, and Sheaf opens on it. Its dot has a ring in the indicator. |
| Home button actions | Home screen settings → **Home button on default page**: Nothing / Lock screen / Expand notifications / Expand quick settings. Runs only when you're already resting on the default page (otherwise Home just returns there). Lock uses the Sheaf accessibility service (Android 9+, keeps fingerprint/face unlock) or device admin as fallback (`lockNow`, next unlock needs PIN/pattern). Notifications/quick settings use the accessibility service, or fall back to the hidden `StatusBarManager` API (`EXPAND_STATUS_BAR`), which works on most but not all devices. The settings show which is enabled, with buttons to enable them or remove device admin. |
| App icon gestures | App menu → **Swipe gestures…**: set an action for swipe up / right / down / left on that icon: one of the app's shortcuts, another app, another app's shortcut, or a system action (lock screen, notifications, quick settings). Directions without an action still change page. Settings follow the app into and out of folders. |
| App shortcuts | Long-press an app (home screen or folder) and release: the app's shortcuts are listed at the top of the menu (needs Sheaf to be the default home app). |
| Hide apps | App menu (home screen or folder) → **Hide app**. Home screen settings → **Hidden apps…** lists every app with a checkbox to hide or unhide. Hidden apps disappear from pages and folders (an emptied folder is removed); unhidden apps return to free cells starting at the current page. Hidden apps can still be opened from swipe gestures. |
| Lock layout | Long-press empty space → **Lock layout** / **Unlock layout**. While locked, nothing can be moved, resized, added, removed, hidden, re-stacked or dragged out of folders; long-press opens the menu immediately with only the non-layout options (shortcuts, swipe gestures, app info, uninstall, widget settings, folder open/rename). |
| Day / night colors | Home screen settings → **Color mode**: Follow system / Day / Night. Day: dark labels with a light glow, dark page dots, light folder and widget panels, light dialogs and menus, dark status/navigation bar icons. Night: the reverse. *Follow system* switches live when Android's dark theme changes. |
| Custom icons & names | App or folder menu → **Edit icon & name…**: type a new name (apps: empty = the app's own name), pick a picture and crop it (square frame you move and resize by its corners, rotate button; result scaled to 256 px and stored in `files/custom_icons/`), use another app's icon, or reset. For folders, reset brings back the default folder-style icon (2×2 previews of its apps). Applies everywhere the app appears (pages, folders, folder previews). |
| Backup (export / import) | Home screen settings → **Export settings…** saves one JSON file with the layout (pages, cells, icon sizes, z-order), all settings, hidden apps, custom names and icons, swipe gestures and widgets (app, position, size, grid-fixed). **Import settings…** restores it, also after a reinstall or on another device. Widgets are re-created in their old places: Android asks to allow each one (tick *Always allow* to skip the rest), and widgets that need setup open their settings screen. A widget whose app isn't installed yet stays as a placeholder and comes back once the app is installed; cancelled ones stay as placeholders — tap to retry, long-press → Remove to drop. A widget's own content/settings (stored by its app) can't be carried over. |
| Large icons (Windows Phone style) | App or folder menu → **Icon size** → 1× / 1.5× / 2×. A large icon grows right and down from its cell (moved up/left if it would leave the page), keeps its size when dragged, and the cells it covers count as busy when new apps are placed. Dropping an app anywhere on a large folder puts it in that folder. |
| Widgets fixed to the grid | Widget menu → **Fix to grid** (on/off per widget). A grid-fixed widget's position and size are whole cells: drops snap to the nearest cell, **Resize / move** shows the grid and snaps while you drag (readout in cells), and **Set exact size…** rounds to cells. Changing grid columns/rows re-fits them. Home screen settings → **Fix new widgets to grid** makes it the default for newly added widgets. |
| Square icons | Home screen settings → **Icon shape**: Original / Square / Rounded. Adaptive icons are drawn full-bleed without the system mask; full-bleed bitmaps (e.g. cropped custom icons) fill the tile; other icons sit on a plate tinted from their own colour. Applies to pages, folders and folder previews. |
| Overlapping + z-order | Apps, folders and widgets may overlap freely. Every item menu has **Bring to front** / **Send to back**. The order controls both drawing and which item receives a touch. |

### Gestures
- **Tap** app/folder → open.
- **Long-press + drag** any item → move it. Hold it at a screen edge to flip to the neighbouring page.
- **Long-press + release** (no drag) → item menu (apps: shortcuts first).
- **Swipe on an app icon** → its swipe gesture for that direction, if set.
- **Long-press empty space** → add widget / wallpaper / settings / set as default page / lock layout.
- A swipe that starts on a widget that can still scroll in that direction (e.g. a list) is given to the widget; otherwise it changes page.

## Build

Open the folder in Android Studio, or:

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Then press Home and choose **Sheaf** (or Settings → Apps → Default apps → Home app).

## Known limits
- If device admin is enabled, Android won't uninstall Sheaf until it's removed (settings button, or Settings → Security → Device admin apps).
- Work-profile apps aren't listed (current user only).
- Widget positions are stored in dp; after a rotation, widgets near the far edge may sit partly off-screen in the other orientation.
