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

/** متجر كتب التطوير (ExcellentEnchants) - الأمر /enchantshop. الأسعار من enchant-books.yml. */
public final class EnchantShop implements Listener, CommandExecutor {

    private static final int PER_PAGE = 45;

    private record Entry(String id, String name, String category, int level, double price, Enchantment ench) {}

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

    /** شاشة الشراء (زي EconomyShopGUI): اختيار الكمية ثم تأكيد. */
    private static final class BuyHolder implements InventoryHolder {
        final Entry entry;
        final int backPage;
        int amount = 1;
        Inventory inv;

        BuyHolder(Entry entry, int backPage) {
            this.entry = entry;
            this.backPage = backPage;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final int S_CONFIRM = 13, S_REM32 = 19, S_REM16 = 20, S_REM1 = 21, S_SEL = 22,
            S_ADD1 = 23, S_ADD16 = 24, S_ADD32 = 25, S_BACK = 36, S_HEAD = 40;

    private final CreateShopPlugin plugin;
    private final List<Entry> entries = new ArrayList<>();

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
        return named(mat, name, 1);
    }

    private ItemStack book(Entry e) {
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        if (it.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            meta.addStoredEnchant(e.ench(), e.level(), true);
            it.setItemMeta(meta);
        }
        return it;
    }

    private int maxAmount() {
        return Math.max(1, Math.min(64, plugin.getConfig().getInt("enchant-shop.max-amount", 64)));
    }

    private void openBuy(Player p, Entry e, int backPage) {
        BuyHolder h = new BuyHolder(e, backPage);
        Inventory inv = Bukkit.createInventory(h, 45, Messages.color(m().gui("gui.es-buy-title", "book", e.name() + " " + e.level())));
        h.inv = inv;
        render(p, h);
        p.openInventory(inv);
    }

    private void render(Player p, BuyHolder h) {
        Inventory inv = h.inv;
        int max = maxAmount();
        double total = plugin.round2(h.entry.price() * h.amount);

        inv.setItem(S_REM32, named(Material.MAGENTA_STAINED_GLASS_PANE, m().gui("gui.es-minus", "n", 32), 32));
        inv.setItem(S_REM16, named(Material.MAGENTA_STAINED_GLASS_PANE, m().gui("gui.es-minus", "n", 16), 16));
        inv.setItem(S_REM1, named(Material.MAGENTA_STAINED_GLASS_PANE, m().gui("gui.es-minus", "n", 1), 1));
        inv.setItem(S_ADD1, named(Material.LIGHT_BLUE_STAINED_GLASS_PANE, m().gui("gui.es-plus", "n", 1), 1));
        inv.setItem(S_ADD16, named(Material.LIGHT_BLUE_STAINED_GLASS_PANE, m().gui("gui.es-plus", "n", 16), 16));
        inv.setItem(S_ADD32, named(Material.LIGHT_BLUE_STAINED_GLASS_PANE, m().gui("gui.es-plus", "n", 32), 32));

        ItemStack confirm = named(Material.PAPER, m().gui("gui.es-confirm-name"), 1);
        ItemMeta cm = confirm.getItemMeta();
        List<String> cl = new ArrayList<>();
        cl.add(m().gui("gui.es-total", "price", plugin.money(total)));
        cm.setLore(cl);
        confirm.setItemMeta(cm);
        inv.setItem(S_CONFIRM, confirm);

        ItemStack sel = book(h.entry);
        sel.setAmount(h.amount);
        ItemMeta sm = sel.getItemMeta();
        List<String> sl = sm.hasLore() && sm.getLore() != null ? new ArrayList<>(sm.getLore()) : new ArrayList<>();
        sl.add(m().gui("gui.es-amount", "amount", h.amount));
        if (h.amount >= max) sl.add(m().gui("gui.es-max"));
        else if (h.amount <= 1) sl.add(m().gui("gui.es-min"));
        sm.setLore(sl);
        sel.setItemMeta(sm);
        inv.setItem(S_SEL, sel);

        inv.setItem(S_BACK, named(Material.ARROW, m().gui("gui.es-back"), 1));
        Economy eco = plugin.economy();
        ItemStack head = named(Material.PLAYER_HEAD, "&f" + p.getName(), 1);
        ItemMeta hm = head.getItemMeta();
        hm.setLore(List.of(m().gui("gui.es-balance", "balance", eco == null ? "?" : plugin.money(eco.getBalance(p)))));
        head.setItemMeta(hm);
        inv.setItem(S_HEAD, head);
    }

    private static ItemStack named(Material mat, String name, int amount) {
        ItemStack it = new ItemStack(mat, amount);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Messages.color(name));
            it.setItemMeta(meta);
        }
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent ev) {
        Inventory top = ev.getView().getTopInventory();
        if (top.getHolder() instanceof BuyHolder bh) {
            ev.setCancelled(true);
            if (!(ev.getWhoClicked() instanceof Player p)) return;
            if (ev.getClickedInventory() != top) return;
            int slot = ev.getRawSlot();
            int max = maxAmount();
            switch (slot) {
                case S_REM32 -> bh.amount = Math.max(1, bh.amount - 32);
                case S_REM16 -> bh.amount = Math.max(1, bh.amount - 16);
                case S_REM1 -> bh.amount = Math.max(1, bh.amount - 1);
                case S_ADD1 -> bh.amount = Math.min(max, bh.amount + 1);
                case S_ADD16 -> bh.amount = Math.min(max, bh.amount + 16);
                case S_ADD32 -> bh.amount = Math.min(max, bh.amount + 32);
                case S_BACK -> {
                    Bukkit.getScheduler().runTask(plugin, () -> open(p, bh.backPage));
                    return;
                }
                case S_CONFIRM -> {
                    Bukkit.getScheduler().runTask(plugin, p::closeInventory);
                    buy(p, bh.entry, bh.amount);
                    return;
                }
                default -> {
                    return;
                }
            }
            render(p, bh);
            return;
        }
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
            if (e != null) Bukkit.getScheduler().runTask(plugin, () -> openBuy(p, e, h.page));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent ev) {
        InventoryHolder ih = ev.getView().getTopInventory().getHolder();
        if (ih instanceof Holder || ih instanceof BuyHolder) ev.setCancelled(true);
    }

    private void buy(Player p, Entry e, int amount) {
        Economy eco = plugin.economy();
        if (eco == null) {
            p.sendMessage(m().msg("economy-missing"));
            return;
        }
        amount = Math.max(1, Math.min(maxAmount(), amount));
        double total = plugin.round2(e.price() * amount);
        if (!plugin.canAfford(p, total)) {
            p.sendMessage(m().msg("not-enough", "total", plugin.money(total)));
            return;
        }
        ItemStack one = book(e);
        if (!ShopGui.canFit(p.getInventory(), one, amount)) {
            p.sendMessage(m().msg("inv-full-buy"));
            return;
        }
        EconomyResponse r = plugin.charge(p, total);
        if (!r.transactionSuccess()) {
            p.sendMessage(m().msg("buy-failed", "reason", String.valueOf(r.errorMessage)));
            return;
        }
        for (int i = 0; i < amount; i++) p.getInventory().addItem(book(e));
        p.sendMessage(m().msg("es-bought", "book", e.name() + " " + e.level(), "amount", amount, "price", plugin.money(total)));
        if (plugin.getConfig().getBoolean("log-sales", true)) {
            plugin.getLogger().info(p.getName() + " bought " + amount + " x book " + e.id() + ":" + e.level() + " for " + total + " with the buy screen.");
        }
    }
}
