package com.createshop;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ShopGui implements Listener {

    private static final int SLOT_INFO = 45;
    private static final int SLOT_MIDDLE = 49;
    private static final int SLOT_CLOSE = 53;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_NEXT = 53;

    enum Kind { MANAGE, VIEW, BROWSE }

    enum PromptType { NEW_LISTING, CHANGE_PRICE, RENAME }

    record Prompt(PromptType type, int invSlot, ItemStack snapshot, UUID listingId, long expires) {}

    record Pending(UUID owner, UUID listingId, int qty, double unitPrice, long expires) {}

    static final class Holder implements InventoryHolder {
        final Kind kind;
        final UUID owner;
        final int page;
        final Map<Integer, UUID> slots = new HashMap<>();
        private Inventory inventory;

        Holder(Kind kind, UUID owner, int page) {
            this.kind = kind;
            this.owner = owner;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final CreateShopPlugin plugin;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pendings = new HashMap<>();

    public ShopGui(CreateShopPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    // ───────────────────────── فتح الواجهات ─────────────────────────

    public void openManage(Player p) {
        PlayerShop shop = plugin.shops().getOrCreate(p);
        Holder h = new Holder(Kind.MANAGE, p.getUniqueId(), 0);
        Inventory inv = Bukkit.createInventory(h, 54, title(m().gui("gui.title-manage", "name", shop.displayName())));
        h.inventory = inv;
        fillListings(inv, h, shop);
        filler(inv);
        inv.setItem(SLOT_INFO, named(Material.BOOK, m().gui("gui.info-name"), m().list("gui.info-lore")));
        inv.setItem(SLOT_MIDDLE, named(Material.NAME_TAG, m().gui("gui.rename-name"),
                m().list("gui.rename-lore", "name", shop.displayName())));
        inv.setItem(SLOT_CLOSE, named(Material.BARRIER, m().gui("gui.close-name"), null));
        p.openInventory(inv);
    }

    public void openView(Player viewer, PlayerShop shop) {
        if (viewer.getUniqueId().equals(shop.owner)) {
            openManage(viewer);
            return;
        }
        Holder h = new Holder(Kind.VIEW, shop.owner, 0);
        Inventory inv = Bukkit.createInventory(h, 54, title(m().gui("gui.title-view", "name", shop.displayName())));
        h.inventory = inv;
        fillListings(inv, h, shop);
        filler(inv);
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta sm) {
            sm.setOwningPlayer(Bukkit.getOfflinePlayer(shop.owner));
            sm.setDisplayName(m().gui("gui.owner-name", "name", shop.displayName()));
            sm.setLore(m().list("gui.owner-lore", "owner", shop.ownerName, "count", shop.listings.size()));
            head.setItemMeta(sm);
        }
        inv.setItem(SLOT_MIDDLE, head);
        inv.setItem(SLOT_CLOSE, named(Material.BARRIER, m().gui("gui.close-name"), null));
        if (shop.listings.isEmpty()) {
            inv.setItem(22, named(Material.STRUCTURE_VOID, m().gui("gui.empty-name"), m().list("gui.empty-lore")));
        }
        viewer.openInventory(inv);
    }

    public void openBrowse(Player p, int page) {
        List<PlayerShop> list = new ArrayList<>();
        for (PlayerShop s : plugin.shops().all()) if (!s.listings.isEmpty()) list.add(s);
        if (list.isEmpty()) {
            p.sendMessage(m().msg("no-shops"));
            return;
        }
        list.sort(Comparator.comparingInt((PlayerShop s) -> s.listings.size()).reversed());
        int pages = Math.max(1, (list.size() + CreateShopPlugin.MAX_SLOTS - 1) / CreateShopPlugin.MAX_SLOTS);
        page = Math.max(0, Math.min(page, pages - 1));

        Holder h = new Holder(Kind.BROWSE, null, page);
        Inventory inv = Bukkit.createInventory(h, 54, title(m().gui("gui.title-browse", "page", page + 1)));
        h.inventory = inv;
        int from = page * CreateShopPlugin.MAX_SLOTS;
        for (int i = 0; i < CreateShopPlugin.MAX_SLOTS && from + i < list.size(); i++) {
            PlayerShop s = list.get(from + i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta sm) {
                sm.setOwningPlayer(Bukkit.getOfflinePlayer(s.owner));
                sm.setDisplayName(m().gui("gui.browse-shop-name", "name", s.displayName()));
                sm.setLore(m().list("gui.browse-shop-lore", "owner", s.ownerName,
                        "count", s.listings.size(), "sale", s.forSaleCount()));
                head.setItemMeta(sm);
            }
            inv.setItem(i, head);
            h.slots.put(i, s.owner);
        }
        filler(inv);
        if (page > 0) inv.setItem(SLOT_PREV, named(Material.ARROW, m().gui("gui.prev-name"), null));
        if (page < pages - 1) inv.setItem(SLOT_NEXT, named(Material.ARROW, m().gui("gui.next-name"), null));
        inv.setItem(SLOT_MIDDLE, named(Material.BARRIER, m().gui("gui.close-name"), null));
        p.openInventory(inv);
    }

    private void fillListings(Inventory inv, Holder h, PlayerShop shop) {
        for (int i = 0; i < CreateShopPlugin.MAX_SLOTS; i++) inv.setItem(i, null);
        h.slots.clear();
        int i = 0;
        for (Listing l : shop.listings.values()) {
            if (i >= CreateShopPlugin.MAX_SLOTS) break;
            inv.setItem(i, display(l, h.kind == Kind.MANAGE));
            h.slots.put(i, l.id);
            i++;
        }
    }

    private void filler(Inventory inv) {
        ItemStack pane = named(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 45; i < 54; i++) inv.setItem(i, pane);
    }

    private static ItemStack named(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) meta.setLore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    private static String title(String s) {
        return s.length() > 32 ? s.substring(0, 32) : s;
    }

    private ItemStack display(Listing l, boolean manage) {
        ItemStack it = l.item.clone();
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        int amount = l.item.getAmount();
        lore.add("");
        lore.add(m().gui("lore.price", "price", plugin.money(l.unitPrice)));
        if (amount > 1) {
            lore.add(m().gui("lore.total", "amount", amount, "total", plugin.money(plugin.round2(l.unitPrice * amount))));
        }
        lore.add(m().gui(l.forSale ? "lore.for-sale" : "lore.display-only"));

        PriceService.Ref ref = plugin.prices().reference(l.item.getType());
        if (ref != null) {
            lore.add(m().gui("lore.server-price",
                    "buy", ref.buy() > 0 ? plugin.money(ref.buy()) : "-",
                    "sell", ref.sell() > 0 ? plugin.money(ref.sell()) : "-"));
        }
        if (manage) {
            PriceService.Range r = plugin.prices().range(l.item.getType());
            if (r != null) {
                lore.add(m().gui("lore.limits", "min", plugin.money(r.min()), "max", plugin.money(r.max())));
            }
            lore.addAll(m().list("lore.manage-hints"));
        } else {
            lore.addAll(m().list(l.forSale ? "lore.view-hints" : "lore.view-hints-display"));
        }
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private static String itemName(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta != null && meta.hasDisplayName()) return meta.getDisplayName();
        String n = it.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String w : n.split(" ")) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    // ───────────────────────── الكليكات ─────────────────────────

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null) return;

        boolean inTop = e.getClickedInventory() == top;
        if (!inTop) {
            if (h.kind == Kind.MANAGE) beginNewListing(p, e.getSlot(), e.getCurrentItem());
            return;
        }

        int slot = e.getRawSlot();
        switch (h.kind) {
            case MANAGE -> {
                if (slot == SLOT_MIDDLE) {
                    startPrompt(p, new Prompt(PromptType.RENAME, -1, null, null, expiry()));
                    p.sendMessage(m().msg("prompt-rename", "max", plugin.getConfig().getInt("shop-name-max-length", 24)));
                } else if (slot == SLOT_CLOSE) {
                    Bukkit.getScheduler().runTask(plugin, p::closeInventory);
                } else {
                    UUID id = h.slots.get(slot);
                    if (id != null) manageClick(p, h, id, e.getClick());
                }
            }
            case VIEW -> {
                if (slot == SLOT_CLOSE) {
                    Bukkit.getScheduler().runTask(plugin, p::closeInventory);
                } else {
                    UUID id = h.slots.get(slot);
                    if (id != null) viewClick(p, h, id, e.getClick());
                }
            }
            case BROWSE -> {
                if (slot == SLOT_MIDDLE) {
                    Bukkit.getScheduler().runTask(plugin, p::closeInventory);
                } else if (slot == SLOT_PREV && h.page > 0) {
                    final int target = h.page - 1;
                    Bukkit.getScheduler().runTask(plugin, () -> openBrowse(p, target));
                } else if (slot == SLOT_NEXT) {
                    final int target = h.page + 1;
                    Bukkit.getScheduler().runTask(plugin, () -> openBrowse(p, target));
                } else {
                    UUID owner = h.slots.get(slot);
                    PlayerShop s = owner == null ? null : plugin.shops().get(owner);
                    if (s != null) Bukkit.getScheduler().runTask(plugin, () -> openView(p, s));
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        prompts.remove(e.getPlayer().getUniqueId());
        pendings.remove(e.getPlayer().getUniqueId());
    }

    // ───────────────────────── إدارة المحل ─────────────────────────

    private void beginNewListing(Player p, int slot, ItemStack clicked) {
        if (!p.hasPermission("createshop.use")) {
            p.sendMessage(m().msg("no-permission"));
            return;
        }
        if (clicked == null || clicked.getType().isAir()) return;
        if (slot < 0 || slot > 35) {
            p.sendMessage(m().msg("bad-slot"));
            return;
        }
        if (plugin.isBlacklisted(clicked.getType())) {
            p.sendMessage(m().msg("blacklisted"));
            return;
        }
        PlayerShop shop = plugin.shops().getOrCreate(p);
        int max = plugin.maxListings(p);
        if (shop.listings.size() >= max) {
            p.sendMessage(m().msg("limit-reached", "max", max));
            return;
        }
        startPrompt(p, new Prompt(PromptType.NEW_LISTING, slot, clicked.clone(), null, expiry()));
        p.sendMessage(m().msg("prompt-price", "item", itemName(clicked), "amount", clicked.getAmount()));
    }

    private void manageClick(Player p, Holder h, UUID id, ClickType click) {
        PlayerShop shop = plugin.shops().get(p.getUniqueId());
        Listing l = shop == null ? null : shop.listings.get(id);
        if (l == null) {
            refresh(p, h);
            return;
        }
        switch (click) {
            case LEFT -> {
                if (!l.forSale) {
                    PriceService.Range bad = plugin.prices().violation(l.item.getType(), l.unitPrice);
                    if (bad != null) {
                        p.sendMessage(m().msg("toggle-blocked", "min", plugin.money(bad.min()), "max", plugin.money(bad.max())));
                        return;
                    }
                    l.forSale = true;
                    p.sendMessage(m().msg("toggled-sale"));
                } else {
                    l.forSale = false;
                    p.sendMessage(m().msg("toggled-display"));
                }
                plugin.shops().saveNow();
                refresh(p, h);
            }
            case RIGHT -> {
                startPrompt(p, new Prompt(PromptType.CHANGE_PRICE, -1, null, id, expiry()));
                p.sendMessage(m().msg("prompt-price", "item", itemName(l.item), "amount", l.item.getAmount()));
            }
            case SHIFT_LEFT, SHIFT_RIGHT -> {
                if (!canFit(p.getInventory(), l.item, l.item.getAmount())) {
                    p.sendMessage(m().msg("inv-full-remove"));
                    return;
                }
                shop.listings.remove(id);
                p.getInventory().addItem(l.item.clone());
                plugin.shops().saveNow();
                p.sendMessage(m().msg("removed", "item", itemName(l.item), "amount", l.item.getAmount()));
                refresh(p, h);
            }
            default -> {
            }
        }
    }

    private void refresh(Player p, Holder h) {
        PlayerShop shop = plugin.shops().get(h.owner);
        if (shop != null && h.inventory != null) fillListings(h.inventory, h, shop);
        p.updateInventory();
    }

    // ───────────────────────── الشراء ─────────────────────────

    private void viewClick(Player p, Holder h, UUID id, ClickType click) {
        if (!p.hasPermission("createshop.visit")) {
            p.sendMessage(m().msg("no-permission"));
            return;
        }
        PlayerShop shop = plugin.shops().get(h.owner);
        Listing l = shop == null ? null : shop.listings.get(id);
        if (l == null) {
            p.sendMessage(m().msg("listing-gone"));
            if (shop != null) refresh(p, h);
            return;
        }
        if (p.getUniqueId().equals(shop.owner)) {
            p.sendMessage(m().msg("own-item"));
            return;
        }
        if (!l.forSale) {
            p.sendMessage(m().msg("not-for-sale"));
            return;
        }
        int amount = l.item.getAmount();
        boolean single = click.isRightClick() && amount > 1 && plugin.getConfig().getBoolean("allow-single-unit-purchase", true);
        int qty = single ? 1 : amount;
        double total = plugin.round2(l.unitPrice * qty);

        if (plugin.getConfig().getBoolean("confirm-purchase", true)) {
            Pending pd = pendings.get(p.getUniqueId());
            long now = System.currentTimeMillis();
            boolean confirmed = pd != null && pd.expires() > now && pd.listingId().equals(id)
                    && pd.qty() == qty && pd.owner().equals(shop.owner);
            if (confirmed && Double.compare(pd.unitPrice(), l.unitPrice) != 0) {
                pendings.remove(p.getUniqueId());
                p.sendMessage(m().msg("price-mismatch"));
                refresh(p, h);
                return;
            }
            if (!confirmed) {
                long ms = plugin.getConfig().getLong("confirm-timeout-seconds", 8) * 1000L;
                pendings.put(p.getUniqueId(), new Pending(shop.owner, id, qty, l.unitPrice, now + ms));
                p.sendMessage(m().msg("confirm-buy", "item", itemName(l.item), "amount", qty, "total", plugin.money(total)));
                return;
            }
            pendings.remove(p.getUniqueId());
        }
        purchase(p, h, shop, l, qty);
    }

    private void purchase(Player buyer, Holder h, PlayerShop shop, Listing l, int qty) {
        Economy eco = plugin.economy();
        if (eco == null) {
            buyer.sendMessage(m().msg("economy-missing"));
            return;
        }
        double total = plugin.round2(l.unitPrice * qty);
        if (!plugin.canAfford(buyer, total)) {
            buyer.sendMessage(m().msg("not-enough", "total", plugin.money(total)));
            return;
        }
        ItemStack give = l.item.clone();
        give.setAmount(qty);
        if (!canFit(buyer.getInventory(), give, qty)) {
            buyer.sendMessage(m().msg("inv-full-buy"));
            return;
        }

        EconomyResponse w = plugin.charge(buyer, total);
        if (!w.transactionSuccess()) {
            buyer.sendMessage(m().msg("buy-failed", "reason", String.valueOf(w.errorMessage)));
            return;
        }
        double tax = plugin.round2(total * Math.max(0, plugin.getConfig().getDouble("sale-tax-percent", 0)) / 100.0);
        double payout = plugin.round2(total - tax);
        OfflinePlayer owner = Bukkit.getOfflinePlayer(shop.owner);
        EconomyResponse d = eco.depositPlayer(owner, payout);
        if (!d.transactionSuccess()) {
            eco.depositPlayer(buyer, total); // استرجاع الفلوس
            buyer.sendMessage(m().msg("buy-failed", "reason", String.valueOf(d.errorMessage)));
            return;
        }

        buyer.getInventory().addItem(give);
        int remaining = l.item.getAmount() - qty;
        if (remaining <= 0) {
            shop.listings.remove(l.id);
        } else {
            l.item.setAmount(remaining);
        }
        plugin.shops().saveNow();

        String name = itemName(give);
        buyer.sendMessage(m().msg("bought", "item", name, "amount", qty, "seller", shop.ownerName, "total", plugin.money(total)));
        Player sellerOnline = Bukkit.getPlayer(shop.owner);
        if (sellerOnline != null) {
            sellerOnline.sendMessage(m().msg("sold", "buyer", buyer.getName(), "item", name, "amount", qty,
                    "payout", plugin.money(payout)));
        }
        if (plugin.getConfig().getBoolean("log-sales", true)) {
            plugin.getLogger().info(buyer.getName() + " bought " + qty + "x " + give.getType() + " from "
                    + shop.ownerName + " for " + total + " (payout " + payout + ")");
        }
        refresh(buyer, h);
    }

    // ───────────────────────── كتابة السعر / الاسم في الشات ─────────────────────────

    private long expiry() {
        return System.currentTimeMillis() + plugin.getConfig().getLong("prompt-timeout-seconds", 60) * 1000L;
    }

    private void startPrompt(Player p, Prompt prompt) {
        prompts.put(p.getUniqueId(), prompt);
        Bukkit.getScheduler().runTask(plugin, p::closeInventory);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        if (!prompts.containsKey(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        final Player p = e.getPlayer();
        final String text = e.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> handlePrompt(p, text));
    }

    private void handlePrompt(Player p, String text) {
        Prompt pr = prompts.get(p.getUniqueId());
        if (pr == null) return;
        if (pr.expires() < System.currentTimeMillis()) {
            prompts.remove(p.getUniqueId());
            p.sendMessage(m().msg("prompt-expired"));
            return;
        }
        String low = text.toLowerCase(Locale.ROOT);
        if (low.equals("cancel") || text.equals("الغاء") || text.equals("إلغاء") || text.equals("الغا")) {
            prompts.remove(p.getUniqueId());
            p.sendMessage(m().msg("prompt-cancelled"));
            openManage(p);
            return;
        }

        if (pr.type() == PromptType.RENAME) {
            prompts.remove(p.getUniqueId());
            int max = plugin.getConfig().getInt("shop-name-max-length", 24);
            String name = Messages.color(text);
            String plain = org.bukkit.ChatColor.stripColor(name);
            if (plain == null || plain.isEmpty()) {
                openManage(p);
                return;
            }
            if (plain.length() > max) name = name.substring(0, Math.min(name.length(), max));
            PlayerShop shop = plugin.shops().getOrCreate(p);
            shop.title = name;
            plugin.shops().saveNow();
            p.sendMessage(m().msg("renamed", "name", name));
            openManage(p);
            return;
        }

        // سعر
        double minP = plugin.getConfig().getDouble("min-price", 0.01);
        double maxP = plugin.getConfig().getDouble("max-price", 1_000_000_000);
        Double parsed = parsePrice(text);
        if (parsed == null || parsed < minP || parsed > maxP) {
            prompts.put(p.getUniqueId(), new Prompt(pr.type(), pr.invSlot(), pr.snapshot(), pr.listingId(), expiry()));
            p.sendMessage(m().msg("invalid-price", "min", plugin.money(minP), "max", plugin.money(maxP)));
            return;
        }
        double price = plugin.round2(parsed);
        if (price < minP) price = minP;
        prompts.remove(p.getUniqueId());

        PlayerShop shop = plugin.shops().getOrCreate(p);
        if (pr.type() == PromptType.NEW_LISTING) {
            finishNewListing(p, shop, pr, price);
        } else {
            Listing l = shop.listings.get(pr.listingId());
            if (l == null) {
                p.sendMessage(m().msg("listing-gone"));
                openManage(p);
                return;
            }
            l.unitPrice = price;
            PriceService.Range bad = l.forSale ? plugin.prices().violation(l.item.getType(), price) : null;
            if (bad != null) {
                l.forSale = false;
                p.sendMessage(m().msg("price-changed-display", "price", plugin.money(price),
                        "min", plugin.money(bad.min()), "max", plugin.money(bad.max())));
            } else {
                p.sendMessage(m().msg("price-changed", "price", plugin.money(price)));
            }
            plugin.shops().saveNow();
            openManage(p);
        }
    }

    private void finishNewListing(Player p, PlayerShop shop, Prompt pr, double price) {
        int max = plugin.maxListings(p);
        if (shop.listings.size() >= max) {
            p.sendMessage(m().msg("limit-reached", "max", max));
            return;
        }
        ItemStack cur = p.getInventory().getItem(pr.invSlot());
        ItemStack snap = pr.snapshot();
        if (cur == null || !cur.isSimilar(snap) || cur.getAmount() != snap.getAmount()) {
            p.sendMessage(m().msg("item-changed"));
            openManage(p);
            return;
        }
        p.getInventory().setItem(pr.invSlot(), null);

        boolean wantSale = plugin.getConfig().getBoolean("default-for-sale", true);
        PriceService.Range bad = wantSale ? plugin.prices().violation(cur.getType(), price) : null;
        boolean forSale = wantSale && bad == null;

        Listing l = new Listing(UUID.randomUUID(), cur.clone(), price, forSale);
        shop.listings.put(l.id, l);
        plugin.shops().saveNow();

        String name = itemName(cur);
        if (bad != null) {
            p.sendMessage(m().msg("listed-display-forced", "item", name,
                    "min", plugin.money(bad.min()), "max", plugin.money(bad.max())));
        } else {
            p.sendMessage(m().msg(forSale ? "listed-sale" : "listed-display",
                    "item", name, "amount", cur.getAmount(), "price", plugin.money(price)));
        }
        openManage(p);
    }

    /** يقبل: 150 | 2.5k | 1m | 1,000 | أرقام عربية ٣٥٠ | فاصلة عربية ٢٫٥ */
    static Double parsePrice(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= '٠' && c <= '٩') sb.append((char) ('0' + (c - '٠')));
            else if (c >= '۰' && c <= '۹') sb.append((char) ('0' + (c - '۰')));
            else if (c == '٫') sb.append('.');
            else if (c == ',' || c == '٬' || c == '$' || c == ' ' || c == '_') continue;
            else sb.append(c);
        }
        String s = sb.toString();
        double mult = 1;
        if (s.endsWith("k") || s.endsWith("ك")) {
            mult = 1_000;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("m")) {
            mult = 1_000_000;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("b")) {
            mult = 1_000_000_000;
            s = s.substring(0, s.length() - 1);
        }
        try {
            double v = Double.parseDouble(s) * mult;
            if (Double.isNaN(v) || Double.isInfinite(v)) return null;
            return v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static boolean canFit(Inventory inv, ItemStack stack, int qty) {
        int remaining = qty;
        int max = stack.getMaxStackSize();
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) {
                remaining -= max;
            } else if (s.isSimilar(stack)) {
                remaining -= Math.max(0, max - s.getAmount());
            }
            if (remaining <= 0) return true;
        }
        return false;
    }
}
