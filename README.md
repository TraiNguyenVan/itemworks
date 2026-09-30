# itemworks

Server-side Fabric mod for the `find` server. Gives shop-bought tools an
**excavation ability** and a **time-limited lease**, both described in a config
file rather than compiled in.

Live as `itemworks-0.1.0+mc26.3.jar` in the `find` mods directory.

## What it is, and why it exists

`itemworks` replaced two things on this server:

1. **Veinminer** (`veinminer-fabric` + `veinminer-enchant`) — it followed a vein by
   repeatedly scanning outward for connected blocks of the same type. That made its
   behaviour a *property of the mod*. An admin could not describe it in a file,
   could not vary it per tool, and could not reason about how far it would reach
   before mining stopped. Two tools with the same enchantment had identical
   behaviour by construction.
2. **`veinmine-lease`** — a separate mod that stamped a deadline on Veinminer's
   tools. Its logic is now `LeaseManager` here. The old project directory
   `/home/yes/projects/veinmine-lease` is retired; do not build or deploy from it.

The governing idea of the rewrite is **behaviour is data**. What a tool does is
decided by a JSON block an admin can read and edit, and a mistake in that block
costs one tool rather than the whole file.

The lease could not simply be a field in the shop's `prices.json`, which is worth
knowing: EconomyCraft hands out one fixed `ItemStack` per price entry, so every
buyer would receive the identical stack and share one clock. Stamping each stack
on first sight is what makes "24 hours after *your* purchase" true.

## Architecture

Two extension tiers, and the split between them is the whole design.

**Tier 1 — config only, no code.** The `excavate_offsets` ability takes its shape
from config, so a 3x3, a 3x3x3, a plus-shape and a tunnel are all edits to one
array. Every new tool should land here. **No rebuild, no jar swap, just a
restart.**

**Tier 2 — a new `Ability`.** For behaviour that is not a list of block positions
— tree felling, tilling, smelting — implement `Ability` and add exactly one
`AbilityRegistry.register(...)` call in `Itemworks.onInitializeServer()`. The core
event loop, the config loader and the lease never need editing again.

Per-tool model: a new sellable tool is **one config block plus one shop entry**,
no new class.

## File map

All source is under `src/main/java/dev/yes/itemworks/`.

| File | Purpose |
|---|---|
| `Itemworks.java` | Entrypoint (`DedicatedServerModInitializer`). Registers abilities, loads config, hooks the block-break and tick events. **Holds the re-entrancy guard** — see the design notes. |
| `ItemworksConfig.java` | Hand-rolled JSON parse of `config/itemworks.json`. Validates **per tool**, so one typo costs one tool and not the file. Copies the bundled default out on first run. |
| `ToolDefinition.java` | Record: one sellable tool — id, matcher, lease terms, ability map. |
| `ToolMatcher.java` | Decides whether a stack is "the" shop tool. **Marker matching only** — never display names. |
| `Ability.java` | The interface: `id()` and `onBlockBreak(ctx)`. Documents the tier split. |
| `AbilityContext.java` | Immutable bundle handed to an ability: player, level, pos, state, stack, tool, ability id, config. |
| `AbilityRegistry.java` | Static id → `Ability` map, populated once at startup. Rejects duplicate ids. |
| `abilities/ExcavateOffsets.java` | The only ability shipped. Breaks the eight blocks around the one broken, in a plane chosen by config. |
| `LeaseManager.java` | Stamps and enforces `itemworksLeaseUntil`. Ticks a sweep every `sweep_interval_ticks`, plus one sweep on join. |
| `AbilityConfig.java` | One ability's raw JSON, so abilities define their own keys. Expensive values (block tags, offsets) are compiled once and memoised. |
| `BlockFilter.java` | Resolves `blocks` entries — block tags and single ids — into a matcher. Unparseable entries are logged and skipped, not fatal. |

Resources: `src/main/resources/fabric.mod.json` and
`src/main/resources/config/itemworks.json` (the default that gets copied to
`config/itemworks.json` on first run, `_comments` block and all).

## Adding a tool

A config block in `config/itemworks.json`, and a shop entry in EconomyCraft's
`prices.json`. **No new class, no rebuild.**

```json
{
  "id": "tunneler",
  "match": {
    "item": "minecraft:iron_pickaxe",
    "marker": "tunneler"
  },
  "lease": {
    "enabled": true,
    "hours": 12.0,
    "notify_on_stamp": true,
    "notify_on_expire": true
  },
  "abilities": {
    "excavate_offsets": {
      "enabled": true,
      "require_sneak": true,
      "plane": "fixed",
      "blocks": ["#minecraft:base_stone_overworld"],
      "include_block_entities": false,
      "offsets": [
        [-1,-1,-1], [0,-1,-1], [1,-1,-1],
        [-1,-1, 0], [0,-1, 0], [1,-1, 0],
        [-1,-1, 1], [0,-1, 1], [1,-1, 1],
        [-1, 0,-1],            [1, 0,-1],
        [-1, 0, 1],            [1, 0, 1],
        [-1, 1,-1], [0, 1,-1], [1, 1,-1],
        [-1, 1, 0], [0, 1, 0], [1, 1, 0],
        [-1, 1, 1], [0, 1, 1], [1, 1, 1]
      ]
    }
  }
}
```

