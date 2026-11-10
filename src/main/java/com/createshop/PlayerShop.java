package com.createshop;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerShop {
    public final UUID owner;
    public String ownerName;
    public String title;
    public final Map<UUID, Listing> listings = new LinkedHashMap<>();

    public PlayerShop(UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.title = "";
    }

    public String displayName() {
        return (title == null || title.isEmpty()) ? ownerName : title;
    }

    public int forSaleCount() {
        int n = 0;
        for (Listing l : listings.values()) if (l.forSale) n++;
        return n;
    }
}
