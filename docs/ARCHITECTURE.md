# CraftRealm — Architecture

> A management panel **and** a full custom economy for Paper 1.21.x, built as a
> set of small, testable modules that happen to share one Bukkit plugin.

This document is the map. Read it top-down; every section points at the real
package it describes.

---

## 1. Design goals

| Goal | How the code achieves it |
| --- | --- |
| **No blocking work on the main thread** | Every database call returns a `CompletableFuture` and runs on a dedicated I/O pool (`core.db.AsyncExecutor`). Bukkit access is always marshalled back with `Scheduler.sync(...)`. |
| **Unit-testable without a server** | All money maths, market pricing, interest, loan schedules, auction fees, claim decay, quest generation, trophy tiers and message formatting sit in **pure** classes that take primitives/records — no `Bukkit.*`, no statics. |
| **Swappable storage** | One `Database` façade over HikariCP; SQL lives only in `Repository` classes; dialect differences are confined to `Dialect` (SQLite ⇄ MySQL). |
| **Public API** | `craftrealm-api` contains only contracts, immutable records and Bukkit events. The server registers `CraftRealmAPI` in the `ServicesManager`, so other plugins never touch internals. |
| **Everything configurable** | `Settings` is an immutable snapshot rebuilt on `/cr reload`; messages come from `lang/<locale>.yml` and support MiniMessage. |
| **Anti-dupe & auditability** | Append-only ledger, atomic conditional SQL updates, idempotency keys for notes/collections, and a rollback journal. |

## 2. Module graph

```
craftrealm-api     contracts · records · events                 (no logic)
      ▲
craftrealm-core    database · domain logic · i18n · GUI kit
      ▲
      ├── craftrealm-economy   Vault, /bal … /auction, jobs, market, notes
      ├── craftrealm-admin     Control Room, punishments, reports, health, audit
      ├── craftrealm-events    world events, claims, chest shops, quests, seasons
      ├── craftrealm-web       Javalin dashboard (zero Bukkit imports)
      └── craftrealm-plugin    wiring, resources, plugin.yml  →  CraftRealm.jar
```

Arrows are **compile-time** dependencies only. `craftrealm-web` deliberately
knows nothing about Bukkit: it receives a `WebBridge` interface that
the plugin module implements (`WebBridgeImpl`). That is what makes it testable and lets you run
the dashboard against any backend.

## 3. Package layout

Every listing below is the real content of the module; if a class is not here it
does not exist.

### `craftrealm-api` — `io.craftrealm.api`

```
CraftRealm.java             static service locator + ServicesManager registration
CraftRealmAPI.java          the root interface handed to other plugins
service/  EconomyService, ShardService, BankService, MarketService,
          AuctionService, JobService, ClaimService, QuestService,
          SeasonService, CosmeticService, EventService, AdminService
model/    Transaction, TransactionType, BalanceEntry, BankAccount, Loan,
          AuctionListing, MailboxEntry, CollectedEntry, MarketQuote, PriceTrend,
          JobType, JobProgress, Claim, ChestShop, Quest, QuestScope, QuestStatus,
          SeasonInfo, Bounty, BountyStatus, Report, ReportStatus, Warning,
          Punishment, PunishmentType, AuditEntry, PlayerView, PlayerNote,
          Cosmetic, CosmeticType, Treasure, ChunkHotspot, WorldHealth,
          HealthSnapshot, EconomyResult, StaffAction, Note
event/    BalanceChangeEvent (cancellable), ShardChangeEvent,
          TransactionCompletedEvent, MarketPriceChangeEvent, AuctionBidEvent,
          ClaimCreateEvent, JobLevelUpEvent, QuestCompletedEvent,
          ServerEventLifecycleEvent, SeasonEndEvent, CraftRealmReloadEvent
```

### `craftrealm-core` — `io.craftrealm.core`

