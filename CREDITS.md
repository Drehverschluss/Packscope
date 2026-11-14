# Credits

## Packscope

Packscope by Drehverschluss, licensed under the [MIT License](LICENSE). The license text is also in the root of the mod
jar.

## Assets

- The status icons (9 × 9 pixels) are my own work, drawn for this project.
- The mod logo is my own work. Its frame and magnifying glass are the same as in the logos of my mods Perilscope and
  Gearscope, so the three belong together.

Packscope ships no textures, sounds or other files of other mods or resource packs. The test packs used by the unit
tests are made for this project.

## Idea

The conflict view of **Mod Organizer 2**: an icon per entry that shows whether it overrides others or is overridden,
and a list of the conflicting files per pair. Only the idea is used, no code.

## Mods Packscope works with (not bundled)

- [Packed Packs](https://www.curseforge.com/minecraft/mc-mods/packed-packs) by fishstiz (MIT): optional. Packscope is
  compiled against its public API only (`packed_packs_api`, from the fishstiz Maven) and registers through the API's
  `ServiceLoader` entry. No code of Packed Packs is copied or bundled. Packscope is not made by or affiliated with the
  author of Packed Packs.
- KubeJS: no direct use and no compile dependency. Packscope only recognises the packs KubeJS adds to the game's
  resources, to rate them at the right place. Not bundled.

## License

Packscope is licensed under the [MIT License](LICENSE).
