# aLauncher

A personal fork of [mLauncher](https://github.com/CodeWorksCreativeHub/mLauncher), the minimal text-based Android launcher. Same GPL-3.0 license, same package id (`app.mlauncher`), so it installs over mLauncher as an update when signed with the same key.

## What's different

- **Settings redesign.** Big title, search, large icon rows grouped in cards, roomy pickers. Material 3 Compose.
- **App drawer.** Follows your finger as you swipe up, blurred wallpaper backdrop, frosted search bar at the bottom, A–Z sidebar on the right with a fisheye wave, letter bubble and haptics, instant section jumps.
- **Home edit mode.** Long-press empty space: rows jiggle and can be dragged to reorder.
- **Clock sticker.** Any image stuck onto the clock with a die-cut outline and a gyroscope-driven holographic foil.
- **Icons for every app.** Icon pack first, then an auto-generated white glyph, then the system icon. One resolver, cached, so icons don't flip back.
- **Bundled Google Sans Flex** (rounded, OFL) as the default font everywhere.
- **Trimmed.** Removed notes, private spaces, word of the day, weather, alarm/battery readouts, screen time, contacts search, widgets page, and the usage/accessibility permission prompts.

## Blur on phones that hide the wallpaper

Many Android 13+ phones don't let apps read the wallpaper. Pick the same photo in **Settings → App drawer → Background image**, or share any image to **Set as drawer background**.

## Build

```
./gradlew assembleProdRelease
```

Credit for the original launcher goes to the mLauncher authors and contributors.