```
db/         Database (HikariCP façade), DatabaseSettings, Dialect (SQLite/MySQL),
            Schema (DDL + versioned migrations), AsyncExecutor, Jdbc, SqlFunction,
            SqlConsumer, RowMapper
repo/       AccountRepository, TransactionRepository, BankRepository,
            LoanRepository, MarketRepository, AuctionRepository,
            JobRepository, PlayerRepository, BountyRepository,
            StaffRepository, WorldRepository, ProgressionRepository
economy/    EconomyServiceImpl (atomic ledger), ShardServiceImpl
bank/       BankServiceImpl, InterestCalculator, LoanSchedule
market/     MarketServiceImpl, MarketPriceEngine
auction/    AuctionServiceImpl, AuctionFeeCalculator
jobs/       JobServiceImpl, JobRewardCalculator, JobLevelCurve
command/    SubCommand, BaseCommand, CommandRouter (labels, aliases, permissions)
config/     Settings (immutable record tree) + per-feature settings records,
            ConfigLoader, WebSettings, DiscordSettings
text/       MessageBundle (MiniMessage + {placeholders}), Messages
ui/         Gui, GuiClick, GuiListener, GuiTheme, ItemBuilder, Pagination
util/       Scheduler, TimeUtil, Money, RateLimiter, Events
CraftRealmCore.java         logger + name cache shared by every module
```

### `craftrealm-economy` — `io.craftrealm.economy`

```
EconomyModule, EconomyContext   wiring and dependency bundle
// the Vault adapter is not here: it needs the Plugin instance and the
// ServicesManager, so it lives in craftrealm-plugin (VaultHook)
items/      CurrencyItems (mint, sign, verify), CurrencyService (redeem),
            NoteSecret (HMAC key file)
command/    BalanceCommand, PayCommand, BaltopCommand, ShopCommand,
            SellCommand, BankCommand, AuctionCommand, JobCommand,
            BountyCommand, CoinsCommand
gui/        ShopGui, SellGui, BankGui, AuctionGui, ListingGui, MailboxGui,
            JobGui, BountyGui
listener/   JobListener, CurrencyListener, BountyListener, StreakListener,
            PlacedBlockTracker (stops self-farmed blocks from paying)
bounty/     BountyService      streak/  StreakService
```

### `craftrealm-admin` — `io.craftrealm.admin`

```
AdminModule, AdminContext, AdminServices, AdminServiceImpl
command/    AdminCommand, PunishCommand, ShortcutPunishCommand, UnbanCommand,
            VanishCommand, StaffChatCommand, ReportCommand, ReportsCommand,
            NotesCommand, AuditCommand
gui/        ControlRoomGui, PlayersGui, PlayerDetailGui, InventoryViewGui,
            EconomyAdminGui, AlertsGui, MarketAdminGui, PunishmentsGui,
            WorldGui, HealthGui, LogsGui
staff/      AuditService, ReportService, StaffTools (vanish, staff chat)
punish/     PunishmentService (warn → escalation ladder → mute/ban)
health/     HealthMonitor (TPS/RAM snapshot), LagFinder (hot-spots + cleanup)
listener/   ModerationListener (mute, freeze, ban enforcement, chat filtering)
```

### `craftrealm-events` — `io.craftrealm.events`

```
EventModule        wiring, task scheduling, warm-up
world/             EventServiceImpl (gold rush, blood moon, market crash, caravan)
claims/            ClaimServiceImpl, ClaimListener, ClaimCommand
shops/             ChestShopService, ChestShopListener, ShopBrowseGui
quests/            QuestServiceImpl, QuestGui
seasons/           SeasonServiceImpl, SeasonGui
cosmetics/         CosmeticServiceImpl, CosmeticGui
treasure/          TreasureService
gui/               shared GUIs (claims, quests, seasons, cosmetics, events)
listener/          GameplayListener (quest progress, treasure detection),
                   CosmeticListener (prefix, name colour, particle trails)
command/           ClaimCommand, ChestShopCommand, QuestsCommand,
                   CosmeticsCommand, SeasonCommand, EventsCommand,
                   TreasureCommand
```

### `craftrealm-web` — `io.craftrealm.web`

```
WebPanel           Javalin lifecycle (start/stop, bind address, sessions)
WebPanelSettings   immutable mirror of core's WebSettings — the module has no
                   dependency on core, so it can even be run outside a server
WebBridge          the only contact surface: implement it to serve the dashboard
                   (status, players, economy, market, reports, audit, console,
                   broadcast, punish) — the plugin module implements it
resources/web/     index.html, style.css, app.js
```

