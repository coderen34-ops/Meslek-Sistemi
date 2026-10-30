package me.mesleksistemi.kacak;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import me.mesleksistemi.MeslekSistemi;

/**
 * Kaçak mal eşyası: tamamen oyun içi, uydurma maddeler (gerçek madde/tarif/kimya YOK).
 * Eşya PDC etiketiyle tanınır; isim değiştirmek etiketi bozmaz, adını taklit eden sahte eşya etiketsiz olur.
 * Oyuncuya görünen metinlerde "uyuşturucu" kelimesi kullanılmaz.
 */
public final class KacakMal {

    public enum Tur {
        KARA_TOZ("kara_toz", "Kara Toz", Material.GUNPOWDER, ChatColor.DARK_GRAY),
        MAVI_OZ("mavi_oz", "Mavi Öz", Material.LIGHT_BLUE_DYE, ChatColor.AQUA);

        public final String id, ad;
        public final Material madde;
        public final ChatColor renk;

        Tur(String id, String ad, Material madde, ChatColor renk) {
            this.id = id; this.ad = ad; this.madde = madde; this.renk = renk;
        }

        public static Tur bul(String id) {
            for (Tur t : values()) if (t.id.equals(id)) return t;
            return null;
        }
    }

    /** Kalite 1-3 (düşük/orta/yüksek). Kaçakçı ve üretim kalite verir; müsadere kaliteyi önemsemez. */
    public static final String[] KALITE_ADI = {"", "Düşük Kalite", "Orta Kalite", "Yüksek Kalite"};

    private KacakMal() {}

    private static NamespacedKey turKey(Plugin p) { return new NamespacedKey(p, "kacak_mal"); }
    private static NamespacedKey kaliteKey(Plugin p) { return new NamespacedKey(p, "kacak_mal_kalite"); }

    public static ItemStack olustur(MeslekSistemi plugin, Tur tur, int kalite, int adet) {
        kalite = Math.max(1, Math.min(3, kalite));
        ItemStack item = new ItemStack(tur.madde, Math.max(1, adet));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(tur.renk + "" + ChatColor.BOLD + tur.ad);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + KALITE_ADI[kalite]);
        lore.add(ChatColor.DARK_RED + "Kaçak mal - polis müsadere eder");
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(turKey(plugin), PersistentDataType.STRING, tur.id);
        meta.getPersistentDataContainer().set(kaliteKey(plugin), PersistentDataType.INTEGER, kalite);
        item.setItemMeta(meta);
        return item;
    }

    public static Tur turu(MeslekSistemi plugin, ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(turKey(plugin), PersistentDataType.STRING);
        return id == null ? null : Tur.bul(id);
    }

    public static int kalitesi(MeslekSistemi plugin, ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        Integer k = item.getItemMeta().getPersistentDataContainer().get(kaliteKey(plugin), PersistentDataType.INTEGER);
        return k == null ? 0 : k;
    }

    public static boolean kacakMalMi(MeslekSistemi plugin, ItemStack item) {
        return turu(plugin, item) != null;
    }
}
