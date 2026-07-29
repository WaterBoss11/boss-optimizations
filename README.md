# Boss Optimizations

A client-side performance and defensive mod for Minecraft **26.2** (Fabric).

Modules are independently toggleable, each owns its own config section, and each does nothing
at all when disabled. Nothing here changes gameplay — no entity data, ticking, pickup or
despawn behaviour is touched.

| Module | Status | What it does |
| --- | --- | --- |
| [Item Render Cap](#item-render-cap) | shipped | Caps how many dropped item entities are drawn on screen |

## Install

Drop `boss-optimizations-<version>.jar` into `mods/`. Client-side only — it does nothing on a
server and doesn't need to be installed on one.

Requires Fabric Loader ≥ 0.19.3 and Java 25. **Fabric API is not required.**

## Config

Single file at `config/boss-optimizations.json`, one section per module. Written back on load,
so the file always contains every current key including ones added by a newer version. Read at
startup only — there is deliberately no command and no config UI.

```json
{
  "itemRenderCap": {
    "enabled": true,
    "maxRenderedPerGroup": 5,
    "groupRadius": 4.0,
    "debug": false,
    "debugLogIntervalFrames": 60
  }
}
```

---

## Item Render Cap

Caps how many dropped item entities are drawn on screen, regardless of how many exist. Purely
visual — the items are all still there and still behave normally, they just aren't submitted to
the renderer.

### How it works

Item entities are bucketed into a uniform grid of cubes `groupRadius` blocks wide. In each
bucket, only the **highest** N entity IDs are drawn and the rest are skipped.

Highest, not lowest, and this matters. Entity IDs come from `ServerLevel.ENTITY_COUNTER`, an
`AtomicInteger` advanced with `incrementAndGet()`, so they rise monotonically — a freshly
dropped item always outranks everything already on the ground. Keeping the *lowest* IDs
therefore permanently starves new drops in any full group: the item is invisible for its whole
toss arc and then pops into existence once an older item leaves. Keeping the *highest* IDs
inverts that, so the item you just threw always renders and the cap falls on the old settled
pile instead.

ID order is still perfectly stable frame to frame — an entity's ID never changes — so this keeps
the no-flicker property that picking by distance-to-camera would have destroyed.

The set is rebuilt **exactly once per frame**, driven from `LevelExtractor.extract`. It is not
rebuilt on a timer: a timer expiring partway through an extraction pass swaps the answer set
mid-frame, so items checked before the swap and items checked after it get judged against
different sets — which is a direct cause of items failing to render even when they should be in
a rendered group.

The item under your crosshair is always drawn, even when its group is already full. Vanilla's
own `hitResult` never targets dropped items (`ItemEntity` isn't pickable), so the module runs
its own ray test out to 32 blocks against slightly-inflated item hitboxes. It also honours
`hitResult` if another mod has made item entities pickable.

### The hooks

Two mixins. `EntityRenderDispatcher.shouldRender` is the cull gate —  `LevelExtractor` calls it
per entity per frame and skips building a render state on `false`, so a hidden item costs
nothing rather than being drawn and thrown away. `LevelExtractor.extract` is a read-only frame
boundary marker.

### Settings

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Master switch for this module |
| `maxRenderedPerGroup` | `5` | Items drawn per group; `0` hides all but your crosshair target |
| `groupRadius` | `4.0` | Group cube width in blocks, clamped `0.5`–`128.0` |
| `debug` | `false` | Log group formation and selection stats |
| `debugLogIntervalFrames` | `60` | How often to emit a debug line; unstable frames always log |

### Reading the debug output

```
frame=1260 items=832 groups=47 visible=201 hidden=631 largestGroup=5 appeared=0 deselected=0 emptyGroups=0 crosshair=none
```

- **`deselected`** — items that still exist this frame but stopped being drawn. In a scene where
  nothing is moving this must be `0`. Anything else is flicker, and the module logs a `WARN`.
- **`emptyGroups`** — occupied groups that rendered nothing at all. Must always be `0`.
- **`appeared`** — the harmless counterpart (new drops, items coming into view).

One `INFO` line is logged the first time a cull decision is made, which confirms the mixin
actually applied.

### Known limitations

- The crosshair ray test doesn't check line of sight, so an item directly behind a wall under
  your crosshair is exempted from the cap. It's occluded anyway, so this costs at most one
  extra item.
- **Items in sustained motion across a group boundary can still flicker.** Grouping is a hard
  grid, so an item physically crossing from one cell into another legitimately re-competes for
  a slot there. A synthetic worst case — ten items oscillating across a cell boundary forever —
  measures 270 deselections over 599 frames (0.4508 per frame). Settled items are completely
  stable (exactly 0 over 500 frames), and moving items are usually new drops that win on ID
  anyway, so this mainly affects old items pushed by water or pistons. Raising `groupRadius`
  reduces how often boundaries are crossed.
- Untested against Sodium/Iris.

---

## Compatibility

**ItemPhysic Lite** (1.6.12 for 26.2): compatible, and structurally so rather than by luck.
ItemPhysic Lite hooks `ItemEntityRenderer.submit` / `extractRenderState`, plus
`ItemEntityRenderState` and `ItemEntity`. This mod hooks `EntityRenderDispatcher.shouldRender`
and `LevelExtractor.extract`. There is no shared mixin target between them.

The pipeline order also composes cleanly: `shouldRender` (this mod's gate) runs *before*
`extractRenderState` and `submit` (ItemPhysic's hooks). Items this mod hides never reach
ItemPhysic's code at all, and items it keeps get ItemPhysic's flat rendering normally.

Boss Optimizations deliberately does **not** try to replace ItemPhysic Lite.

Not yet run together in-game — the claim above is from inspecting both jars' mixin targets.

## Building

Requires JDK 25.

```
./gradlew build
```

Output lands in `build/libs/`. `./gradlew test` runs the unit suite, which covers the render
cap's group selection across simulated frames.

## License

MIT — see [LICENSE](LICENSE).