### `craftrealm-plugin` — `io.craftrealm.plugin`

```
CraftRealmPlugin   enable/disable, startup order, reload, task scheduling
ApiImpl            CraftRealmAPI implementation (pure facade)
VaultHook          registers net.milkbowl.vault Economy at Highest priority
WebBridgeImpl      WebBridge backed by the live services and Bukkit
ConsoleBuffer      bounded ring buffer of the server log for the dashboard
DiscordNotifier    async webhook POST with JSON escaping
ConnectionListener warms/clears the per-player caches on join/quit
RootCommand        /craftrealm <reload|version|panel|save>
resources/         plugin.yml, config.yml, lang/en_US.yml, lang/fa_IR.yml
```

## 4. Request / data flow

**A player buys an item in the shop**

1. `ShopGui` → `EconomyService.withdraw(uuid, price, TransactionType.SHOP_BUY, reason)`.
2. `EconomyServiceImpl` validates the amount (finite, > 0, below the configured
   cap, rate-limited), then asks `AccountRepository` for an **atomic conditional
   update**: `UPDATE cr_accounts SET balance = balance - ? WHERE uuid = ? AND balance >= ?`.
3. Zero rows updated ⇒ insufficient funds ⇒ the future completes with `false`,
   the GUI plays the anvil sound and shows the lava-orange error line.
4. Success ⇒ the delta is written to the ledger (`cr_transactions`), the cache is
   refreshed, `BalanceChangeEvent` is fired **on the main thread**, and the GUI is
   re-rendered.

Nothing above blocks: steps 2–3 happen on the I/O pool, steps 1/4 on the tick thread.

**A GUI click**

`GuiListener` resolves the top inventory's `InventoryHolder`. If it is a `Gui`,
the listener cancels the event, looks up the action by slot and runs it. Actions
are plain `Consumer<GuiClick>` — no reflection, no magic strings.

## 5. Database

* One pooled `DataSource` (HikariCP), `jdbc:sqlite:plugins/CraftRealm/data/craftrealm.db`
  by default, `jdbc:mysql://…` when `storage.type: mysql`.
* Schema is created/migrated on enable by `Schema#apply`, which is idempotent
  (`CREATE TABLE IF NOT EXISTS` + `ALTER TABLE … ADD COLUMN` guarded by a
  version row in `cr_meta`).
* Money is stored as `DECIMAL(20,2)` so a value written by one node reads back
  identically on another. All arithmetic goes through `Money.round`.
* Timestamps are epoch millis (`BIGINT`) — no timezone surprises.
* `cr_transactions.op_id` is `UNIQUE`: a retried note-redeem or auction collect
  can never pay out twice.

## 6. Threading contract

| Code | Thread |
| --- | --- |
| `Repository`, `Database`, `AsyncExecutor` | I/O pool (never the main thread) |
| Domain maths (`MarketPriceEngine` …) | caller's thread, pure & fast |
| Anything touching `Player`, `World`, `Inventory` | main thread — enforced by `Scheduler.sync` |
| Web requests (Javalin) | Jetty threads; they read snapshots and enqueue commands |

`EconomyServiceImpl` keeps a `ConcurrentHashMap` cache of balances. It is the
**only** place a balance is read for gameplay; the database is the source of
truth, the cache is a read-through copy updated on write and on join.

## 7. Security model

* **Notes** (physical banknotes) carry a `PersistentDataContainer` id and a
  checksum; redeeming is a single atomic conditional delete + credit keyed by
  `op_id`, so a duplicated NBT payload pays out once.
* **Coins** are non-withdrawable; right-click swaps the item for Crowns through
  the same idempotent path.
* **Auction/mailbox** payouts are collected with `op_id = "collect:<id>"`.
* **Web** requires a bearer session minted from an admin token; tokens are
  compared in constant time, sessions expire, every web mutation is audited.
* **Suspicious transfers** (above `economy.alerts.large-transaction`) raise a
  staff alert and an audit entry from inside the economy service.

## 8. Theming

