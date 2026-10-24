package io.craftrealm.admin;

import io.craftrealm.api.service.EconomyService;
import io.craftrealm.api.service.MarketService;
import io.craftrealm.api.service.ShardService;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.db.Database;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.ui.GuiTheme;
import org.bukkit.plugin.Plugin;

/**
 * Dependencies for the admin module, injected once at enable.
 */
public final class AdminContext {

    public final Plugin plugin;
    public final Settings settings;
    public final Messages messages;
    public final GuiTheme theme;
    public final EconomyService economy;
    public final ShardService shards;
    public final MarketService market;
    public final Database database;

    public AdminContext(Plugin plugin, Settings settings, Messages messages, GuiTheme theme,
                        EconomyService economy, ShardService shards, MarketService market,
                        Database database) {
        this.plugin = plugin;
        this.settings = settings;
        this.messages = messages;
        this.theme = theme;
        this.economy = economy;
        this.shards = shards;
        this.market = market;
        this.database = database;
    }

    public String money(double amount) {
        return economy.format(amount);
    }
}
