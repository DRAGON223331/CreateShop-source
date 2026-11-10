package com.createshop;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * الربط مع البلاجنز التانية بقراءة ملفاتها:
 *  - QuickShop-Hikari/price-restriction.yml  -> حدود السعر (min/max) لكل خامة
 *  - EconomyShopGUI/shops/*.yml              -> أسعار السيرفر المرجعية (buy/sell)
 */
public final class PriceService {

    public record Range(double min, double max) {}

    public record Ref(double buy, double sell) {}

    private static final Set<Material> SKIP_REF = EnumSet.of(
            Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION,
            Material.TIPPED_ARROW, Material.ENCHANTED_BOOK, Material.SPAWNER);

    private final CreateShopPlugin plugin;
    private final Map<Material, Range> ranges = new EnumMap<>(Material.class);
    private final Map<Material, Ref> refs = new EnumMap<>(Material.class);
    private Range undefined = null;
    private boolean qsActive = false;

    public PriceService(CreateShopPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        ranges.clear();
        refs.clear();
        undefined = null;
        qsActive = false;
        File plugins = plugin.getDataFolder().getParentFile();

        if (plugin.getConfig().getBoolean("integrations.quickshop.enabled", true)) {
            loadQuickShop(new File(plugins, plugin.getConfig().getString("integrations.quickshop.folder", "QuickShop-Hikari")));
        }
        if (plugin.getConfig().getBoolean("integrations.economyshopgui.enabled", true)) {
            loadEconomyShopGui(new File(plugins, plugin.getConfig().getString("integrations.economyshopgui.folder", "EconomyShopGUI")));
        }
        plugin.getLogger().info("QuickShop limits: " + (qsActive ? ranges.size() + " خامة" : "غير مفعّل")
                + " | EconomyShopGUI prices: " + refs.size() + " خامة");
    }

    private void loadQuickShop(File folder) {
        File f = new File(folder, "price-restriction.yml");
        if (!f.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        if (!y.getBoolean("enable", true)) return;
        qsActive = true;

        ConfigurationSection und = y.getConfigurationSection("undefined");
        if (und != null) {
            undefined = new Range(und.getDouble("min", 0), und.getDouble("max", Double.MAX_VALUE));
        }
        ConfigurationSection rules = y.getConfigurationSection("rules");
        if (rules == null) return;
        for (String key : rules.getKeys(false)) {
            ConfigurationSection r = rules.getConfigurationSection(key);
            if (r == null) continue;
            Range range = new Range(r.getDouble("min", 0), r.getDouble("max", Double.MAX_VALUE));
            for (String name : r.getStringList("items")) {
                Material m = Material.matchMaterial(name);
                if (m != null) ranges.put(m, range);
            }
        }
    }

    private void loadEconomyShopGui(File folder) {
        File shopsDir = new File(folder, "shops");
        File[] files = shopsDir.listFiles((d, n) -> n.toLowerCase().endsWith(".yml"));
        if (files == null) return;
        for (File f : files) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            ConfigurationSection pages = y.getConfigurationSection("pages");
            if (pages == null) continue;
            for (String pg : pages.getKeys(false)) {
                ConfigurationSection items = pages.getConfigurationSection(pg + ".items");
                if (items == null) continue;
                for (String k : items.getKeys(false)) {
                    ConfigurationSection it = items.getConfigurationSection(k);
                    if (it == null) continue;
                    String mat = it.getString("material");
                    if (mat == null) continue;
                    Material m = Material.matchMaterial(mat);
                    if (m == null || SKIP_REF.contains(m)) continue;
                    refs.putIfAbsent(m, new Ref(it.getDouble("buy", -1), it.getDouble("sell", -1)));
                }
            }
        }
    }

    /** الحد المسموح للبيع، أو null لو مفيش قيود. */
    public Range range(Material m) {
        if (!qsActive) return null;
        Range r = ranges.get(m);
        return r != null ? r : undefined;
    }

    /** null = مسموح. غير كده بيرجع الحد اللي السعر خالفه. */
    public Range violation(Material m, double unitPrice) {
        Range r = range(m);
        if (r == null) return null;
        return (unitPrice < r.min() || unitPrice > r.max()) ? r : null;
    }

    public Ref reference(Material m) {
        return plugin.getConfig().getBoolean("integrations.economyshopgui.show-server-prices", true) ? refs.get(m) : null;
    }
}