And the matching shop entry, as one member of the top-level `prices.json` object
(a fragment, not a standalone document):

```json
"minecraft:iron_pickaxe#itemworks_tunneler": {
  "category": "itemworks", "stack": 1,
  "unit_buy": 4000, "unit_sell": 0, "dynamic_price_enabled": false,
  "components": {
    "minecraft:custom_data": { "itemworks": "tunneler" },
    "minecraft:custom_name": { "text": "Tunneler Pickaxe", "color": "gold", "italic": false },
    "minecraft:lore": [
      { "text": "Sneak-mine a 3x3x3 tunnel of stone.", "color": "gray", "italic": false },
      { "text": "Expires 12h after purchase.", "color": "dark_red", "italic": false }
    ]
  }
}
```

The `"marker"` in config and `"itemworks"` in the shop entry are **the same
string**. That is the link between the two files. Restart the server afterwards —
`config/itemworks.json` has no file watcher.

The `offsets` list above is all 26 neighbours of a 3x3x3, origin excluded —
count them before shipping a shape. That verbosity is the argument for
`plane: "view"` on anything that is a ring, and the reason the `dy: 1` bug was
possible: **an explicit list must be counted, not eyeballed.**

## Config schema

`config/itemworks.json`, copied from the jar on first run. Deleting it restores
the shipped defaults. It carries a `_comments` object documenting every key
in-place; that block ships in the jar and is the thing to read first.

Top level:

| Key | Meaning |
|---|---|
| `sweep_interval_ticks` | Ticks between full inventory sweeps for lease expiry. 100 = 5s. Values under 20 are clamped up to 100. |
| `tools` | Array of tool definitions. |

Per tool: `id` (log lines only, must be unique), `match`, `lease`,
`abilities`.

`match` requires **both** `item` (the base item) and `marker` (the custom-data
value). Both must match, which is what stops a player's own netherite pickaxe
from being treated as a leased tool — `LeaseManager` destroys expired stacks, so
a loose match here destroys people's tools.

`lease` is `enabled`, `hours`, `notify_on_stamp`, `notify_on_expire`.
Fractional hours work, which is the only practical way to test expiry: `0.01` is
36 seconds.

### `excavate_offsets` keys

| Key | Meaning |
|---|---|
| `enabled` | Defaults to `true` when the key is absent — abilities are opt-out. |
| `require_sneak` | Only fires while the player holds shift. Default `true`. |
| `plane` | `"view"` or `"fixed"`. See below. Default `"fixed"` when absent. |
| `view_threshold` | How far off level the player must be looking before `"view"` switches to a horizontal ring. This is the **sine of the pitch**: 0.5 ≈ 30°. Lower means it takes a steeper look to flatten. |
| `blocks` | List of block **tags** (`#minecraft:…`) or single **ids**. See below. |
| `include_block_entities` | Whether chests, signs and other block entities can be broken by a wide swing. Default `false`. |
| `offsets` | Only read when `plane` is `"fixed"`. `[dx,dy,dz]` triples relative to the block that was broken. **Do not include `[0,0,0]`** — the origin is already broken. |

**`plane: "view"`** picks the plane from where the player is looking, and is the
default in practice because it is the one that feels right in the hand:

- Looking **up or down steeply** (more than `view_threshold` off level) gives a
  horizontal ring in x/z at the broken block's own y. Digging into the ground
  clears the floor in front of you and nothing above or below it.
- Looking **roughly level** gives a vertical ring in the plane being faced — the
  3x3 of the wall you are looking at, and nothing in front of or behind it.

Either way the ring **never leaves its own plane**, so the tool stays one block
deep in the direction you are digging. A full swing costs 9 durability.

**`plane: "fixed"`** uses the `offsets` list verbatim. This is the escape hatch
for shapes that are not a ring: a 3x3x3 tunnel, a plus, a line.

> **Why `view` exists, and the bug that caused it.** The first deployed build had
> `plane: "fixed"` with a hand-written list of triples that were *all* at
> `dy: 1`. It looks like a 3x3 and behaves like one, but it mines the plane one
> block **above** the block you broke. The docs described it as "3x3, depth 1",
> which the numbers did not support. The lesson is not "check the offsets" — it is
> that a literal offset list is easy to author wrong and easy to misdescribe.
> Deriving the plane from the look angle removes the entire class of mistake.
> Note there is deliberately **no `depth` key**: an earlier draft had one and it
> could not express this. Use `"view"` for a ring, `"fixed"` plus an explicit list
> for anything else, and make the numbers say what the name says.

