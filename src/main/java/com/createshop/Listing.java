package com.createshop;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** غرض معروض في محل لاعب. السعر للقطعة الواحدة. */
public final class Listing {
    public final UUID id;
    public final ItemStack item; // النسخة الأصلية (بالكمية)
    public double unitPrice;
    public boolean forSale;

    public Listing(UUID id, ItemStack item, double unitPrice, boolean forSale) {
        this.id = id;
        this.item = item;
        this.unitPrice = unitPrice;
        this.forSale = forSale;
    }
}
