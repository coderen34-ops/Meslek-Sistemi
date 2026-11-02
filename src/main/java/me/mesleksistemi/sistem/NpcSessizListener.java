package me.mesleksistemi.sistem;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.plugin.Plugin;

/** Eklentilerin kurduğu köylü NPC'leri (banka, tapu, tüccar vb.) ses çıkarmasın.
 *  Doğal/üreyen köylülere dokunmaz: yalnızca eklenti ile doğurulanlar (CUSTOM) ve
 *  yapay zekâsı kapalı ya da isimli köylüler sessize alınır. */
public class NpcSessizListener implements Listener {

    public NpcSessizListener(Plugin plugin) {
        // Sunucu açıldıktan kısa süre sonra, zaten yüklü olan dünyalardaki NPC'leri tara
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (World w : Bukkit.getWorlds()) for (Villager v : w.getEntitiesByClass(Villager.class)) sessizYap(v);
        }, 100L);
    }

    private static boolean npcMi(Villager v) {
        return v.customName() != null || !v.hasAI();
    }

    private static void sessizYap(Villager v) {
        if (npcMi(v) && !v.isSilent()) v.setSilent(true);
    }

    @EventHandler
    public void onDogma(CreatureSpawnEvent e) {
        if (e.getEntity() instanceof Villager v && e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) v.setSilent(true);
    }

    @EventHandler
    public void onYukle(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (en instanceof Villager v) sessizYap(v);
    }
}
