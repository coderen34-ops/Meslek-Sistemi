package me.mesleksistemi.pazar;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
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

    /**
     * NPC'yi kiralayan oyuncunun skin'ine büründürür; isim/uuid boşsa (stand boşaldıysa) disguise'ı kaldırır.
     *
     * ÖNEMLİ (skin geçmeme bugı): new PlayerDisguise(String isim) kullanıldığında LibsDisguises skin'i
     * İSİMLE Mojang API'sinden ASENKRON olarak çözmeye çalışır. Sunucu offline-mode/cracked ise (ya da
     * Mojang sorgusu başarısız/zaman aşımına uğrarsa) bu çözümleme hep BAŞARISIZ olur ve NPC varsayılan
     * (Steve) skin'inde kalır - kiralayan oyuncu online olsa bile. Bunun yerine, oyuncu online ise
     * new PlayerDisguise(Player) kurucusu kullanılıyor: bu, oyuncunun o anki GERÇEK skin'ini doğrudan
     * (ağ isteği/isim çözümlemesi olmadan) klonlar, offline-mode sunucularda da çalışır. Oyuncu o an
     * çevrimdışıysa (örn. sunucu açılışında stand adı güncellenirken) isim tabanlı çözümlemeye geri dönülür.
     */
    static void guncelle(Villager v, UUID sahipUuid, String isim) {
        if (DisguiseAPI.isDisguised(v)) DisguiseAPI.undisguiseToAll(v);
        if (isim == null || "?".equals(isim)) return;
        Player sahip = sahipUuid == null ? null : Bukkit.getPlayer(sahipUuid);
        PlayerDisguise disguise = sahip != null ? new PlayerDisguise(sahip) : new PlayerDisguise(isim);
        disguise.setHideArmorFromSelf(true);
        DisguiseAPI.disguiseToAll(v, disguise);
    }
}
