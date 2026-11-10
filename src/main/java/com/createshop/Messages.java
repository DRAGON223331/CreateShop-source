package com.createshop;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Messages {

    private final CreateShopPlugin plugin;
    private YamlConfiguration yml = new YamlConfiguration();

    public Messages(CreateShopPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File f = new File(plugin.getDataFolder(), "messages.yml");
        if (!f.exists()) plugin.saveResource("messages.yml", false);
        yml = YamlConfiguration.loadConfiguration(f);
        InputStream in = plugin.getResource("messages.yml");
        if (in != null) {
            yml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
    }

    public static String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private String apply(String s, Object... kv) {
        for (int i = 0; i + 1 < kv.length; i += 2) {
            s = s.replace("%" + kv[i] + "%", String.valueOf(kv[i + 1]));
        }
        return s;
    }

    /** رسالة شات (مع البادئة). */
    public String msg(String key, Object... kv) {
        String s = yml.getString(key, key);
        s = s.replace("%prefix%", yml.getString("prefix", ""));
        return color(apply(s, kv));
    }

    /** نص للواجهة (بدون بادئة). */
    public String gui(String key, Object... kv) {
        return color(apply(yml.getString(key, key), kv));
    }

    public List<String> list(String key, Object... kv) {
        List<String> out = new ArrayList<>();
        for (String s : yml.getStringList(key)) out.add(color(apply(s, kv)));
        return out;
    }
}
