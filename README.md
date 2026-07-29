# Item Render Cap

A single-purpose, client-side Fabric mod for Minecraft **26.2**. It caps how many dropped item
entities are actually drawn on screen, no matter how many exist.

This is **purely visual**. The mod never touches entity data, ticking, pickup, hoppers or
despawn logic — the items are all still there and still behave normally, they just aren't
submitted to the renderer.

## How it works

Item entities are bucketed into a uniform grid of cubes `groupRadius` blocks wide. In each
bucket, only the lowest N entity IDs are drawn and the rest are skipped.

Entity ID is the tie-break rather than distance-to-camera on purpose: it doesn't change as you
move, so the surviving set stays stable and the pile doesn't shimmer. The set is rebuilt at most
once every 50 ms (one tick) and held constant in between, so every item in a given frame gets a
consistent answer.

The item under your crosshair is always drawn, even when its group is already full. Vanilla's
own `hitResult` never targets dropped items (`ItemEntity` isn't pickable), so the mod runs its
own ray test out to 32 blocks against slightly-inflated item hitboxes. It also honours
`hitResult` if another mod has made item entities pickable.

## The hook

One mixin, one method. `LevelExtractor` calls `EntityRenderDispatcher.shouldRender` for every
entity each frame and skips building a render state for anything that answers false. Returning
false there drops the item out of the render pass before its render state is built, so a hidden
item costs nothing rather than being drawn and thrown away.

## Config

Written to `config/itemrendercap.json` on first launch. Read at startup only — there is
deliberately no command and no config UI.

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Master switch. When false the mod does nothing at all. |
| `maxRenderedPerGroup` | `5` | Items drawn per group. `0` hides everything except your crosshair target. |
| `groupRadius` | `4.0` | Group cube width in blocks. Clamped to `0.5`–`128.0`. |

## Building

Requires JDK 25.

```
./gradlew build
```

Output lands in `build/libs/`. Client-side only — it does nothing on a server and doesn't need
to be installed on one.

## Dependencies

Fabric Loader only. **Fabric API is not required** — the mod needs just the client entrypoint
and vanilla classes, so keeping it out reduces the footprint.

## Known limitations

- The crosshair ray test doesn't check line of sight, so an item directly behind a wall under
  your crosshair is exempted from the cap. It's occluded anyway, so this costs at most one
  extra item.
- Untested against Sodium/Iris.

## License

MIT — see [LICENSE](LICENSE).
