# Changelog

## 0.1.0 – First release

- Conflict icons for every active pack in the vanilla resource pack screen: *Overrides other packs*, *Is overridden*,
  *Overrides and is overridden*, *Completely covered*, *Against marking*, *Replaces sounds*, *Only intended conflicts*.
- Tooltip on the icon with the packs it overrides and is overridden by, warnings and replaced mod/Minecraft files.
- Detail window per pack and for all packs: files, language entries and sound events grouped by pack, search, file type
  filter and a side-by-side texture preview of every version.
- Live evaluation of the order in the screen, before "Done" is pressed; scanning in the background with a cache.
- Conflict rules like the game: whole files, language files per key (only loaded languages), `sounds.json` per event,
  merged atlases and fonts, filters, overlays, harmless byte-identical copies, pairwise counting like Mod Organizer 2.
- Intended markings in `config/packscope-intended.json`, matched by id, base name (survives pack updates) or `regex:`.
- Reports as Markdown and JSON: "Export report" in the detail window, `/packscope report` and `/packscope rescan`.
- Optional Packed Packs support: icons, tooltips, right-click items and a Conflicts button in its screen.
- Packs injected by mods such as KubeJS, built-in packs in mod jars and child packs of built-in packs are rated too.
- Client config with in-game config screen (*Mods → Packscope → Config*), English and German.
