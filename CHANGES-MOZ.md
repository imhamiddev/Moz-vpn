# Moz VPN 2.0.0

## Rebrand
- App renamed from FoxyVPN to **Moz VPN** (labels, notifications, widget, proxy identity, log files).
- Package / applicationId: `com.vauth.foxyvpn` -> `com.imhamiddev.mozvpn` (JNI package flag and ProGuard rules updated).
- Classes renamed `Foxy*` -> `Moz*`.

## Design system ("Moz Aurora")
- New violet + mint palette, light and dark. Connected = mint, connecting = amber.
- New type scale and larger corner radii.
- Shared components: `MozCard`, `StatTile`, `StatusChip`, `SectionTitle`, `ConnectOrb`, `MozBottomBar`.
- Floating pill bottom navigation: Home / Servers / Apps / Settings.
- Home re-ordered: location picker on top, animated connect orb, stat tiles, IP and quota cards.
- Servers and Settings use large-title headers; Settings sections are grouped in cards.
- New launcher icon (gradient shield with M) incl. themed (monochrome) layer; new notification, tile and widget icons.

## Split tunneling (new Apps tab)
- Modes: **All apps**, **Bypass selected apps**, **Only selected apps**.
- Search, "selected only" filter, system-app toggle, select/clear shown, per-app switches with icons.
- Separate saved lists for bypass and only-selected modes; existing exclusion lists are migrated to Bypass mode.
- "Only selected" with an empty list falls back to tunneling everything (fail-closed).
- A banner offers to reconnect when changes are made while the VPN is running.
