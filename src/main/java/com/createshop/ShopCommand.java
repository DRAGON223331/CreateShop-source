package com.createshop;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ShopCommand implements CommandExecutor, TabCompleter {

    private final CreateShopPlugin plugin;

    public ShopCommand(CreateShopPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        Messages m = plugin.messages();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!sender.hasPermission("createshop.admin")) {
                sender.sendMessage(m.msg("no-permission"));
                return true;
            }
            plugin.reloadAll();
            sender.sendMessage(m.msg("reloaded"));
            return true;
        }
        if (sub.equals("help") || sub.equals("?")) {
            for (String line : m.list("help")) sender.sendMessage(line);
            return true;
        }

        if (!(sender instanceof Player p)) {
            sender.sendMessage(m.msg("players-only"));
            return true;
        }
        if (plugin.economy() == null) {
            p.sendMessage(m.msg("economy-missing"));
            return true;
        }

        switch (sub) {
            case "" , "manage", "my", "me" -> {
                if (!p.hasPermission("createshop.use")) {
                    p.sendMessage(m.msg("no-permission"));
                    return true;
                }
                plugin.gui().openManage(p);
            }
            case "visit", "v", "زيارة" -> {
                if (!p.hasPermission("createshop.visit")) {
                    p.sendMessage(m.msg("no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    p.sendMessage(m.msg("usage-visit"));
                    return true;
                }
                PlayerShop shop = plugin.shops().findByName(args[1]);
                if (shop == null) {
                    p.sendMessage(m.msg("unknown-shop", "player", args[1]));
                    return true;
                }
                plugin.gui().openView(p, shop);
            }
            case "browse", "list", "b", "قائمة" -> {
                if (!p.hasPermission("createshop.visit")) {
                    p.sendMessage(m.msg("no-permission"));
                    return true;
                }
                plugin.gui().openBrowse(p, 0);
            }
            default -> {
                for (String line : m.list("help")) p.sendMessage(line);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            String a = args[0].toLowerCase(Locale.ROOT);
            for (String s : new String[]{"visit", "browse", "help", "reload"}) {
                if (s.startsWith(a) && (!s.equals("reload") || sender.hasPermission("createshop.admin"))) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("visit")) {
            String a = args[1].toLowerCase(Locale.ROOT);
            for (PlayerShop s : plugin.shops().all()) {
                if (s.ownerName.toLowerCase(Locale.ROOT).startsWith(a)) out.add(s.ownerName);
            }
        }
        return out;
    }
}
