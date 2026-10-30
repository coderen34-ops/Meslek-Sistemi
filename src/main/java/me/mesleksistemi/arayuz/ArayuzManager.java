package me.mesleksistemi.arayuz;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scoreboard.NameTagVisibility;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.scheduler.BukkitRunnable;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import me.mesleksistemi.MeslekSistemi;

/**
 * Arayüz katmanı:
 *  1) Vanilla isim etiketleri herkesin başında kapatılır (takım ayarı; mevcut takımın prefix/suffix'ine dokunulmaz).
 *  2) İsim etiketi: sadece nişanı (crosshair) o oyuncuya çevrilince, yalnızca o izleyiciye gösterilir.
 *     İstisna: klan savaşı, vahşi kale kuşatması veya arena maçındaki izleyici yakındaki herkesin ismini görür.
 *     Etiketler TextDisplay'dir: duvarların arkasından görünmez.
 *  3) "ARANIYOR" göstergesi: aranan oyuncunun kafasında, sadece polis/amirlere görünür (tab'de yazmaz).
 *  4) Sohbet balonu: oyuncu yazınca kısa süre kafasının üstünde, yakındaki alıcılara görünür.
 * Her şey oyuncunun yolcusu (passenger) olan, varsayılan olarak GİZLİ TextDisplay'lerle yapılır ve izleyici başına açılır.
 */
public class ArayuzManager implements Listener {

    private static final double MENZIL = 24.0;
    private static final double TAM_MENZIL = 40.0;
    private static final int BALON_TICK = 20 * 6;

    private static final class Gosterge {
        TextDisplay isim, aranan, balon;
        int balonToken;
        String balonMetin = "";
        Set<UUID> balonIzleyen = new HashSet<>();
    }

    private final MeslekSistemi plugin;
    private final NamespacedKey anahtar;
    private final Map<UUID, Gosterge> gostergeler = new HashMap<>();
    private Team gizliTakim;
    /** Klan NPC'leri (nöbetçi/lider/muhafız): entity -> etiket. Etiket sadece nişan alanlara (ve savaş/kuşatmadakilere) görünür. */
    private final Map<UUID, TextDisplay> npcEtiket = new HashMap<>();
    private static final NamespacedKey NPC_ISIM_KEY = new NamespacedKey("klansistemi", "isim_gizli");

    public ArayuzManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.anahtar = new NamespacedKey(plugin, "arayuz_gosterge");
        plugin.getConfig().addDefault("arayuz.aktif", true);
        plugin.getConfig().addDefault("arayuz.yukseklik-taban", 0.35);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();

        // Emniyet: önceki oturumdan kalmış gösterge entity'lerini temizle
        for (org.bukkit.World w : Bukkit.getWorlds())
            for (TextDisplay d : w.getEntitiesByClass(TextDisplay.class))
                if (d.getPersistentDataContainer().has(anahtar, org.bukkit.persistence.PersistentDataType.BYTE)) d.remove();

