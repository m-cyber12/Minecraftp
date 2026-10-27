# Forge 1.20.1 edition

CraftRealm's original implementation is a Paper plugin for Minecraft 1.21.4. It
uses Bukkit/Paper APIs in the public API, configuration, commands, menus,
listeners and service adapters; that JAR cannot run on a Forge-only server.
Changing only the Minecraft version or the plugin descriptor would not make it
compatible.

A separate, native Forge target now lives in `craftrealm-forge`. It targets
Minecraft 1.20.1 / Forge 47 and compiles to Java 17 bytecode, the runtime level
required by Minecraft 1.20.1. It deliberately does not depend on the Bukkit
modules. The existing Paper modules remain intact while the Forge adapter is
ported, so their API types are not accidentally bundled into or loaded by the
Forge mod.

The Forge implementation now has a SQLite/Hikari storage layer, account creation
on player login, a durable cents-based transaction ledger, and native Brigadier
commands for `/balance` (`/bal`), `/pay`, and `/baltop`. Payments are applied in a
single SQL transaction and return to the server thread before sending feedback.
These are the first ported features; the rest of the Paper feature set remains
unported.

## Build

Install JDK 17 and run:

```shell
./gradlew :craftrealm-forge:build
```

The reobfuscated mod artifact is under `craftrealm-forge/build/libs/`. The
GitHub Actions `Build` workflow installs Java 17 as a Gradle toolchain and Java
21 for the legacy Paper modules; it compiles, tests and packages both editions.

## Porting status

The Forge entry point and `mods.toml` are in place and the workflow compiles the
Forge target. This is the foundation, not yet a feature-complete port. The
Bukkit economy, admin, GUI and event adapters still need Forge-native
implementations. Pure persistence/domain code can be shared after its remaining
Bukkit model types are separated behind platform-neutral interfaces. Do not
install the Paper JAR on Forge or treat the bootstrap mod as feature-complete.

## Port sequence

1. Separate Bukkit types from the public contracts and data/config layer.
2. Port persistence-backed economy, bank, auction, market and job services.
3. Implement Forge commands, player lifecycle hooks and inventory menus.
4. Port moderation, claims, events, quests, seasons, shops and cosmetics.
5. Validate on a dedicated Forge 1.20.1 server and publish a Forge-only release.
