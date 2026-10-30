package me.mesleksistemi.sistem;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.mesleksistemi.MeslekSistemi;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * Oyuncu etkileşim sistemi.
 *
 * Boş elle (eğilmeden) başka bir oyuncuya sağ tık -> menü:
 *   Beğen / Beğenme / Yorum Bırak / Yorumları Gör
 *   (Belediye Başkanı için ayrıca: Vatandaşlıktan Çıkar)
 * Polis ve amirlere sağ tık şikayet menüsünü açar (PolisManager), onlar bu menüde yer almaz.
 * Kendi profilini görmek için: /profil   Başkasınınkini görmek için: /profil <oyuncu>
 *
 * Yorum Bırak'a basınca yazılan bir sonraki sohbet mesajı herkese gitmez, o oyuncunun yorumu olarak
 * kaydedilir ("iptal" yazarsan vazgeçilir).
 *
 * Kötüye kullanımı azaltmak için: yeni gelenler (varsayılan 30 dk oyun süresi dolmadan), göçmenler ve
 * mahkumlar oy veremez / yorum bırakamaz. Bir oyuncu bir oyuncuya tek oy verir (tekrar basarsa geri alır).
 *
 * Ayarlar (config.yml, hepsi isteğe bağlı):
 *   etkilesim.min-oyun-dakika: 30      oy/yorum için gereken oyun süresi
 *   etkilesim.yorum-min: 3             yorum en az kaç karakter
 *   etkilesim.yorum-max: 120           yorum en fazla kaç karakter
 *   etkilesim.yorum-bekleme-saniye: 60 aynı kişiye iki yorum arası
 *   etkilesim.en-fazla-yorum: 50       bir oyuncuda saklanan yorum sayısı (eskiler silinir)
 *
 * Yönetici izni: etkilesim.yonet (herkesin yorumunu silebilir).
 * Veriler plugins/MeslekSistemi/etkilesim.yml dosyasında tutulur.
 */
public class EtkilesimManager implements Listener, CommandExecutor, TabCompleter {

    public static final String YONET = "etkilesim.yonet";

    private static class Yorum {
        final UUID yazar;
        final String yazarAd;
        final String metin;
        final long zaman;

        Yorum(UUID yazar, String yazarAd, String metin, long zaman) {
            this.yazar = yazar;
            this.yazarAd = yazarAd;
            this.metin = metin;
            this.zaman = zaman;
        }
    }

    private enum Tip { ANA, YORUMLAR, ONAY }

    private static class EtkMenu implements InventoryHolder {
        final Tip tip;
        final UUID hedef;
        final Map<Integer, Yorum> slotYorum = new HashMap<>();
        Inventory envanter;

        EtkMenu(Tip tip, UUID hedef) {
            this.tip = tip;
            this.hedef = hedef;
        }

        @Override
        public Inventory getInventory() { return envanter; }
    }

    private static final int SLOT_BEGEN = 10;
    private static final int SLOT_BEGENME = 12;
    private static final int SLOT_YORUM_BIRAK = 14;
    private static final int SLOT_YORUM_GOR = 16;
    private static final int SLOT_CIKAR = 22;
    private static final int SLOT_PARA = 24;
    private static final double PARA_MIN = 1.0;
    private static final int SLOT_ONAY = 11;
    private static final int SLOT_VAZGEC = 15;
    private static final int SLOT_GERI = 49;

    private final MeslekSistemi plugin;
    private final File dosya;
    private final Object yazmaKilidi = new Object();

    // hedef -> (oy veren -> +1 / -1)
    private final Map<UUID, Map<UUID, Integer>> oylar = new HashMap<>();
    // hedef -> yorumlar (eskiden yeniye)
    private final Map<UUID, List<Yorum>> yorumlar = new HashMap<>();
    // bilinen oyuncu adları (çevrimdışı /profil için)
    private final Map<UUID, String> adlar = new HashMap<>();

    // yorum yazmayı bekleyenler: yazar -> hedef
    private final Map<UUID, UUID> yorumBekleyen = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> paraBekleyen = new ConcurrentHashMap<>();
    private final Map<String, Long> sonYorum = new HashMap<>();
    private boolean kayitPlanlandi = false;

