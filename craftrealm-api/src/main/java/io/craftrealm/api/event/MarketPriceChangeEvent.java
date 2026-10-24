package io.craftrealm.api.event;

import org.bukkit.Material;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/** Fired when supply and demand moved a market price. */
public class MarketPriceChangeEvent extends CraftRealmEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Material material;
    private final double oldPrice;
    private final double newPrice;

    public MarketPriceChangeEvent(@NotNull Material material, double oldPrice, double newPrice) {
        this.material = material;
        this.oldPrice = oldPrice;
        this.newPrice = newPrice;
    }

    public @NotNull Material material() {
        return material;
    }

    public double oldPrice() {
        return oldPrice;
    }

    public double newPrice() {
        return newPrice;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
