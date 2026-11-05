# LumiFollow

LumiFollow is an independent Citizens movement plugin for BeautyQuests. Install
`build/libs/LumiFollow.jar` alongside Citizens and BeautyQuests, then restart.
It has no dependency on LumiDrugs. LumiDrugs does not bundle or link against LumiFollow.
Java 21 and a compatible Paper/Citizens server are required. The API currently compiles
against BeautyQuests 2.0; newer server versions require runtime verification.

Build LumiFollow with `./gradlew clean build`. The JAR is written to `build/libs/LumiFollow.jar`.

LumiDrugs is maintained separately at https://github.com/saboooor/LumiDrugs.
This plugin was extracted from that repository; existing quest configuration and Java API packages remain compatible.

## Following during a quest objective

Add a `lumi-follow` option to any regular stage in a BeautyQuests quest file:

```yaml
manager:
  branches:
    '0':
      stages:
        '0':
          stageType: lumidrugs-cartel-delivery
          options:
            lumi-follow:
              enabled: true
              npc-data: npc
              open-doors: true
              climbing: true
              max-climb-height: 6
              climb-timeout-ticks: 100
              teleport-recovery: true
              follow-teleports: true
              cross-world: false
      endingStages: {}
```

The stage type above is LumiDrugs' delivery objective; the option itself works with any
stage type. The objective supplies the spawned NPC's **entity UUID as a string** in its
BeautyQuests per-player stage data under `npc` (or the configured `npc-data` key).
For example, an objective provider calls:

```java
controller.updateObjective(quester, "npc", npc.getEntity().getUniqueId().toString());
```

LumiFollow resolves the entity across all Citizens registries, including temporary NPCs,
and follows the quest's individual player while that objective is active. Missing NPC data
waits without moving an NPC. NPC leases prevent two objectives from controlling the same
NPC. Ending/cancelling the objective, leaving the quest, unloading it, or disabling LumiFollow
releases pursuit. LumiFollow never completes the objective or creates/destroys the NPC.
The objective provider owns NPC creation, destruction and delivery progress.

Options are disabled by default; quests without this option do not acquire movement.
Set `enabled: false` to disable it explicitly. Movement settings belong in the quest file,
not LumiDrugs' `cartel.yml`. Stop the server before editing quest files and restart afterward.
The delivery quest needs LumiFollow installed for movement; ordinary LumiDrugs features
and stationary buyers do not require it.

LumiDrugs adds this option to newly generated cartel quests. Its managed
`lumidrugs-cartel.yml` file is upgraded once when the option is absent, preserving other
settings and saving the original as `lumidrugs-cartel.yml.pre-follow.backup`. Custom quest
files are not migrated: add the option yourself. Legacy movement settings in `cartel.yml`
are ignored; copy any custom door/climb settings into the quest option.

## Standalone escort objectives

LumiFollow also registers the `lumi-follow` **stage type**, distinct from the option with
the same name. Configure `escorts.<profile>` in `plugins/LumiFollow/config.yml`, including
a persistent Citizens `npc-id` and destination. Then use:

```yaml
stageType: lumi-follow
profile: default
```

This objective escorts that NPC to the profile destination and completes when both the
player and NPC arrive and validation requirements pass. The follow option runs alongside
an objective; the escort stage is itself an objective. The default profile is an example
and must be configured for your server's NPC and world.

## Movement behavior

Citizens handles ordinary navigation. Following stops within 2.5 blocks, uses a range of
64 blocks, and refreshes paths every 10 ticks. Speed multipliers are 1.15 walking and 1.5
when the player sprints away. Bounded climbing assistance supports up to 6 blocks for
100 ticks by default. Door handling opens blocks directly; turn it off for NPCs that
should leave doors alone. This does not add block breaking or arbitrary parkour.

Recovery searches only loaded chunks, respects the world border and teleport cancellation,
and waits if no safe landing exists. Cross-world movement is disabled for the quest option
by default. Disabling `follow-teleports` skips teleport-event recovery; distance recovery
can still apply when `teleport-recovery` is enabled.

The Java movement API remains available for other plugin authors: create one `LumiFollow`
service per owning plugin, call `follow(npc, player, FollowOptions.defaults())`, then
`release(npc)` on cleanup and `close()` on shutdown. All calls must run on the server main
thread. This API does not depend on LumiDrugs.

## License

GPL-3.0; see `LICENSE`.
