package com.createshop;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** تخزين المحلات في plugins/CreateShop/data.yml. الحفظ فوري عشان مفيش نسخ مكررة للأغراض. */
public final class ShopManager {

    private final CreateShopPlugin plugin;
    private final File file;
    private final Map<UUID, PlayerShop> shops = new LinkedHashMap<>();

    public ShopManager(CreateShopPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
    }

    public void load() {
        shops.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("shops");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            UUID owner;
            try {
                owner = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            PlayerShop shop = new PlayerShop(owner, s.getString("name", "?"));
            shop.title = s.getString("title", "");
            ConfigurationSection ls = s.getConfigurationSection("listings");
            if (ls != null) {
                for (String lid : ls.getKeys(false)) {
                    ConfigurationSection l = ls.getConfigurationSection(lid);
                    if (l == null) continue;
                    ItemStack item = l.getItemStack("item");
                    if (item == null || item.getType().isAir()) continue;
                    try {
                        shop.listings.put(UUID.fromString(lid),
                                new Listing(UUID.fromString(lid), item, l.getDouble("price"), l.getBoolean("sale")));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            shops.put(owner, shop);
        }
    }

    public synchronized void saveNow() {
        YamlConfiguration y = new YamlConfiguration();
        for (PlayerShop shop : shops.values()) {
            String base = "shops." + shop.owner;
            y.set(base + ".name", shop.ownerName);
            y.set(base + ".title", shop.title);
            for (Listing l : shop.listings.values()) {
                String p = base + ".listings." + l.id;
                y.set(p + ".item", l.item);
                y.set(p + ".price", l.unitPrice);
                y.set(p + ".sale", l.forSale);
            }
        }
        try {
            plugin.getDataFolder().mkdirs();
            File tmp = new File(plugin.getDataFolder(), "data.yml.tmp");
            y.save(tmp);
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFail) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.SEVERE, "فشل حفظ data.yml", ex);
        }
    }

    public PlayerShop getOrCreate(Player p) {
        PlayerShop s = shops.computeIfAbsent(p.getUniqueId(), id -> new PlayerShop(id, p.getName()));
        s.ownerName = p.getName();
        return s;
    }

    public PlayerShop get(UUID owner) {
        return shops.get(owner);
    }

    public PlayerShop findByName(String name) {
        for (PlayerShop s : shops.values()) {
            if (s.ownerName.equalsIgnoreCase(name)) return s;
        }
        String low = name.toLowerCase(Locale.ROOT);
        for (PlayerShop s : shops.values()) {
            if (s.title != null && s.title.toLowerCase(Locale.ROOT).equals(low)) return s;
        }
        return null;
    }

    public Collection<PlayerShop> all() {
        return new ArrayList<>(shops.values());
    }
}
