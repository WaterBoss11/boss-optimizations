# Item Render Cap

A single-purpose, client-side Fabric mod for Minecraft **26.2**. It caps how many dropped item
entities are actually drawn on screen, no matter how many exist.

This is **purely visual**. The mod never touches entity data, ticking, pickup, hoppers or
despawn logic — the items are all still there and still behave normally, they just aren't
submitted to the renderer.

## How it works

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
| `debug` | `false` | Log group formation and selection stats. Turn on to diagnose missing or flickering items. |
| `debugLogIntervalFrames` | `60` | How often to emit a debug line. Unstable frames are always logged regardless. |

### Reading the debug output

With `debug: true` you get lines like:

```
frame=1260 items=832 groups=47 visible=201 hidden=631 largestGroup=5 appeared=0 deselected=0 emptyGroups=0 crosshair=none
```

The two numbers that matter for diagnosing flicker:

- **`deselected`** — items that still exist this frame but stopped being drawn. In a scene where
  nothing is moving this must be `0`. Anything else is flicker, and the mod logs a `WARN`
  naming it.
- **`emptyGroups`** — occupied groups that rendered nothing at all. Must always be `0`.

`appeared` is the harmless counterpart (new drops, items coming into view), so a non-zero
`appeared` with a zero `deselected` is normal.

The mod also logs one `INFO` line the first time it makes a cull decision, which confirms the
mixin actually applied.

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

## Compatibility

**ItemPhysic Lite** (1.6.12 for 26.2): compatible, and structurally so rather than by luck.
ItemPhysic Lite hooks `ItemEntityRenderer.submit` / `extractRenderState`, plus
`ItemEntityRenderState` and `ItemEntity`. This mod hooks `EntityRenderDispatcher.shouldRender`
and `LevelExtractor.extract`. There is no shared mixin target between them.

The pipeline order also composes cleanly: `shouldRender` (this mod's gate) runs *before*
`extractRenderState` and `submit` (ItemPhysic's hooks). Items this mod hides never reach
ItemPhysic's code at all, and items it keeps get ItemPhysic's flat rendering normally.

Not yet run together in-game — the claim above is from inspecting both jars' mixin targets.

## Known limitations

- The crosshair ray test doesn't check line of sight, so an item directly behind a wall under
  your crosshair is exempted from the cap. It's occluded anyway, so this costs at most one
  extra item.
- **Items in sustained motion across a group boundary can still flicker.** Grouping is a hard
  grid, so an item physically crossing from one cell into another legitimately re-competes for
  a slot there. A synthetic worst case — ten items oscillating across a cell boundary forever —
  measures ~0.45 deselections per frame (`ItemGroupSelectorTest`). Settled items are completely
  stable (measured at exactly 0 over 500 frames), and moving items are usually new drops that
  win on ID anyway, so this mainly affects old items pushed by water or pistons. Raising
  `groupRadius` reduces how often boundaries are crossed.
- Untested against Sodium/Iris.

## License

MIT — see [LICENSE](LICENSE).