**`blocks` — tags vs ids.** Prefer tags. `#minecraft:base_stone_overworld` and
`#minecraft:mineable/shovel` are defined by the game, so a config change picks up
new blocks automatically and a typo in a hand-written id list is not a
housekeeping burden. Single ids are accepted for a hand-curated list. An
unresolvable entry is logged and skipped, not fatal. An **empty** resolved filter
is treated as a misconfiguration: the ability logs one warning and never fires,
rather than matching everything.

The origin block must also pass the filter. That is what stops a shovel
stone-mining and a pickaxe tunnelling through dirt.

## Design notes — read before changing anything

**Items are identified by a `minecraft:custom_data` marker, never by display
name.** The key is `itemworks`; the value is the tool id, set by the shop's
`prices.json` components. The previous lease mod matched on a **name substring**,
which is wrong twice over: it silently stops working for everyone the moment an
admin renames the shop item in `prices.json`, with no error anywhere; and it is
far too loose, because a player can rename their own pickaxe with an anvil and
would then have it destroyed on expiry. The marker is set once by the price entry
and display text cannot drift away from it.

**The mod hooks `PlayerBlockBreakEvents.AFTER`, never `BEFORE`.** Fabric stops
invoking listeners at the first one that returns `false`, so cancelling in BEFORE
could suppress ShopGuard's own listener and let a player mine straight through a
claim. AFTER is `void` and non-cancellable, which is exactly the property wanted
here.

**Chained breaks use `ServerPlayerGameMode#destroyBlock`, NOT `setBlock(air)`.**
Fabric's `ServerPlayerGameModeMixin` injects both BEFORE and AFTER into
`destroyBlock` itself, so every single chained break re-runs ShopGuard's
protection check. A 3x3 straddling a claim border therefore breaks the blocks
the player owns and leaves the neighbours alone. `setBlock(air)` would be faster
and **completely unprotected** — no break event fires and ShopGuard never sees
it. Do not "optimise" this.

**A `ThreadLocal<int>` depth guard is mandatory.** Chained `destroyBlock` calls
fire the break event synchronously, so without the guard one click recurses:
the neighbour break triggers the ability again, which breaks *its* neighbours,
and a connected stone region disappears in a single tick. It is `ThreadLocal`
because a dedicated server can tick several levels on different threads and the
depth is per-thread by definition, and it is an `int[]` holder because a
`ThreadLocal` cannot store a mutable `int`. Abilities therefore never have to
defend against their own re-entry.

**Never mutate the `minecraft:block_drops` gamerule.** The alternative mod
considered for this job, amethyst-pickaxes, was rejected partly for doing exactly
that — toggling it dozens of times per swing from a datapack. It races between
players, and if the server errors mid-sequence it leaves the rule off
**server-wide**, silently deleting every drop for everyone. It is one global
boolean and it is not a safe place to keep per-swing state.

**`minecraft:custom_data` is a copy-on-read component: read it with
`copyTag()`, and write it back wholesale.** The lease stamp
(`itemworksLeaseUntil`) and the tool marker (`itemworks`) **share one compound**.
Stamping must therefore read, modify and re-set the whole tag, and must re-assert
the marker — never build a fresh tag from scratch, or the marker is clobbered and
the tool instantly stops matching.

**A stamped tool no longer exactly matches its price entry**, because
`ItemStack.isSameItemSameComponents` is an exact test. `/worth` and `/sell` then
fall back to the plain base item at roughly the vanilla rate. That is a money
sink rather than a faucet — a 12,000 purchase can be cashed out for a vanilla
pickaxe and no more. It is a deliberate trade, not a bug to fix.

**Durability floor.** A swing is refused when the tool has 1 durability left,
because a 3x3 costs 9 and breaking through would destroy it outright.

## Build and deploy

There is **no working JDK on this host** (`java: not found`). Every build runs in
the `ghcr.io/pelican-eggs/yolks:java_25` container, which carries a complete
Temurin 25 at `/opt/java/openjdk`. Do not try to fix the host JDK.

```sh
cd /home/yes/projects/itemworks
docker run --rm --user 1000:1000 --network host \
  -e GRADLE_USER_HOME=/ecg -e JAVA_HOME=/opt/java/openjdk \
  -v /home/yes/projects/itemworks:/p -v /home/yes/.cache/ec-gradle:/ecg \
  -w /p --entrypoint sh ghcr.io/pelican-eggs/yolks:java_25 \
  -c './gradlew build --no-daemon'
```

