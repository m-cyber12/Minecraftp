# CraftRealm

**CraftRealm's full feature set currently runs as a Paper 1.21.4 plugin. A native
Forge 1.20.1 edition is now being ported in `craftrealm-forge`.**

> **Forge status:** The Forge module now has native SQLite-backed balances, payments, leaderboards,
> banking/loans, chunk claims/protection, five profession tracks, and a configurable
> dynamic-price item market, audited operator wallet adjustments, and temporary
> timed profession reward events, and daily profession quests; major features are still being ported. The Bukkit/Paper JAR will not
> run on Forge. See [the Forge port plan](docs/FORGE-PORT.md) for scope and status.

CraftRealm replaces the usual pile of economy plugins with one coherent system:
Crowns in a double-entry ledger, a vault that pays interest, loans with due dates,
an auction house with a mail-style collection box, a supply-and-demand market,
five careers, a Control Room for staff, and a creative layer of land claims, chest
shops, quests, seasons, cosmetics, treasure hunts and world events.

Everything is asynchronous, configurable, translatable (English + Persian) and
exposed through a small public API for other plugins.

```
Oak and spruce browns · deepslate greys · redstone red · gold and copper
Emerald for "good" · lava orange for "careful" · parchment for text
```

---

## Table of contents

1. [Feature tour](#1-feature-tour)
2. [Requirements](#2-requirements)
3. [Building from source](#3-building-from-source)
4. [Installing](#4-installing)
5. [First run](#5-first-run)
6. [Commands](#6-commands)
7. [Permissions](#7-permissions)
8. [Configuration](#8-configuration)
9. [How the economy stays honest](#9-how-the-economy-stays-honest)
10. [The Control Room](#10-the-control-room)
11. [The web panel](#11-the-web-panel)
12. [Developer API](#12-developer-api)
13. [Project layout](#13-project-layout)
14. [Testing](#14-testing)
15. [Troubleshooting](#15-troubleshooting)

---

## 1. Feature tour

### Currency — "Emeralds & Ore Standard"

| Piece | What it does |
| --- | --- |
| **Crowns** (primary) | Named and symbolised in `config.yml`. Stored as `DECIMAL(20,2)`, every movement written to an append-only ledger. |
| **Shards** (premium) | Earned from quests, treasures, season rewards and streak milestones. Deliberately **not** purchasable — there is no shop or command that sells them. |
| **Physical money** | `/coins coin` mints a coin item, `/coins note` signs a banknote. Both carry a serial number and an HMAC signature in the item's persistent data; the serial is burned in `cr_banknotes` the first time it is redeemed, so copying an item through a dupe glitch produces a worthless copy. |
| **Vault** | Registers as the Vault economy provider at `ServicePriority.Highest`, so shops, kits and quests that already speak Vault just work. |

### Banking

* **Vault (savings)** — deposit and withdraw from a GUI; daily interest with a
  configurable rate, period and interest-bearing ceiling.
* **Loans** — principal limited by a multiple of your wallet + vault; fixed term,
  daily rate, due date, one-shot penalty on default and an automatic
  `ACTIVE → DEFAULTED` transition after the configured grace period.

### Market, auction and trade

* **Dynamic market** (`/shop`) — every material has an anchor price. Selling pushes
  the price down, buying pushes it up, prices drift back towards the anchor each
  hour. The GUI shows base price, current price, sold/bought volume, pressure and a
  ▲ / ▼ / ▬ trend arrow.
* **Auction house** (`/auction`) — listing fee, min/max price, configurable
  duration, minimum bid increment, sales tax, buy-now, anti-snipe bid rules and a
  **mailbox** that holds items *and* money until the player has room for them.
* **Player chest shops** — a chest plus a sign becomes a shop (hologram price
  line included). Stock is escrowed in the ledger, so breaking the chest cannot
  destroy or duplicate goods.
* **Bounties** — escrowed Crowns, optional fee, broadcast on claim.

### Careers, streaks and quests

* **Five jobs** — Miner, Farmer, Hunter, Fisher, Builder. XP curve, level
  multiplier, per-job daily cap, and a placed-block tracker so you cannot farm
  your own walls.
* **Daily streaks** — escalating login reward, with Shard milestones.
* **Quest board** — daily and weekly templates from `config.yml`, progress
  tracked from real gameplay events, rewards claimed once (guarded by a
  compare-and-set on the quest row, not a "trust me" boolean).

### Creative layer

* **Land claims** — pay per block with Crowns, pay daily upkeep, decay through
  three stages (`GRACE → DECAYING → WILDERNESS`) if you stop paying.
* **World events** — Gold Rush (double ore value), Blood Moon (double mob
  drops), Market Crash (suppressed prices), Merchant Caravan (rare trader).
  Manual from `/events`, or automatic on a timer with a minimum gap.
* **Treasure hunts** — staff bury caches with a clue and a Shard payout;
  `/treasure hint` points players at the nearest one.
* **Seasons** — 30-day leaderboard scored on Crowns *earned* (not held), with
  Crown/Shard/title rewards for the top ranks.
* **Cosmetics** — chat prefixes, name colours and particle trails bought with
  Shards and equipped per type.

### Staff tools

Control Room GUI (Players, Economy, Punishments, World, Health, Logs), inventory
and ender-chest inspection, balance editing, teleport, freeze, mute, kick,
tempban, IP history, staff notes, warnings with an auto-escalation ladder,
vanish, staff chat, a report queue, a lag-source finder with one-click cleanup,
and an append-only audit trail of every staff action.

---

## 2. Requirements

| | |
| --- | --- |
| Target | Forge 1.20.1 mod (port in progress) or legacy Paper 1.21.4 plugin |
| Forge runtime | Minecraft **1.20.1**, Forge **47.x**, Java **17** |
| Paper runtime | Paper **1.21.4**, Java **21** (legacy edition) |
| Build | Gradle 8.10.2 wrapper; GitHub Actions installs both JDK toolchains |
| Optional | Vault for the legacy Paper plugin, MySQL 8 for either edition when ported |

SQLite is the default backend and needs no setup.

---

## 3. Building from source

```bash
git clone <this repository> CraftRealm
cd CraftRealm

# Build/test all configured targets (JDK 17 and 21 toolchains required)
./gradlew build

# Native Forge 1.20.1 mod (Java 17 bytecode)
./gradlew :craftrealm-forge:build

# Legacy Paper 1.21.4 plugin (Java 21 bytecode)
./gradlew :craftrealm-plugin:shadowJar
./gradlew dist                 # legacy Paper jar only
```

Artifacts land at:

```
craftrealm-forge/build/libs/CraftRealm-Forge-1.0.0-all.jar  # Forge mod; port in progress
craftrealm-plugin/build/libs/CraftRealm-1.0.0.jar       # full legacy Paper implementation
```

> **Note on offline builds.** The first build downloads the Paper API,
> HikariCP, JDBC drivers, Javalin and JUnit from `repo.papermc.io`, Maven Central
> and JitPack. A machine without internet access cannot resolve those
> dependencies; use a cache-carrying `--offline` build after one online build.

Optional extras:

```bash
./gradlew test                                        # unit tests only
./gradlew :craftrealm-plugin:buildResourcePack         # resourcepack/ → zip
```

---

## 4. Installing

1. Copy `CraftRealm-1.0.0.jar` into `plugins/`.
2. (Optional) Drop `CraftRealm-ResourcePack.zip` into your pack host and set it in
   `server.properties` — the GUI icons use custom model data when
   `theme.custom-model-data.enabled` is `true`.
3. (Optional) Install **Vault** if other plugins need an economy provider.
4. Start the server once, then stop it and edit `plugins/CraftRealm/config.yml`.
5. Start again. On first start the plugin writes:

```
plugins/CraftRealm/
├── config.yml
├── lang/en_US.yml
├── lang/fa_IR.yml
├── data/note.key        # HMAC key for banknote signatures — back this up
└── craftrealm.db        # SQLite (unless storage.type: mysql)
```

> **Back up `data/note.key`.** Without it, existing banknotes cannot be
> verified. Deleting it invalidates every note in circulation (existing items
> become worthless rather than forgeable — this is deliberate).

---

## 5. First run

1. **Choose a currency name** — `economy.currency.name` and `symbol`.
2. **Pick your language** — `messages.locale: en_US` or `fa_IR`.
3. **Decide on storage** — leave `sqlite` for one server. For a network switch to
   `mysql`; every server then shares one ledger, so a player cannot spend the
   same Crowns twice by hopping servers.
4. **Set staff tiers** — `staff.tiers` maps permission nodes to tiers; grant
   `craftrealm.tier.mod` to your moderators.
5. **Warm up the market** — the anchor prices under `market.base-prices` are what
   everything else drifts back to. Adjust them to your server's scale before
   players start trading.
6. `/craftrealm reload` re-reads `config.yml` and the language files and rebuilds
   the services behind them (listeners, tasks and the web panel are restarted, so
   edits take effect without a server restart). Changing `storage.*` needs a real
   restart — the plugin says so in the console instead of silently ignoring it.

---

## 6. Commands

### Players

| Command | Aliases | Description |
| --- | --- | --- |
| `/balance [player]` | `bal`, `money` | Wallet, vault and Shards |
| `/pay <player> <amount>` | | Send Crowns (rate-limited, logged) |
| `/baltop [page]` | `balancetop`, `moneytop` | Richest accounts |
| `/shop [category]` | | Market GUI with trend arrows |
| `/sell` | `sellall` | Sell counter |
| `/bank` | | Vault, interest and loans |
| `/auction` | `ah`, `auctions` | Auction house + mailbox |
| `/jobs` | `job`, `careers` | Career board |
| `/bounty [player] [amount]` | `bounties` | Bounty board |
| `/coins <coin\|note\|redeem> [amount]` | `cash`, `note` | Mint or redeem physical money |
| `/claim <create\|list\|info\|remove>` | `claims`, `land` | Land claims |
| `/chestshop [list\|remove <id>]` | `myshops` | Chest shops |
| `/quests` | `quest` | Daily and weekly quests |
| `/cosmetics [type]` | `cosmetic`, `titles` | Titles, colours and trails |
| `/season` | `seasons`, `leaderboard` | Season standings |
| `/events [stop <id>]` | `event` | World events |
| `/treasure <hint\|list>` | `hunt` | Treasure hunt hints |
| `/report <player> <reason>` | | Report a player |

### Staff

| Command | Aliases | Description |
| --- | --- | --- |
| `/admin [player]` | `controlroom`, `staff` | Open the Control Room |
| `/punish <player> <action> [duration] <reason>` | | Warn, mute, kick, ban, tempban, freeze |
| `/warn`, `/mute`, `/kick`, `/ban` | | Shortcuts into the same service |
| `/unban <player>` | `pardon` | Lift a ban |
| `/vanish` | `v` | Toggle vanish |
| `/staffchat [message]` | `sc` | Staff-only chat |
| `/reports` | | Report queue GUI |
| `/notes <player> [text]` | | Read or write staff notes |
| `/audit [limit]` | | Recent staff actions |
| `/treasure hide <shards> <clue>` | | Bury a treasure cache |
| `/craftrealm <reload\|version\|panel\|save>` | `cr` | Maintenance |

Durations accept `30m`, `2h`, `7d`, `permanent`.

---

## 7. Permissions

| Node | Default | Meaning |
| --- | --- | --- |
| `craftrealm.use` | everyone | Parent of the player-facing nodes below |
| `craftrealm.shop`, `.quests`, `.claims`, `.cosmetics`, `.seasons`, `.events`, `.treasure`, `.economy.coins` | everyone | Individual player features |
| `craftrealm.admin` | op | Control Room + maintenance; parent of the staff nodes |
| `craftrealm.staff.punish` / `.warn` / `.unban` | op | Moderation |
| `craftrealm.staff.vanish` (+ `.see`) / `.chat` | op | Presence tools |
| `craftrealm.staff.reports` / `.notes` / `.audit` | op | Oversight |
| `craftrealm.tier.helper` / `.mod` / `.admin` / `.owner` | false | Staff tiers (weights from `config.yml`) |
| `craftrealm.claims.bypass` | op | Build anywhere |
| `craftrealm.events.admin` | op | Start/stop world events |
| `craftrealm.treasure.admin` | op | Hide treasures |
| `craftrealm.shop.limit-bypass` | op | Ignore chest-shop limits |

---

## 8. Configuration

`config.yml` is commented throughout; every key is optional and falls back to a
built-in default. The important groups:

| Section | What lives there |
| --- | --- |
| `storage` | Backend (`sqlite`/`mysql`), file, pool sizes, I/O threads, `server-id` |
| `economy` | Currency name/symbol/decimals, starting balance, transaction ceiling, `/pay` minimum, rate limit, alert threshold |
| `bank` | Interest rate, period, interest ceiling; loan limits, rate, term, penalty, default delay |
| `market` | Anchor prices, blacklist, GUI categories, elasticity, floor/ceiling, recovery, trend threshold, buy multiplier |
| `auction` | Listing fee (percent + minimum), max listings, price bounds, durations, bid increment, tax |
| `jobs` | Level cap, multiplier per level, XP curve, daily cap, per-job payout tables |
| `claims` | Price per block, daily upkeep, size and count limits, grace days |
| `chest-shops` | Tax, per-player limit, holograms, sign requirement |
| `web` | Enable, bind host/port, API tokens, session length, console access, ring-buffer size |
| `discord` | Webhook URL and which events to post |
| `theme` | Sounds, custom model data, colour palette |
| `staff` | Tier → permission mapping, warning escalation ladder |
| `admin` | Lag thresholds, report cooldown, freeze behaviour, ban default |
| `events` | Auto-start, interval, gap, duration, event pool and per-event multipliers |
| `quests` | Daily/weekly counts and template list |
| `seasons` | Length and reward tiers |
| `cosmetics` | Catalogue of purchasable items |
| `bounties`, `streaks`, `treasure` | Feature switches and tuning |
| `messages` | Locale, fallback locale, chat prefix |

A minimal, working example:

```yaml
config-version: 1
storage:
  type: sqlite
economy:
  currency:
    name: Crowns
    symbol: "⛃"
    starting-balance: 100.0
bank:
  interest:
    rate-percent: 1.5
    period-hours: 24
market:
  buy-multiplier: 1.25
  base-prices:
    COBBLESTONE: 0.5
    IRON_INGOT: 12.0
    DIAMOND: 120.0
messages:
  locale: en_US
  prefix: "<#6b4a2f>[<#e8b339>CraftRealm</#e8b339>]</#6b4a2f> "
```

### Messages

Language files live in `plugins/CraftRealm/lang/` and use **MiniMessage**. Colour
codes from the theme palette are written inline (`<#e8b339>`). Placeholders are
`{like_this}` and are substituted *after* MiniMessage parsing, and player names
are inserted as plain text — a name can never inject markup.

Delete a language file and restart to get the shipped copy back.

---

## 9. How the economy stays honest

* **One ledger, atomic writes.** Every balance change is a conditional
  `UPDATE … WHERE balance >= ?` inside a transaction plus an append-only
  `cr_transactions` row. There is no read-modify-write window.
* **Idempotency.** Ledger rows carry a unique `op_id`; retrying a settlement
  after a timeout returns the original result instead of paying twice.
* **Serial numbers.** Coins and notes carry a serial and an HMAC signature;
  redemptions insert the serial into `cr_banknotes` with a unique index, so a
  duplicated item is rejected the second time.
* **Bids and mail are escrowed.** A bid takes the money immediately and refunds
  the previous bidder; uncollected items and proceeds wait in the mailbox.
* **Rollback.** Staff can reverse a suspicious transaction from the alert queue;
  the reversal is itself a ledger entry (nothing is ever silently rewritten).
* **Alerts.** Transactions above `economy.alerts.large-transaction` show up in
  the Control Room and the dashboard.
* **No main-thread I/O.** Services return `CompletableFuture`s; the only
  synchronous path is the Vault façade, which waits with a two-second ceiling
  because Vault's contract is synchronous — and it says so in the logs if the
  database is slow.

---

## 10. The Control Room

`/admin` opens a 6-row chest GUI. Every category is one click deep and every
destructive action is a shift-click.

| Category | Contents |
| --- | --- |
| **Players** | Searchable list → per-player detail: wallet edit, teleport, freeze, mute, kick, ban, warnings, IP history, notes, inventory and ender-chest view |
| **Economy** | Total circulation, inflation trend, top earners, alert queue, market ledger, manual snapshot |
| **Punishments** | Open reports, warning ladder preview, quick actions |
| **World** | Per-world players/chunks/entities/time/weather, entity breakdown |
| **Health** | TPS (1/5/15 min), RAM with block-style bar, uptime, chunk hot-spots, one-click cleanup |
| **Logs** | The audit trail, newest first |

Everything staff do is written to `cr_audit` — who, what, on whom, why, when —
including web-panel logins and console commands.

---

## 11. The web panel

Disabled by default. It binds a Javalin server inside the plugin and serves three
static files plus a JSON API.

```yaml
web:
  enabled: true
  host: 0.0.0.0
  port: 8080
  tokens:
    - "paste-a-long-random-string-here"
  session-minutes: 720
  allow-console: true
  max-console-lines: 200
```

* **Token auth** — there is no password database. You paste tokens in config; the
  panel hands back a random session id and keeps only its hash, expiring it after
  `session-minutes`.
* **Bind address** — put it behind a reverse proxy with HTTPS, or set
  `host: 127.0.0.1`. Never expose port 8080 directly to the internet.
* **Console** — `allow-console` gates command execution; broadcasts and
  punishments are separate endpoints.

API surface (all JSON, `Authorization: Bearer <session>` after login):

```
POST /api/login              {"token":"…"}            → {"session":"…"}
GET  /api/status             server name, TPS, RAM, players
GET  /api/economy            circulation, accounts, inflation, history, top, alerts
GET  /api/players            online players with balances
GET  /api/reports            open reports
GET  /api/audit?limit=50     staff audit trail
GET  /api/market?limit=40    market prices and volume
GET  /api/console?limit=200  recent log lines
POST /api/console            {"command":"say hello"}
POST /api/broadcast          {"message":"Server restart in 5 minutes"}
POST /api/punish             {"target":"…","type":"MUTE","reason":"…","durationMinutes":60}
```

The UI follows the same theme as the in-game menus: deepslate background,
parchment cards, wooden-plank headers, hotbar-like buttons with a pressed
animation, pixel borders and block-style progress bars.

---

## 12. Developer API

`craftrealm-api` is the only module you should compile against.

```groovy
dependencies {
    compileOnly 'io.craftrealm:craftrealm-api:1.0.0'   // or the api jar from build/libs
}
```

### Using the services

```java
import io.craftrealm.api.CraftRealm;
import io.craftrealm.api.model.TransactionType;

CraftRealm.get().economy()
        .deposit(uuid, 250.0, TransactionType.QUEST_REWARD, "Welcome bonus")
        .thenAccept(result -> {
            if (!result.success()) {
                getLogger().warning("Deposit failed: " + result.reason());
            }
        });
```

Every call returns a `CompletableFuture` and never blocks the calling thread.
`CraftRealm.isAvailable()` tells you whether the plugin is loaded; the API object
also lives in Bukkit's `ServicesManager` for preference-free lookup.

### Listening for money moving

```java
import io.craftrealm.api.CraftRealm;
import io.craftrealm.api.event.BalanceChangeEvent;
import io.craftrealm.api.model.TransactionType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class TaxListener implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onBalanceChange(BalanceChangeEvent event) {
        if (event.type() != TransactionType.SHOP_BUY) {
            return;
        }
        // A 2% guild tax on purchases, paid by the buyer.
        double tax = event.delta() * 0.02;
        if (tax > 0.0) {
            CraftRealm.get().economy()
                    .withdraw(event.player(), tax, TransactionType.TAX, "Guild tax");
        }
    }
}
```

`BalanceChangeEvent` is `Cancellable`: cancelling it aborts the write before it
reaches the database.

Available events: `BalanceChangeEvent`, `ShardChangeEvent`,
`TransactionCompletedEvent`, `MarketPriceChangeEvent`, `AuctionBidEvent`,
`ClaimCreateEvent`, `JobLevelUpEvent`, `QuestCompletedEvent`,
`ServerEventLifecycleEvent`, `SeasonEndEvent`, `CraftRealmReloadEvent`.

Available services: `economy()`, `shards()`, `bank()`, `market()`, `auctions()`,
`jobs()`, `claims()`, `quests()`, `seasons()`, `cosmetics()`, `events()`,
`admin()`.

> Only `CraftRealm.get()` (and the services it returns) is a stable contract.
> Anything in `io.craftrealm.core`, `io.craftrealm.economy`, `io.craftrealm.admin`
> or `io.craftrealm.events` may change between minor releases.

---

## 13. Project layout

```
craftrealm-api       contracts, immutable records, Bukkit events     (no logic)
craftrealm-core      database, domain logic, i18n, GUI toolkit
craftrealm-economy   Vault bridge, /bal … /auction, jobs, market, notes
craftrealm-admin     Control Room, punishments, reports, health, audit
craftrealm-events    world events, claims, chest shops, quests, seasons, cosmetics
craftrealm-web       embedded Javalin dashboard (zero Bukkit imports)
craftrealm-plugin    wiring, plugin.yml, config.yml, lang/, → CraftRealm.jar
docs/ARCHITECTURE.md the detailed design document
resourcepack/        optional resource pack (generated 16x16 art + models)
```

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the package-by-package
map, the startup order and the reasoning behind the design, and
[`resourcepack/README.md`](resourcepack/README.md) for the optional art pack
(regeneration, model-data indices, `pack_format` per version).

---

## 14. Testing

The domain logic — money rounding and parsing, price elasticity and drift,
interest maths, loan schedules, auction fees, job level curves, claim decay,
message formatting, pagination and SQL generation for both dialects — is pure Java
with no Bukkit imports, so the tests run without a server:

```bash
./gradlew test
```

Reports land in `craftrealm-core/build/reports/tests/test/index.html`.

For a manual smoke test on a dev server:

```
/shop → buy 8 cobblestone → sell them back (price should dip, then recover)
/pay <alt> 10 → /bal <alt>
/coins note 500 → drop it, pick it up, redeem it → redeem the copy (must fail)
/bank → deposit → /craftrealm save → restart → balance intact
/claim create 4 → /claim list → /claim info
/treasure hide 5 test clue → /treasure hint
/craftrealm reload
```

---

## 15. Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| `Could not open the database` | `storage.type: mysql` with wrong credentials, or the SQLite file is not writable. Check `plugins/CraftRealm/`, then restart. |
| Everything costs the same | `market.elasticity` or `market.reference-volume` is too large/small for your economy; `min-factor`/`max-factor` may be clamping prices. |
| `/bal` shows an old balance | The cache is refreshed on write and on join. If a *second* server writes, that server does not fire local events — use MySQL so both see the same ledger. |
| A banknote fails to redeem | `data/note.key` changed or the item was copied after redemption. Both are intentional. |
| Web panel refuses to start | `web.tokens` is empty (the log says so), the port is taken, or `host` is not an interface on the machine. |
| GUI icons all look like paper | That is the fallback when `theme.custom-model-data.enabled: true` but no resource pack is installed. |
| Messages show `{amount}` literally | The language file is missing that key or the placeholder name differs; compare with `lang/en_US.yml`. |
| TPS tanks after a restart | Check Control Room → Health → lag sources; the market warm-up and schema migration are the only startup I/O and both are off-tick. |

---

## Credits

CraftRealm is original code written for Paper 1.21.x. It uses the Paper API,
Adventure/MiniMessage, HikariCP, sqlite-jdbc, MySQL Connector/J and Javalin —
all of which are shaded and relocated, except the Paper API itself.

CraftRealm is released under the [MIT licence](LICENSE).