    public EtkilesimManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.dosya = new File(plugin.getDataFolder(), "etkilesim.yml");
        yukle();
    }

    // ------------------------------------------------------------------ ayarlar
    private int minOyunDakika() { return Math.max(0, plugin.getConfig().getInt("etkilesim.min-oyun-dakika", 30)); }
    private int yorumMin() { return Math.max(1, plugin.getConfig().getInt("etkilesim.yorum-min", 3)); }
    private int yorumMax() { return Math.max(yorumMin(), plugin.getConfig().getInt("etkilesim.yorum-max", 120)); }
    private int yorumBekleme() { return Math.max(0, plugin.getConfig().getInt("etkilesim.yorum-bekleme-saniye", 60)); }
    private int enFazlaYorum() { return Math.max(1, plugin.getConfig().getInt("etkilesim.en-fazla-yorum", 50)); }

    // ------------------------------------------------------------------ kayıt
    private void yukle() {
        oylar.clear();
        yorumlar.clear();
        adlar.clear();
        if (!dosya.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);

        ConfigurationSection a = y.getConfigurationSection("adlar");
        if (a != null) {
            for (String k : a.getKeys(false)) {
                try { adlar.put(UUID.fromString(k), a.getString(k, "?")); } catch (IllegalArgumentException ignored) { }
            }
        }
        ConfigurationSection o = y.getConfigurationSection("oylar");
        if (o != null) {
            for (String hedefStr : o.getKeys(false)) {
                try {
                    UUID hedef = UUID.fromString(hedefStr);
                    ConfigurationSection s = o.getConfigurationSection(hedefStr);
                    if (s == null) continue;
                    Map<UUID, Integer> m = new HashMap<>();
                    for (String veren : s.getKeys(false)) {
                        int v = s.getInt(veren);
                        if (v == 1 || v == -1) m.put(UUID.fromString(veren), v);
                    }
                    if (!m.isEmpty()) oylar.put(hedef, m);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("etkilesim.yml içinde bozuk oy kaydı atlandı: " + hedefStr);
                }
            }
        }
        ConfigurationSection yo = y.getConfigurationSection("yorumlar");
        if (yo != null) {
            for (String hedefStr : yo.getKeys(false)) {
                try {
                    UUID hedef = UUID.fromString(hedefStr);
                    List<Yorum> liste = new ArrayList<>();
                    for (Map<?, ?> m : yo.getMapList(hedefStr)) {
                        Object yz = m.get("yazar");
                        Object ad = m.get("ad");
                        Object mt = m.get("metin");
                        Object zm = m.get("zaman");
                        if (yz == null || mt == null) continue;
                        liste.add(new Yorum(UUID.fromString(String.valueOf(yz)), ad == null ? "?" : String.valueOf(ad),
                                String.valueOf(mt), zm instanceof Number ? ((Number) zm).longValue() : 0L));
                    }
                    if (!liste.isEmpty()) yorumlar.put(hedef, liste);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("etkilesim.yml içinde bozuk yorum kaydı atlandı: " + hedefStr);
                }
            }
        }
    }

    private String icerikUret() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, String> e : adlar.entrySet()) y.set("adlar." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, Map<UUID, Integer>> e : oylar.entrySet()) {
            for (Map.Entry<UUID, Integer> v : e.getValue().entrySet()) {
                y.set("oylar." + e.getKey() + "." + v.getKey(), v.getValue());
            }
        }
        for (Map.Entry<UUID, List<Yorum>> e : yorumlar.entrySet()) {
            List<Map<String, Object>> liste = new ArrayList<>();
            for (Yorum c : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("yazar", c.yazar.toString());
                m.put("ad", c.yazarAd);
                m.put("metin", c.metin);
                m.put("zaman", c.zaman);
                liste.add(m);
            }
            y.set("yorumlar." + e.getKey(), liste);
        }
        return y.saveToString();
    }

    private void yaz(String icerik) {
        synchronized (yazmaKilidi) {
            try {
                plugin.getDataFolder().mkdirs();
                Files.write(dosya.toPath(), icerik.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                plugin.getLogger().warning("etkilesim.yml yazılamadı: " + e.getMessage());
            }
        }
    }

    /** Aynı anda gelen değişiklikleri birleştirir: içerik ana thread'de üretilir, dosya arka planda yazılır. */
    private void kaydetPlanla() {
        if (kayitPlanlandi) return;
        kayitPlanlandi = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            kayitPlanlandi = false;
            final String icerik = icerikUret();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> yaz(icerik));
        }, 40L);
    }

    /** Eklenti kapanırken bekleyen kaydı hemen yazar. */
    public void onDisable() {
        yaz(icerikUret());
    }

    // ------------------------------------------------------------------ yardımcılar
    private String meslekOf(Player p) {
        return plugin.oyuncuMeslekCache.getOrDefault(p.getUniqueId(), "vatandas");
    }

    private boolean polisMi(String meslek) {
        return meslek != null && (meslek.equalsIgnoreCase("polis") || meslek.equalsIgnoreCase("amir"));
    }

    private boolean baskanMi(Player p) {
        return meslekOf(p).equalsIgnoreCase("belediyebaskani");
    }

    /** Oy/yorum yapabilir mi? Yapamıyorsa nedenini döndürür, yapabiliyorsa null. */
    private String katilamazNedeni(Player p) {
        String m = meslekOf(p);
        if (m.equalsIgnoreCase("gocmen") || m.equalsIgnoreCase("default")) return "Göçmenler oy veremez ve yorum bırakamaz.";
        if (m.equalsIgnoreCase("mahkum")) return "Hapisteyken oy veremez ve yorum bırakamazsınız.";
        int dakika = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 60;
        if (dakika < minOyunDakika()) {
            return "Oy vermek ve yorum bırakmak için en az " + minOyunDakika() + " dakika oynamış olmalısınız (şu an " + dakika + ").";
        }
        return null;
    }

    private void adKaydet(Player p) {
        String eski = adlar.put(p.getUniqueId(), p.getName());
        if (eski == null || !eski.equals(p.getName())) kaydetPlanla();
    }

    private int begeniSayisi(UUID hedef, int deger) {
        Map<UUID, Integer> m = oylar.get(hedef);
        if (m == null) return 0;
        int n = 0;
        for (int v : m.values()) if (v == deger) n++;
        return n;
    }

    private String adOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) return p.getName();
        String ad = adlar.get(id);
        if (ad != null) return ad;
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() == null ? "?" : op.getName();
    }

    private ItemStack esya(Material m, String ad, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ad);
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add(s);
        meta.setLore(l);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack hedefKafasi(UUID hedef) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(hedef));
        meta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + adOf(hedef));
        int arti = begeniSayisi(hedef, 1);
        int eksi = begeniSayisi(hedef, -1);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GREEN + "Beğeni: " + ChatColor.WHITE + arti);
        lore.add(ChatColor.RED + "Beğenmeme: " + ChatColor.WHITE + eksi);
        lore.add(ChatColor.GRAY + "Yorum sayısı: " + ChatColor.WHITE + yorumlar.getOrDefault(hedef, Collections.emptyList()).size());
        meta.setLore(lore);
        head.setItemMeta(meta);
        return head;
    }

    private List<String> sar(String metin, int genislik) {
        List<String> satirlar = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (String kelime : metin.split(" ")) {
            while (kelime.length() > genislik) {
                if (sb.length() > 0) { satirlar.add(sb.toString()); sb.setLength(0); }
                satirlar.add(kelime.substring(0, genislik));
                kelime = kelime.substring(genislik);
            }
            if (sb.length() + kelime.length() + (sb.length() > 0 ? 1 : 0) > genislik) {
                satirlar.add(sb.toString());
                sb.setLength(0);
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(kelime);
        }
        if (sb.length() > 0) satirlar.add(sb.toString());
        return satirlar;
    }

    /** PolisManager gibi dış sistemlerin (örn. Başkan Menüsü'ndeki "Beğeni/Etkileşim" köprü butonu) açabilmesi için
     *  özel anaMenu()'ye genel erişim sağlar. Etkileşim menüsü zaten polis hedeflerini kendi akışında hariç tutar
     *  (bkz. onSagTik), bu yüzden köprü butonu buraya özellikle polis/amir hedefleri için yönlendirme yapar. */
    public void menuAc(Player izleyen, UUID hedef) {
        anaMenu(izleyen, hedef);
    }

    // ------------------------------------------------------------------ menüler
    private void anaMenu(Player izleyen, UUID hedef) {
        EtkMenu holder = new EtkMenu(Tip.ANA, hedef);
        Inventory inv = Bukkit.createInventory(holder, 27, ChatColor.DARK_GREEN + "Oyuncu: " + adOf(hedef));
        holder.envanter = inv;
        inv.setItem(4, hedefKafasi(hedef));

        boolean kendi = izleyen.getUniqueId().equals(hedef);
        if (kendi) {
            inv.setItem(13, esya(Material.BOOK, ChatColor.AQUA + "" + ChatColor.BOLD + "Yorumlarım",
                    ChatColor.GRAY + "Sana bırakılan yorumları gör."));
        } else {
            Map<UUID, Integer> m = oylar.get(hedef);
            Integer benimOyum = m == null ? null : m.get(izleyen.getUniqueId());
            inv.setItem(SLOT_BEGEN, esya(Material.LIME_DYE, ChatColor.GREEN + "" + ChatColor.BOLD + "Beğen",
                    ChatColor.GRAY + "Bu oyuncuyu beğenirsin.",
                    benimOyum != null && benimOyum == 1 ? ChatColor.YELLOW + "► Şu an beğeniyorsun (tekrar basarsan geri alınır)" : ChatColor.DARK_GRAY + "Tıkla"));
            inv.setItem(SLOT_BEGENME, esya(Material.RED_DYE, ChatColor.RED + "" + ChatColor.BOLD + "Beğenme",
                    ChatColor.GRAY + "Bu oyuncuyu beğenmezsin.",
                    benimOyum != null && benimOyum == -1 ? ChatColor.YELLOW + "► Şu an beğenmiyorsun (tekrar basarsan geri alınır)" : ChatColor.DARK_GRAY + "Tıkla"));
            inv.setItem(SLOT_YORUM_BIRAK, esya(Material.WRITABLE_BOOK, ChatColor.GOLD + "" + ChatColor.BOLD + "Yorum Bırak",
                    ChatColor.GRAY + "Tıkla, sonra sohbete yorumunu yaz.",
                    ChatColor.GRAY + "Yorum herkese değil, bu oyuncunun profiline gider."));
            inv.setItem(SLOT_YORUM_GOR, esya(Material.BOOK, ChatColor.AQUA + "" + ChatColor.BOLD + "Yorumları Gör",
                    ChatColor.GRAY + "Bu oyuncuya bırakılan yorumlar."));
            inv.setItem(SLOT_PARA, esya(Material.GOLD_INGOT, ChatColor.YELLOW + "" + ChatColor.BOLD + "Para Gönder",
                    ChatColor.GRAY + "Banka hesabından bu oyuncunun hesabına para aktar.",
                    ChatColor.GRAY + "Tıkla, sonra sohbete miktarı yaz."));
            Player hedefOyuncu = Bukkit.getPlayer(hedef);
            if (baskanMi(izleyen) && hedefOyuncu != null && plugin.koyManager != null
                    && plugin.koyManager.cikarilamazNedeni(hedefOyuncu) == null) {
                inv.setItem(SLOT_CIKAR, esya(Material.BARRIER, ChatColor.DARK_RED + "" + ChatColor.BOLD + "Vatandaşlıktan Çıkar",
                        ChatColor.GRAY + "Oyuncu göçmen olur ve köyün dışına ışınlanır.",
                        ChatColor.RED + "Sadece Belediye Başkanı"));
            }
        }
        izleyen.openInventory(inv);
    }

    private void yorumMenu(Player izleyen, UUID hedef) {
        EtkMenu holder = new EtkMenu(Tip.YORUMLAR, hedef);
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.DARK_AQUA + "Yorumlar: " + adOf(hedef));
        holder.envanter = inv;

        List<Yorum> liste = new ArrayList<>(yorumlar.getOrDefault(hedef, Collections.emptyList()));
        Collections.reverse(liste); // en yeni önce
        SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        boolean yonetici = izleyen.hasPermission(YONET);
        int slot = 0;
        for (Yorum y : liste) {
            if (slot >= 45) break;
            List<String> lore = new ArrayList<>();
            for (String s : sar(y.metin, 34)) lore.add(ChatColor.WHITE + s);
            lore.add("");
            lore.add(ChatColor.DARK_GRAY + f.format(new Date(y.zaman)));
            if (yonetici || y.yazar.equals(izleyen.getUniqueId())) lore.add(ChatColor.RED + "Sağ tık: yorumu sil");
            ItemStack it = esya(Material.PAPER, ChatColor.YELLOW + y.yazarAd);
            ItemMeta meta = it.getItemMeta();
            meta.setLore(lore);
            it.setItemMeta(meta);
            inv.setItem(slot, it);
            holder.slotYorum.put(slot, y);
            slot++;
        }
        if (liste.isEmpty()) {
            inv.setItem(22, esya(Material.BARRIER, ChatColor.GRAY + "Henüz yorum yok"));
        }
        inv.setItem(SLOT_GERI, esya(Material.ARROW, ChatColor.YELLOW + "Geri"));
        izleyen.openInventory(inv);
    }

    private void onayMenu(Player baskan, Player hedef) {
        EtkMenu holder = new EtkMenu(Tip.ONAY, hedef.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, 27, ChatColor.DARK_RED + "Vatandaşlıktan çıkar?");
        holder.envanter = inv;
        inv.setItem(13, hedefKafasi(hedef.getUniqueId()));
        inv.setItem(SLOT_ONAY, esya(Material.RED_CONCRETE, ChatColor.RED + "" + ChatColor.BOLD + "Evet, vatandaşlıktan çıkar",
                ChatColor.GRAY + hedef.getName() + " göçmen olur."));
        inv.setItem(SLOT_VAZGEC, esya(Material.LIME_CONCRETE, ChatColor.GREEN + "" + ChatColor.BOLD + "Vazgeç"));
        baskan.openInventory(inv);
    }

    // ------------------------------------------------------------------ olaylar
    @EventHandler
    public void onSagTik(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Player)) return;
        Player izleyen = event.getPlayer();
        Player hedef = (Player) event.getRightClicked();
        if (izleyen.isSneaking()) return;
        if (izleyen.getInventory().getItemInMainHand().getType() != Material.AIR) return;
        if (hedef.hasMetadata("NPC")) return;
        if (izleyen.getUniqueId().equals(hedef.getUniqueId())) return;
        // Polis ve amirlere sağ tık: şikayet menüsü (PolisManager)
        if (polisMi(meslekOf(hedef))) return;

        // Polis/amir: sağ tık GBT kontrolü açar (GBT menüsünden oyuncu menüsüne ulaşılır)
        if (plugin.gbtManager != null && polisMi(meslekOf(izleyen))) {
            event.setCancelled(true);
            plugin.gbtManager.gbtAc(izleyen, hedef);
            return;
        }

        event.setCancelled(true);
        adKaydet(hedef);
        adKaydet(izleyen);
        anaMenu(izleyen, hedef.getUniqueId());
    }

    @EventHandler
    public void onTikla(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof EtkMenu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player p = (Player) event.getWhoClicked();
        EtkMenu menu = (EtkMenu) event.getView().getTopInventory().getHolder();
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
        ItemStack tiklanan = event.getCurrentItem();
        if (tiklanan == null || tiklanan.getType() == Material.AIR) return;
        UUID hedef = menu.hedef;

        switch (menu.tip) {
            case ANA:
                anaTikla(p, hedef, slot);
                break;
            case YORUMLAR:
                if (slot == SLOT_GERI) {
                    anaMenu(p, hedef);
                    return;
                }
                Yorum y = menu.slotYorum.get(slot);
                if (y != null && event.getClick() == ClickType.RIGHT) {
                    if (p.hasPermission(YONET) || y.yazar.equals(p.getUniqueId())) {
                        List<Yorum> liste = yorumlar.get(hedef);
                        if (liste != null && liste.remove(y)) {
                            if (liste.isEmpty()) yorumlar.remove(hedef);
                            kaydetPlanla();
                            p.sendMessage(ChatColor.YELLOW + "Yorum silindi.");
                        }
                        yorumMenu(p, hedef);
                    }
                }
                break;
            case ONAY:
                if (slot == SLOT_ONAY) {
                    p.closeInventory();
                    Player hp = Bukkit.getPlayer(hedef);
                    if (hp == null) {
                        p.sendMessage(ChatColor.RED + "Oyuncu artik oyunda değil.");
                    } else if (plugin.koyManager != null) {
                        plugin.koyManager.vatandasliktanCikar(p, hp);
                    }
                } else if (slot == SLOT_VAZGEC) {
                    anaMenu(p, hedef);
                }
                break;
            default:
                break;
        }
    }

    private void anaTikla(Player p, UUID hedef, int slot) {
        boolean kendi = p.getUniqueId().equals(hedef);
        if (kendi) {
            if (slot == 13) yorumMenu(p, hedef);
            return;
        }
        if (slot == SLOT_YORUM_GOR) {
            yorumMenu(p, hedef);
            return;
        }
        if (slot == SLOT_CIKAR) {
            Player hp = Bukkit.getPlayer(hedef);
            if (!baskanMi(p)) {
                p.closeInventory();
                p.sendMessage(ChatColor.RED + "Bunu sadece Belediye Başkanı yapabilir!");
                return;
            }
            if (hp == null) {
                p.closeInventory();
                p.sendMessage(ChatColor.RED + "Oyuncu artik oyunda değil.");
                return;
            }
            String neden = plugin.koyManager == null ? "Köy sistemi kapalı." : plugin.koyManager.cikarilamazNedeni(hp);
            if (neden != null) {
                p.closeInventory();
                p.sendMessage(ChatColor.RED + neden);
                return;
            }
            onayMenu(p, hp);
            return;
        }
        if (slot == SLOT_PARA) {
            String mk = meslekOf(p);
            p.closeInventory();
            if (mk.equalsIgnoreCase("mahkum")) {
                p.sendMessage(ChatColor.RED + "Hapisteyken para gönderemezsin.");
                return;
            }
            paraBekleyen.put(p.getUniqueId(), hedef);
            yorumBekleyen.remove(p.getUniqueId());
            double bak = plugin.bankaHesaplari.getOrDefault(p.getUniqueId(), 0.0);
            p.sendMessage(ChatColor.GOLD + "--- Para Gönder: " + ChatColor.YELLOW + adOf(hedef) + ChatColor.GOLD + " ---");
            p.sendMessage(ChatColor.GREEN + "Göndermek istediğin miktarı sohbete yaz. Banka bakiyen: " + ChatColor.YELLOW + "$" + String.format(Locale.US, "%.2f", bak));
            p.sendMessage(ChatColor.GRAY + "(Vazgeçmek için 'iptal' yaz)");
            return;
        }
        if (slot != SLOT_BEGEN && slot != SLOT_BEGENME && slot != SLOT_YORUM_BIRAK) return;

        String neden = katilamazNedeni(p);
        if (neden != null) {
            p.closeInventory();
            p.sendMessage(ChatColor.RED + neden);
            return;
        }

        if (slot == SLOT_YORUM_BIRAK) {
            p.closeInventory();
            paraBekleyen.remove(p.getUniqueId());
            yorumBekleyen.put(p.getUniqueId(), hedef);
            p.sendMessage(ChatColor.GOLD + "--- Yorum: " + ChatColor.YELLOW + adOf(hedef) + ChatColor.GOLD + " ---");
            p.sendMessage(ChatColor.GREEN + "Yorumunu sohbete yaz (" + yorumMin() + "-" + yorumMax() + " karakter). Herkese gitmez, o oyuncunun profiline kaydedilir.");
            p.sendMessage(ChatColor.GRAY + "(Vazgeçmek için 'iptal' yaz)");
            return;
        }

        int deger = slot == SLOT_BEGEN ? 1 : -1;
        Map<UUID, Integer> m = oylar.computeIfAbsent(hedef, k -> new HashMap<>());
        Integer eski = m.get(p.getUniqueId());
        String ad = adOf(hedef);
        if (eski != null && eski == deger) {
            m.remove(p.getUniqueId());
            if (m.isEmpty()) oylar.remove(hedef);
            p.sendMessage(ChatColor.YELLOW + ad + " için verdiğin oy geri alındı.");
        } else {
            m.put(p.getUniqueId(), deger);
            p.sendMessage(deger == 1
                    ? ChatColor.GREEN + ad + " oyuncusunu beğendin."
                    : ChatColor.RED + ad + " oyuncusunu beğenmedin.");
        }
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        kaydetPlanla();
        anaMenu(p, hedef);
    }

    @EventHandler
    public void onSurukle(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EtkMenu) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSohbet(AsyncChatEvent event) {
        Player p = event.getPlayer();
        boolean para = paraBekleyen.containsKey(p.getUniqueId());
        if (!para && !yorumBekleyen.containsKey(p.getUniqueId())) return;
        event.setCancelled(true);
        final String mesaj = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            if (paraBekleyen.containsKey(p.getUniqueId())) paraIsle(p, mesaj);
            else yorumIsle(p, mesaj);
        });
    }

    private void paraIsle(Player p, String ham) {
        UUID hedef = paraBekleyen.remove(p.getUniqueId());
        if (hedef == null) return;
        if (ham.equalsIgnoreCase("iptal")) {
            p.sendMessage(ChatColor.YELLOW + "Para gönderme iptal edildi.");
            return;
        }
        double miktar;
        try {
            miktar = Double.parseDouble(ham.replace(",", ".").replace("$", "").trim());
        } catch (NumberFormatException e) {
            p.sendMessage(ChatColor.RED + "Geçerli bir miktar yazmalısın. Tekrar denemek için menüden yeniden seç.");
            return;
        }
        if (Double.isNaN(miktar) || Double.isInfinite(miktar)) {
            p.sendMessage(ChatColor.RED + "Geçersiz miktar.");
            return;
        }
        miktar = Math.round(miktar * 100.0) / 100.0;
        if (miktar < PARA_MIN) {
            p.sendMessage(ChatColor.RED + "En az $" + (int) PARA_MIN + " gönderebilirsin.");
            return;
        }
        if (meslekOf(p).equalsIgnoreCase("mahkum")) {
            p.sendMessage(ChatColor.RED + "Hapisteyken para gönderemezsin.");
            return;
        }
        if (p.getUniqueId().equals(hedef)) return;
        double bakiye = plugin.bankaHesaplari.getOrDefault(p.getUniqueId(), 0.0);
        if (bakiye < miktar) {
            p.sendMessage(ChatColor.RED + "Banka hesabında yeterli bakiye yok! Bakiyen: $" + String.format(Locale.US, "%.2f", bakiye));
            return;
        }
        plugin.bankaHesaplari.put(p.getUniqueId(), bakiye - miktar);
        plugin.bankaHesaplari.merge(hedef, miktar, Double::sum);
        plugin.veriKaydet();

        String ad = adOf(hedef);
        String yazi = "$" + String.format(Locale.US, "%.2f", miktar);
        p.sendMessage(ChatColor.GREEN + ad + " oyuncusuna " + ChatColor.YELLOW + yazi + ChatColor.GREEN + " gönderildi.");
        Player hp = Bukkit.getPlayer(hedef);
        if (hp != null) {
            hp.sendMessage(ChatColor.GOLD + "[Banka] " + ChatColor.YELLOW + p.getName() + ChatColor.GREEN + " sana " + ChatColor.YELLOW + yazi + ChatColor.GREEN + " gönderdi.");
            hp.playSound(hp.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        }
        plugin.getLogger().info("[PARA] " + p.getName() + " -> " + ad + " " + yazi);
        if (plugin.alarmManager != null) plugin.alarmManager.transfer(p, hedef, ad, miktar);
    }

    private void yorumIsle(Player p, String ham) {
        UUID hedef = yorumBekleyen.remove(p.getUniqueId());
        if (hedef == null) return;
        if (ham.equalsIgnoreCase("iptal")) {
            p.sendMessage(ChatColor.YELLOW + "Yorum iptal edildi.");
            return;
        }
        String neden = katilamazNedeni(p);
        if (neden != null) {
            p.sendMessage(ChatColor.RED + neden);
            return;
        }
        String metin = ChatColor.stripColor(ham.replace('§', ' ')).replaceAll("\\s+", " ").trim();
        if (metin.length() < yorumMin()) {
            p.sendMessage(ChatColor.RED + "Yorum en az " + yorumMin() + " karakter olmalı. Tekrar denemek için menüden yeniden seç.");
            return;
        }
        if (metin.length() > yorumMax()) {
            p.sendMessage(ChatColor.RED + "Yorum en fazla " + yorumMax() + " karakter olabilir (" + metin.length() + " yazdın). Tekrar denemek için menüden yeniden seç.");
            return;
        }
        String anahtar = p.getUniqueId() + ">" + hedef;
        long simdi = System.currentTimeMillis();
        Long son = sonYorum.get(anahtar);
        if (son != null && simdi - son < yorumBekleme() * 1000L) {
            long kalan = (yorumBekleme() * 1000L - (simdi - son)) / 1000L + 1;
            p.sendMessage(ChatColor.RED + "Bu oyuncuya tekrar yorum bırakmak için " + kalan + " saniye beklemelisin.");
            return;
        }
        sonYorum.put(anahtar, simdi);

        List<Yorum> liste = yorumlar.computeIfAbsent(hedef, k -> new ArrayList<>());
        liste.add(new Yorum(p.getUniqueId(), p.getName(), metin, simdi));
        while (liste.size() > enFazlaYorum()) liste.remove(0);
        kaydetPlanla();

        String ad = adOf(hedef);
        p.sendMessage(ChatColor.GREEN + ad + " oyuncusuna yorumun kaydedildi: " + ChatColor.GRAY + "\"" + metin + "\"");
        Player hp = Bukkit.getPlayer(hedef);
        if (hp != null) {
            hp.sendMessage(ChatColor.GOLD + "[Yorum] " + ChatColor.YELLOW + p.getName() + ChatColor.GRAY + " sana bir yorum bıraktı. " + ChatColor.DARK_GRAY + "(/profil ile gör)");
        }
    }

    @EventHandler
    public void onGiris(PlayerJoinEvent event) {
        adKaydet(event.getPlayer());
    }

    @EventHandler
    public void onCikis(PlayerQuitEvent event) {
        yorumBekleyen.remove(event.getPlayer().getUniqueId());
        paraBekleyen.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ liderlik tablosu
    private void liderlikGonder(CommandSender sender) {
        // hedef -> (begeni, begenmeme) sayilari
        Map<UUID, int[]> sayac = new HashMap<>();
        for (Map.Entry<UUID, Map<UUID, Integer>> e : oylar.entrySet()) {
            int begeni = 0, begenmeme = 0;
            for (int v : e.getValue().values()) {
                if (v > 0) begeni++; else if (v < 0) begenmeme++;
            }
            sayac.put(e.getKey(), new int[]{begeni, begenmeme});
        }
        List<Map.Entry<UUID, int[]>> enCokBegeni = new ArrayList<>(sayac.entrySet());
        enCokBegeni.removeIf(e -> e.getValue()[0] <= 0);
        enCokBegeni.sort((a, b) -> b.getValue()[0] - a.getValue()[0]);
        List<Map.Entry<UUID, int[]>> enCokBegenmeme = new ArrayList<>(sayac.entrySet());
        enCokBegenmeme.removeIf(e -> e.getValue()[1] <= 0);
        enCokBegenmeme.sort((a, b) -> b.getValue()[1] - a.getValue()[1]);

        sender.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "--- En Çok Beğenilenler ---");
        if (enCokBegeni.isEmpty()) sender.sendMessage(ChatColor.GRAY + "Henüz kimse beğenilmemiş.");
        int sira = 1;
        for (Map.Entry<UUID, int[]> e : enCokBegeni) {
            if (sira > 10) break;
            sender.sendMessage(ChatColor.YELLOW + "" + sira + ". " + ChatColor.WHITE + adOf(e.getKey()) + ChatColor.GREEN + " (" + e.getValue()[0] + " beğeni)");
            sira++;
        }
        sender.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "--- En Çok Beğenilmeyenler ---");
        if (enCokBegenmeme.isEmpty()) sender.sendMessage(ChatColor.GRAY + "Henüz kimse beğenilmemiş (olumsuz).");
        sira = 1;
        for (Map.Entry<UUID, int[]> e : enCokBegenmeme) {
            if (sira > 10) break;
            sender.sendMessage(ChatColor.YELLOW + "" + sira + ". " + ChatColor.WHITE + adOf(e.getKey()) + ChatColor.RED + " (" + e.getValue()[1] + " beğenmeme)");
            sira++;
        }
    }

    // ------------------------------------------------------------------ komut
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("topbegeni")) {
            liderlikGonder(sender);
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("Bu komut oyun içinden kullanılır.");
            return true;
        }
        Player p = (Player) sender;
        UUID hedef = p.getUniqueId();
        if (args.length > 0) {
            Player online = Bukkit.getPlayerExact(args[0]);
            if (online != null) {
                hedef = online.getUniqueId();
            } else {
                UUID bulunan = null;
                for (Map.Entry<UUID, String> e : adlar.entrySet()) {
                    if (e.getValue().equalsIgnoreCase(args[0])) { bulunan = e.getKey(); break; }
                }
                if (bulunan == null) {
                    p.sendMessage(ChatColor.RED + "Böyle bir oyuncu bulunamadı: " + args[0]);
                    return true;
                }
                hedef = bulunan;
            }
        }
        adKaydet(p);
        if (hedef.equals(p.getUniqueId())) anaMenu(p, hedef);
        else yorumMenu(p, hedef);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> sonuc = new ArrayList<>();
        if (args.length == 1) {
            String on = args[0].toLowerCase(Locale.ROOT);
            for (String ad : adlar.values()) {
                if (ad.toLowerCase(Locale.ROOT).startsWith(on)) sonuc.add(ad);
            }
        }
        return sonuc;
    }
}
