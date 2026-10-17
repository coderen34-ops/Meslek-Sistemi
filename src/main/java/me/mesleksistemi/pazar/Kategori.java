package me.mesleksistemi.pazar;

import org.bukkit.Material;

/**
 * Pazar standı ilanlarını eşya türüne göre kategorilere ayırır (alışveriş menüsünde
 * "her eşya türünün ayrı kategorisi olsun" isteği için).
 */
public enum Kategori {
    YIYECEK("§6Yiyecek ve İçecek", Material.COOKED_BEEF),
    ARAC_SILAH("§cAraç ve Silah", Material.DIAMOND_SWORD),
    ZIRH("§bZırh", Material.DIAMOND_CHESTPLATE),
    REDSTONE("§4Redstone ve Mekanizma", Material.REDSTONE),
    IKSIR_BUYU("§dİksir ve Büyü", Material.ENCHANTED_BOOK),
    BLOK("§eBloklar ve Yapı Malzemesi", Material.BRICKS),
    DIGER("§7Diğer", Material.CHEST);

    public final String ad;
    public final Material ikon;

    Kategori(String ad, Material ikon) {
        this.ad = ad;
        this.ikon = ikon;
    }

    /** Verilen eşyanın hangi kategoriye girdiğini söyler. */
    public static Kategori sinifla(Material m) {
        String n = m.name();
        if (isYiyecek(m)) return YIYECEK;
        if (n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")
                || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT") || n.equals("MACE") || n.equals("ARROW")) return ARAC_SILAH;
        if (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE") || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || n.equals("SHIELD") || n.endsWith("_HORSE_ARMOR") || n.equals("ELYTRA") || n.equals("TURTLE_HELMET")) return ZIRH;
        if (n.contains("REDSTONE") || n.contains("REPEATER") || n.contains("COMPARATOR") || n.contains("PISTON")
                || n.equals("OBSERVER") || n.equals("HOPPER") || n.equals("DISPENSER") || n.equals("DROPPER")
                || n.contains("RAIL") || n.contains("LEVER") || n.contains("_BUTTON") || n.contains("PRESSURE_PLATE")
                || n.contains("TARGET") || n.equals("TRIPWIRE_HOOK") || n.equals("DAYLIGHT_DETECTOR")) return REDSTONE;
        if (n.contains("POTION") || n.equals("ENCHANTED_BOOK") || n.equals("EXPERIENCE_BOTTLE") || n.contains("ENCHANTING")) return IKSIR_BUYU;
        if (m.isBlock()) return BLOK;
        return DIGER;
    }

    private static boolean isYiyecek(Material m) {
        try {
            return m.isEdible();
        } catch (Exception e) {
            return false;
        }
    }
}