`--network host` is required — containers have no outbound TCP otherwise.
`GRADLE_USER_HOME=/ecg` is the warm cache at `/home/yes/.cache/ec-gradle`; losing
it means re-downloading the world.

### Pin the Gradle wrapper to 9.7.1

`gradle/wrapper/gradle-wrapper.properties` **must** say `9.7.1`:

```
distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip
```

`9.7.1` is the only version cached in `/home/yes/.cache/ec-gradle`. `9.7.0` is
**not** cached — only a `.zip.part` — so a project pinning it tries to download a
distribution that is not there and fails. `shopguard` still pins 9.7.0 and
therefore still cannot be built; that was one of two failed boots on the 2026-09-30
deploy. **Pin new projects to 9.7.1.**

Toolchain, which must track the server (`gradle.properties`): `minecraft_version=26.3`,
`loader_version=0.19.5`, `loom_version=1.18.1`, `fabric_api_version=0.161.0+26.3`,
and `options.release = 25` in `build.gradle`.

Build result: `build/libs/itemworks-0.1.0+mc26.3.jar` — 31537 bytes, 13 classes,
builds clean in ~12s warm.

```sh
sha256sum build/libs/itemworks-0.1.0+mc26.3.jar
# edbee5f68ad376d03cd810b74f27afd200e2f00a25bb56b6031d5a786968ce1b
```

### Deploy

The volume is owned by uid/gid **105:107** and is **not writable directly from the
host** (permission denied, and there is no passwordless sudo). All writes go
through a container at that uid:

```sh
V=/var/lib/pterodactyl/volumes/536eac8f-ad7d-436f-900e-8f1605962b63
docker run --rm -i --user 105:107 -v "$V":/v --entrypoint sh ghcr.io/pelican-eggs/yolks:java_25 \
  -c 'cat > /v/mods/itemworks-0.1.0+mc26.3.jar && sha256sum /v/mods/itemworks-0.1.0+mc26.3.jar' \
  < /home/yes/projects/itemworks/build/libs/itemworks-0.1.0+mc26.3.jar
```

- **`-i` is mandatory.** Without it `docker run` does not attach stdin, the
  redirect silently produces a **0-byte jar**, and you only find out when the
  server fails to boot. This caused the other failed boot on 2026-09-30.
- **Always verify with `sha256sum` on both sides.** Compare the container's output
  to the local hash above. Do not trust the copy. The 0-byte jar boots to a crash,
  not a warning.
- Mods are read at boot only. Restart the server after swapping the jar.

### The `server` entrypoint, not `main`

A `DedicatedServerModInitializer` must be declared under the `"server"` entrypoint
in `fabric.mod.json`. Under `"main"` the server refuses to boot with:

```
LanguageAdapterException: ... cannot be cast to ModInitializer
```

That is the Kotlin/Fabric language-adapter path firing, not a compile error — the
code builds fine and the jar deploys fine, and the failure only appears at boot.
`itemworks` declares it correctly.

## Known gaps / not yet tested

Nothing here has been verified in game. Do not assume any of it works:

- **A 3x3 straddling a claim border.** The `destroyBlock` path is supposed to
  re-run ShopGuard per block, but the interaction has not been watched happen.
  This is the single most important thing to test.
- **A plain un-marked netherite tool must be inert** — no ability, no lease, not
  destroyed. The two-part `match` is designed for this and it is untested.
- **Cross-tool block filtering.** That a shovel does not stone-mine and a pickaxe
  does not dig dirt, given both use the same ability.
- **The `plane: "fixed"` path has never been exercised in game.** It is the
  Tier 1 escape hatch and therefore the most important untested code.
- **The durability floor** has not been tested against a nearly-broken tool.
- **Lease survival across relog** — the sweep on join is written but unproven.
- **Measured `/sell` income** for a stamped tool. The fallback to the plain base
  item is reasoned about, not measured.

Also noted, cosmetic: `settings.gradle` still says `rootProject.name =
'veinmine-lease'`, left over from the rename. It is harmless — the jar name comes
from `archives_base_name=itemworks` — but it is misleading. Left alone because
it is not worth a rebuild.

## Further documentation

This README lives **outside** the pterodactyl docs tree, so those are absolute
paths on this host, not relative links:

- `/home/yes/pterodactyl/operations/itemworks-plan.md` — the design doc: why this
  replaced Veinminer, the rejected alternatives, the full test matrix, the
  `prices.json` migration.
- `/home/yes/pterodactyl/operations/itemworks-admin.md` — the admin runbook, plus
  the Minecraft 26.3 console/NBT traps (`mcprobe.py`).
- `/home/yes/pterodactyl/operations/build-mods.md` — the build and deploy runbook
  shared by all three custom mods.