`GuiTheme` and `web/style.css` share one palette, taken from the game itself:

| Token | Hex | Used for |
| --- | --- | --- |
| Oak | `#b07d4f` | primary wood, headers |
| Spruce | `#6b4a2f` | frames, borders |
| Deepslate | `#36393d` | panels, web background |
| Redstone | `#c0392b` | destructive actions |
| Gold | `#e8b339` | currency, highlights |
| Copper | `#c77b53` | secondary accents |
| Emerald | `#2fbf71` | positive values |
| Lava | `#f26b21` | warnings |
| Parchment | `#e8d8b0` | card faces, lore text |

Icons are chosen for meaning (gold ingot = bank, villager spawn egg = jobs,
TNT = punishments, clock = events, comparator = settings). Every action plays a
note-block / villager / anvil sound, and each item carries optional
`custom-model-data` so a resource pack can swap in parchment textures.

## 9. Reload

`/craftrealm reload` is a controlled rebuild, not a variable swap:

1. cancel every scheduled task, stop the web panel, unregister the API, the Vault
   provider and **every** listener (`HandlerList.unregisterAll`), so no handler is
   ever registered twice;
2. re-read `config.yml` into a new immutable `Settings` and re-load the language
   bundles;
3. rebuild the services, the modules, the command router and the tasks from that
   snapshot;
4. re-prime the caches for players who are already online (balances, jobs,
   punishment state, cosmetics, quests) — otherwise a muted player could talk and
   a rich player would read as broke until they rejoined.

Only the database is kept across a reload. Because a connection pool cannot be
migrated safely while requests are in flight, a change to `storage.*` is reported
and applied on the next start rather than half-applied now.

## 10. Extending

* Other plugins: `CraftRealm.get().economy().deposit(...)` and listen to
  `BalanceChangeEvent` / `MarketPriceChangeEvent`.
* New currency sink: implement `TransactionType` usage in your own command and
  call the economy service — you get ledger, rollback and alerts for free.
* New server event: add its id to `events.pool`, then extend
  `EventServiceImpl` with a start/stop branch and the multipliers it returns.
* New quest template: add an entry to `quests.templates` in `config.yml`; the
  loader validates the kind, target and reward at startup and skips bad entries
  with a warning instead of failing the whole plugin.

## 11. Resource pack

`resourcepack/` is a complete, self-contained pack generated by
`resourcepack/tools/generate_textures.py` (Pillow only, deterministic output).

* **`pack.mcmeta`** — `pack_format` 46, which is what 1.21.x expects.
* **`assets/craftrealm/textures/item/gui/*.png`** — 25 hand-drawn 16×16
  "plaques": a parchment tile with a spruce frame and one accent glyph. The
  order matches `GuiTheme.Icon`, so `custom-model-data = offset + ordinal`
  always picks the right picture.
* **`assets/craftrealm/textures/item/currency/{coin,note}.png`** — a struck
  coin and a signed banknote, used at `offset + 500` and `offset + 501`.
* **`assets/minecraft/models/item/<material>.json`** — vanilla item models that
  add the `custom_model_data` overrides. Only *generated* items are touched
  (ingots, paper, dyes, arrows…); blocks, chests, clocks and compasses keep
  their vanilla models so the pack can never break normal gameplay.

Enable it with `theme.custom-model-data.enabled: true`. Without the pack the
numbers are inert and the menus use plain vanilla materials, which is exactly
what the fallback path is meant to look like.

## 12. Build and release

```
./gradlew clean build          # compile, test, shade
./gradlew dist                 # only the plugin jar
./gradlew :craftrealm-plugin:buildResourcePack
```

Release checklist:

1. `gradle.properties` → bump `projectVersion`.
2. `./gradlew clean build` on a JDK 21 machine **with** network access (the
   Paper API, HikariCP, JDBC drivers and Javalin resolve from remote repos).
3. Smoke-test the jar on a scratch Paper server: enable, `/bal`, `/shop`,
   `/admin`, `/cr reload`, restart, verify the ledger survived.
4. Attach `CraftRealm-<version>.jar` and, if the theme is enabled,
   `CraftRealm-ResourcePack.zip`.
