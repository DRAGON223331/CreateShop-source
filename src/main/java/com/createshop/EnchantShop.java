package com.createshop;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** متجر كتب التطوير (ExcellentEnchants) - الأمر /enchantshop. الأسعار من enchant-books.yml. */
public final class EnchantShop implements Listener, CommandExecutor {

    private static final int PER_PAGE = 45;

    private record Entry(String id, String name, String category, int level, double price, Enchantment ench) {}

    private record Pending(String id, long expires) {}

    private static final class Holder implements InventoryHolder {
        final int page;
        final Map<Integer, Entry> slots = new HashMap<>();
        Inventory inv;

        Holder(int page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final CreateShopPlugin plugin;
    private final List<Entry> entries = new ArrayList<>();
    private final Map<UUID, Pending> pendings = new HashMap<>();

    public EnchantShop(CreateShopPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    @SuppressWarnings("deprecation")
    public void load() {
        entries.clear();
        File f = new File(plugin.getDataFolder(), "enchant-books.yml");
        if (!f.exists()) plugin.saveResource("enchant-books.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection root = y.getConfigurationSection("books");
        if (root == null) return;
        String ns = plugin.getConfig().getString("enchant-shop.namespace", "excellentenchants");
        int missing = 0;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Enchantment e = Enchantment.getByKey(new NamespacedKey(ns, id));
            if (e == null) {
                missing++;
                plugin.getLogger().warning("EnchantShop: التطوير غير موجود (تخطيته): " + ns + ":" + id);
                continue;
            }
            entries.add(new Entry(id, s.getString("name", id), s.getString("category", ""),
                    Math.max(1, s.getInt("level", 1)), s.getDouble("price", 1000), e));
        }
        plugin.getLogger().info("EnchantShop: " + entries.size() + " كتاب جاهز" + (missing > 0 ? " (" + missing + " ناقص)" : ""));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(m().msg("players-only"));
            return true;
        }
        if (!p.hasPermission("createshop.enchantshop")) {
            p.sendMessage(m().msg("no-permission"));
            return true;
        }
        if (entries.isEmpty()) {
            p.sendMessage(m().msg("es-empty"));
            return true;
        }
        int page = 0;
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]) - 1;
            } catch (NumberFormatException ignored) {
            }
        }
        open(p, page);
        return true;
    }

    private void open(Player p, int page) {
        int pages = Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        Holder h = new Holder(page);
        Inventory inv = Bukkit.createInventory(h, 54, Messages.color(m().gui("gui.es-title", "page", page + 1, "pages", pages)));
        h.inv = inv;
        int from = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && from + i < entries.size(); i++) {
            Entry e = entries.get(from + i);
            ItemStack it = book(e);
            ItemMeta meta = it.getItemMeta();
            List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(m().gui("gui.es-lore-price", "price", plugin.money(e.price())));
            lore.add(m().gui("gui.es-lore-category", "category", e.category()));
            lore.add(m().gui("gui.es-lore-buy"));
            meta.setLore(lore);
            it.setItemMeta(meta);
            inv.setItem(i, it);
            h.slots.put(i, e);
        }
        ItemStack pane = named(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, pane);
        if (page > 0) inv.setItem(45, named(Material.ARROW, m().gui("gui.prev-name")));
        inv.setItem(49, named(Material.BARRIER, m().gui("gui.close-name")));
        if (page < pages - 1) inv.setItem(53, named(Material.ARROW, m().gui("gui.next-name")));
        p.openInventory(inv);
    }

    private static ItemStack named(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            it.setItemMeta(meta);
        }
        return it;
    }

    private ItemStack book(Entry e) {
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        if (it.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            meta.addStoredEnchant(e.ench(), e.level(), true);
            it.setItemMeta(meta);
        }
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent ev) {
        Inventory top = ev.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder h)) return;
        ev.setCancelled(true);
        if (!(ev.getWhoClicked() instanceof Player p)) return;
        if (ev.getClickedInventory() != top) return;
        int slot = ev.getRawSlot();
        if (slot == 49) {
            Bukkit.getScheduler().runTask(plugin, p::closeInventory);
        } else if (slot == 45 && h.page > 0) {
            Bukkit.getScheduler().runTask(plugin, () -> open(p, h.page - 1));
        } else if (slot == 53) {
            Bukkit.getScheduler().runTask(plugin, () -> open(p, h.page + 1));
        } else {
            Entry e = h.slots.get(slot);
            if (e != null) click(p, e);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent ev) {
        if (ev.getView().getTopInventory().getHolder() instanceof Holder) ev.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent ev) {
        pendings.remove(ev.getPlayer().getUniqueId());
    }

    private void click(Player p, Entry e) {
        long now = System.currentTimeMillis();
        if (plugin.getConfig().getBoolean("confirm-purchase", true)) {
            Pending pd = pendings.get(p.getUniqueId());
            if (pd == null || pd.expires() < now || !pd.id().equals(e.id())) {
                long ms = plugin.getConfig().getLong("confirm-timeout-seconds", 8) * 1000L;
                pendings.put(p.getUniqueId(), new Pending(e.id(), now + ms));
                p.sendMessage(m().msg("es-confirm", "book", e.name() + " " + e.level(), "price", plugin.money(e.price())));
                return;
            }
            pendings.remove(p.getUniqueId());
        }
        buy(p, e);
    }

    private void buy(Player p, Entry e) {
        Economy eco = plugin.economy();
        if (eco == null) {
            p.sendMessage(m().msg("economy-missing"));
            return;
        }
        double price = plugin.round2(e.price());
        if (!eco.has(p, price)) {
            p.sendMessage(m().msg("not-enough", "total", plugin.money(price)));
            return;
        }
        ItemStack give = book(e);
        if (!ShopGui.canFit(p.getInventory(), give, 1)) {
            p.sendMessage(m().msg("inv-full-buy"));
            return;
        }
        EconomyResponse r = eco.withdrawPlayer(p, price);
        if (!r.transactionSuccess()) {
            p.sendMessage(m().msg("buy-failed", "reason", String.valueOf(r.errorMessage)));
            return;
        }
        p.getInventory().addItem(give);
        p.sendMessage(m().msg("es-bought", "book", e.name() + " " + e.level(), "price", plugin.money(price)));
        if (plugin.getConfig().getBoolean("log-sales", true)) {
            plugin.getLogger().info(p.getName() + " bought book " + e.id() + ":" + e.level() + " for " + price);
        }
    }
}