        new BukkitRunnable() {
            @Override public void run() { if (aktif()) guncelle(); }
        }.runTaskTimer(plugin, 40L, 2L);
        new BukkitRunnable() {
            @Override public void run() { if (aktif()) etiketGizle(); }
        }.runTaskTimer(plugin, 40L, 40L);
        // Eski "Aranıyor" tab son eki kalmışsa temizle
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (String ad : new ArrayList<>(plugin.polisManager.arananOyuncular))
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag removesuffix " + ad);
        }, 200L);
    }

    private boolean aktif() { return plugin.getConfig().getBoolean("arayuz.aktif", true); }

    public void kapat() {
        for (TextDisplay d : npcEtiket.values()) if (d != null && d.isValid()) d.remove();
        npcEtiket.clear();
        for (Gosterge g : gostergeler.values()) kaldir(g);
        gostergeler.clear();
    }

    // ------------------------------------------------------------------ vanilla etiket
    private void etiketGizle() {
        Scoreboard sb = Bukkit.getScoreboardManager().getMainScoreboard();
        if (gizliTakim == null) {
            gizliTakim = sb.getTeam("ks_gizli");
            if (gizliTakim == null) gizliTakim = sb.registerNewTeam("ks_gizli");
            gizliTakim.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasMetadata("NPC")) continue;
            Team t = sb.getEntryTeam(p.getName());
            if (t == null) { gizliTakim.addEntry(p.getName()); }
            else if (t.getOption(Team.Option.NAME_TAG_VISIBILITY) != Team.OptionStatus.NEVER) {
                t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            }
        }
    }

    // ------------------------------------------------------------------ gösterge entity'leri
    // NOT: Oyuncunun yolcusu YAPILMAZ (yolcusu olan oyuncu ışınlanamaz); her 2 tickte oyuncuya taşınır.
    private TextDisplay olustur(Entity sahip, Component metin) {
        return sahip.getWorld().spawn(konum(sahip, 0), TextDisplay.class, e -> {
            e.setVisibleByDefault(false);
            e.setPersistent(false);
            e.setBillboard(Display.Billboard.CENTER);
            e.setSeeThrough(false);
            e.setShadowed(true);
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setViewRange(0.5f);
            e.setTeleportDuration(2);
            e.getPersistentDataContainer().set(anahtar, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            e.text(metin);
        });
    }

    private org.bukkit.Location konum(Entity p, double ek) {
        double taban = plugin.getConfig().getDouble("arayuz.yukseklik-taban", 0.35);
        return p.getLocation().add(0, p.getHeight() + taban + ek, 0);
    }

    private void kaldir(Gosterge g) {
        if (g == null) return;
        for (TextDisplay d : new TextDisplay[] {g.isim, g.aranan, g.balon}) if (d != null && d.isValid()) d.remove();
        g.isim = null; g.aranan = null; g.balon = null;
    }

    /** Gösterge varsa/gerekliyse oluşturur, konumlar; izleyen kümesine göre izleyici başına açar/kapatır. */
    private TextDisplay esitle(Entity sahip, TextDisplay d, Set<UUID> izleyenler, Component metin, double ek) {
        if (izleyenler.isEmpty()) {
            if (d != null && d.isValid()) d.remove();
            return null;
        }
        if (d == null || !d.isValid() || !d.getWorld().equals(sahip.getWorld())) {
            if (d != null && d.isValid()) d.remove();
            d = olustur(sahip, metin);
        } else {
            d.teleport(konum(sahip, ek));
        }
        for (Player v : Bukkit.getOnlinePlayers()) {
            boolean ister = izleyenler.contains(v.getUniqueId());
            boolean goruyor = v.canSee(d);
            if (ister && !goruyor) v.showEntity(plugin, d);
            else if (!ister && goruyor) v.hideEntity(plugin, d);
        }
        return d;
    }

    // ------------------------------------------------------------------ tam görünürlük istisnası (başka eklentiler)
    private final Map<String, Method> yontemler = new HashMap<>();

    private boolean tamGorunur(Player p) {
        for (String ad : new String[] {"KlanSistemi", "ArenaLigi"}) {
            Plugin pl = Bukkit.getPluginManager().getPlugin(ad);
            if (pl == null || !pl.isEnabled()) continue;
            try {
                Method m = yontemler.get(ad);
                if (m == null) {
                    m = pl.getClass().getMethod("isimTamGorunur", Player.class);
                    yontemler.put(ad, m);
                }
                if (Boolean.TRUE.equals(m.invoke(pl, p))) return true;
            } catch (NoSuchMethodException ignored) {
                // eklenti henüz güncellenmemiş
            } catch (Exception e) {
                plugin.getLogger().fine("isimTamGorunur çağrılamadı: " + e.getMessage());
            }
        }
        return false;
    }

    /** Vanish'teki (Essentials/SuperVanish/PremiumVanish "vanished" metadata) oyuncu: etiket, Aranıyor ve balon gösterilmez. */
    private boolean vanishli(Player p) {
        for (org.bukkit.metadata.MetadataValue v : p.getMetadata("vanished")) {
            if (v.asBoolean()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ güncelleme döngüsü
    private void guncelle() {
        Map<UUID, Set<UUID>> isimIzleyen = new HashMap<>();
        Map<UUID, Set<UUID>> arananIzleyen = new HashMap<>();
        Map<UUID, Set<UUID>> npcIzleyen = new HashMap<>();
        Map<UUID, Entity> npcVarlik = new HashMap<>();

        for (Player izleyen : Bukkit.getOnlinePlayers()) {
            if (izleyen.hasMetadata("NPC")) continue;
            boolean polis = plugin.gbtManager != null && plugin.gbtManager.polisMi(izleyen);
            boolean tam = tamGorunur(izleyen);
            Entity hedef = null;
            try { hedef = izleyen.getTargetEntity((int) MENZIL, false); } catch (Throwable ignored) { }

            // Klan NPC'leri: nişan alınan ya da (savaş/kuşatmadaysa) yakındaki isim-gizli NPC'ler
            if (hedef != null && !(hedef instanceof Player) && hedef.getPersistentDataContainer().has(NPC_ISIM_KEY, org.bukkit.persistence.PersistentDataType.STRING)) {
                npcIzleyen.computeIfAbsent(hedef.getUniqueId(), x -> new HashSet<>()).add(izleyen.getUniqueId());
                npcVarlik.put(hedef.getUniqueId(), hedef);
            }
            if (tam) {
                for (Entity yakin : izleyen.getNearbyEntities(TAM_MENZIL, TAM_MENZIL, TAM_MENZIL)) {
                    if (yakin instanceof Player) continue;
                    if (!yakin.getPersistentDataContainer().has(NPC_ISIM_KEY, org.bukkit.persistence.PersistentDataType.STRING)) continue;
                    npcIzleyen.computeIfAbsent(yakin.getUniqueId(), x -> new HashSet<>()).add(izleyen.getUniqueId());
                    npcVarlik.put(yakin.getUniqueId(), yakin);
                }
            }

            for (Player hp : Bukkit.getOnlinePlayers()) {
                if (hp.getUniqueId().equals(izleyen.getUniqueId()) || hp.hasMetadata("NPC")) continue;
                if (!hp.getWorld().equals(izleyen.getWorld())) continue;
                if (vanishli(hp) || !izleyen.canSee(hp)) continue; // vanish'teki / izleyenin göremediği oyuncuyu açığa çıkarma
                double mesafe2 = hp.getLocation().distanceSquared(izleyen.getLocation());
                boolean hedefte = hedef != null && hedef.getUniqueId().equals(hp.getUniqueId());
                if ((tam && mesafe2 <= TAM_MENZIL * TAM_MENZIL) || hedefte)
                    isimIzleyen.computeIfAbsent(hp.getUniqueId(), x -> new HashSet<>()).add(izleyen.getUniqueId());
                if (polis && mesafe2 <= TAM_MENZIL * TAM_MENZIL
                        && plugin.polisManager.arananOyuncular.contains(hp.getName().toLowerCase(Locale.ROOT)))
                    arananIzleyen.computeIfAbsent(hp.getUniqueId(), x -> new HashSet<>()).add(izleyen.getUniqueId());
            }
        }

        Set<UUID> canli = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasMetadata("NPC")) continue;
            canli.add(p.getUniqueId());
            Set<UUID> ii = isimIzleyen.getOrDefault(p.getUniqueId(), java.util.Collections.emptySet());
            Set<UUID> ai = arananIzleyen.getOrDefault(p.getUniqueId(), java.util.Collections.emptySet());
            Gosterge g = gostergeler.get(p.getUniqueId());
            if (g == null) {
                if (ii.isEmpty() && ai.isEmpty()) continue;
                g = new Gosterge();
                gostergeler.put(p.getUniqueId(), g);
            }
            g.isim = esitle(p, g.isim, ii, Component.text(p.getName(), NamedTextColor.WHITE), 0.0);
            g.aranan = esitle(p, g.aranan, ai,
                    Component.text("ARANIYOR", NamedTextColor.RED).decorate(TextDecoration.BOLD), 0.28);
            if (g.balon != null) {
                if (g.balon.isValid() && !g.balonIzleyen.isEmpty()) g.balon.teleport(konum(p, 0.56));
                else { g.balon = null; }
            }
            if (g.isim == null && g.aranan == null && g.balon == null) gostergeler.remove(p.getUniqueId());
        }
        for (UUID id : new ArrayList<>(gostergeler.keySet())) {
            if (!canli.contains(id)) kaldir(gostergeler.remove(id));
        }

        // Klan NPC etiketleri
        for (Map.Entry<UUID, Entity> e : npcVarlik.entrySet()) {
            Entity ent = e.getValue();
            if (!ent.isValid()) continue;
            String ham = ent.getPersistentDataContainer().get(NPC_ISIM_KEY, org.bukkit.persistence.PersistentDataType.STRING);
            Component metin = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(ham == null ? "" : ham);
            TextDisplay d = esitle(ent, npcEtiket.get(e.getKey()), npcIzleyen.get(e.getKey()), metin, 0.0);
            if (d != null) npcEtiket.put(e.getKey(), d);
        }
        for (UUID id : new ArrayList<>(npcEtiket.keySet())) {
            if (npcVarlik.containsKey(id)) continue;
            TextDisplay d = npcEtiket.remove(id);
            if (d != null && d.isValid()) d.remove();
        }
    }

    // ------------------------------------------------------------------ sohbet balonu
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSohbet(AsyncPlayerChatEvent e) {
        if (!aktif()) return;
        final Player p = e.getPlayer();
        String ham = e.getMessage().replaceAll("§.", "").trim();
        if (ham.isEmpty()) return;
        if (ham.length() > 90) ham = ham.substring(0, 87) + "...";
        final String metin = ham;
        final Set<UUID> alicilar = new HashSet<>();
        for (Player r : e.getRecipients()) alicilar.add(r.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> balonGoster(p, metin, alicilar));
    }

    private void balonGoster(Player p, String metin, Set<UUID> alicilar) {
        if (!p.isOnline() || p.hasMetadata("NPC") || vanishli(p)) return;
        final Gosterge g = gostergeler.computeIfAbsent(p.getUniqueId(), x -> new Gosterge());
        Set<UUID> izleyenler = new HashSet<>();
        for (Player v : Bukkit.getOnlinePlayers()) {
            if (!v.getWorld().equals(p.getWorld())) continue;
            if (!v.getUniqueId().equals(p.getUniqueId()) && !alicilar.contains(v.getUniqueId())) continue;
            if (!v.getUniqueId().equals(p.getUniqueId()) && !v.canSee(p)) continue;
            if (v.getLocation().distanceSquared(p.getLocation()) <= MENZIL * MENZIL) izleyenler.add(v.getUniqueId());
        }
        if (izleyenler.isEmpty()) return;
        g.balonIzleyen = izleyenler;
        if (g.balon != null && g.balon.isValid()) g.balon.remove();
        g.balon = null;
        g.balon = esitle(p, null, izleyenler, Component.text(metin, NamedTextColor.WHITE), 0.56);
        if (g.balon != null) g.balon.setBackgroundColor(Color.fromARGB(150, 0, 0, 0));
        final int token = ++g.balonToken;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (g.balonToken != token) return;
            if (g.balon != null && g.balon.isValid()) g.balon.remove();
            g.balon = null;
            g.balonIzleyen = new HashSet<>();
        }, BALON_TICK);
    }

    @EventHandler
    public void onCikis(PlayerQuitEvent e) {
        kaldir(gostergeler.remove(e.getPlayer().getUniqueId()));
    }
}
