package com.createshop;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Material;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class CreateShopPlugin extends JavaPlugin {

    public static final int MAX_SLOTS = 45;

    private Economy economy;
    private Messages messages;
    private PriceService prices;
    private ShopManager shops;
    private ShopGui gui;
    private final Set<Material> blacklist = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        messages.load();
        loadBlacklist();

        prices = new PriceService(this);
        prices.reload();

        shops = new ShopManager(this);
        shops.load();

        gui = new ShopGui(this);
        getServer().getPluginManager().registerEvents(gui, this);

        ShopCommand cmd = new ShopCommand(this);
        PluginCommand pc = getCommand("createshop");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }

        if (economy() == null) {
            getLogger().warning("لم يتم العثور على مزود اقتصاد عبر Vault بعد. سيتم إعادة المحاولة عند أول استخدام.");
        }
        getLogger().info("CreateShop جاهز. محلات محمّلة: " + shops.all().size());
    }

    public void reloadAll() {
        reloadConfig();
        messages.load();
        loadBlacklist();
        prices.reload();
    }

    private void loadBlacklist() {
        blacklist.clear();
        for (String s : getConfig().getStringList("blacklist")) {
            Material m = Material.matchMaterial(s);
            if (m != null) blacklist.add(m);
        }
    }

    public boolean isBlacklisted(Material m) {
        return blacklist.contains(m);
    }

    /** يجيب الاقتصاد من Vault (ويعيد المحاولة لو اتسجل متأخر). */
    public Economy economy() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) economy = rsp.getProvider();
        }
        return economy;
    }

    public Messages messages() { return messages; }
    public PriceService prices() { return prices; }
    public ShopManager shops() { return shops; }
    public ShopGui gui() { return gui; }

    /** أقصى عدد أغراض للاعب (من الإعدادات + صلاحية createshop.limit.N). */
    public int maxListings(Player p) {
        int max = getConfig().getInt("max-listings", 27);
        for (PermissionAttachmentInfo pai : p.getEffectivePermissions()) {
            String perm = pai.getPermission().toLowerCase(Locale.ROOT);
            if (pai.getValue() && perm.startsWith("createshop.limit.")) {
                try {
                    max = Math.max(max, Integer.parseInt(perm.substring("createshop.limit.".length())));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return Math.max(1, Math.min(MAX_SLOTS, max));
    }

    public double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public String money(double v) {
        String pattern = getConfig().getString("currency-format", "#,##0.00");
        String symbol = getConfig().getString("currency-symbol", "$");
        DecimalFormat df;
        try {
            df = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US));
        } catch (IllegalArgumentException ex) {
            df = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        }
        return symbol + df.format(v);
    }
}
