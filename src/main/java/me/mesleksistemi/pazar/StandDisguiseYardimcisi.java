package me.mesleksistemi.pazar;

import org.bukkit.entity.Villager;

import me.libraryaddict.disguise.DisguiseAPI;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;

/**
 * LibsDisguises'e özel kod, sınıf-yükleme (classloading) sırasında me.libraryaddict.* sınıflarını
 * çözemediği için PazarManager'ı çökertmesin diye bilerek ayrı bir sınıfa alındı.
 * Sadece çağrıldığında (ve LibsDisguises gerçekten etkinken) yüklenir; PazarManager.class bu
 * sınıfın adını taşır ama LibsDisguises API tiplerine doğrudan referans vermez.
 */
final class StandDisguiseYardimcisi {

    private StandDisguiseYardimcisi() {}

    /** NPC'yi kiralayan oyuncunun skin'ine büründürür; isim boşsa (stand boşaldıysa) disguise'ı kaldırır. */
    static void guncelle(Villager v, String isim) {
        if (DisguiseAPI.isDisguised(v)) DisguiseAPI.undisguiseToAll(v);
        if (isim == null || "?".equals(isim)) return;
        PlayerDisguise disguise = new PlayerDisguise(isim);
        disguise.setHideArmorFromSelf(true);
        DisguiseAPI.disguiseToAll(v, disguise);
    }
}
