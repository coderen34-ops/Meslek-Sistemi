package me.mesleksistemi.gorev;

import me.mesleksistemi.MeslekSistemi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Görev (quest) sistemi - Entry #4 / Faz 2, madde 5: "temel altyapı" (GorevTuru enum,
 * NPC entegrasyonu, ödül). Changelog'daki zengin tasarım (görev panosu, zincirler,
 * çeşitli görev türleri - ZİYARET_ET, SATIŞ_YAP, SÖZLEŞME_TAMAMLA, TURNUVAYA_KATIL vb.)
 * ileride buraya yeni GorevTanim + yeni tür desteği eklenerek büyütülecek. Bu ilk sürüm
 * TOPLA, OLDUR ve KAZAN türlerini uçtan uca çalışır şekilde kurar; diğer türler enum'da
 * yer tutucu olarak var ama henüz işlenmiyorlar (completeDeneTamamla'da yoksayılırlar -
 * kazayla ödül verilmez, sadece henüz desteklenmediği için her zaman "tamamlanmadı" döner).
 *
 * Kurye meslek sistemi (Faz 2, madde 6) bu altyapının üzerine inşa edilecek.
 *
 * Veri güvenliği: PazarManager/BasarimManager/BountyManager'daki aynı veriYuklendi
 * boolean-guard kalıbı burada da uygulanıyor.
 */
public class GorevManager implements Listener, CommandExecutor {

    public enum GorevTuru { TOPLA, OLDUR, KAZAN, TESLIM_ET, ZIYARET_ET, SATIS_YAP, SOZLESME_TAMAMLA, TURNUVAYA_KATIL }

    public static class GorevTanim {
        public final String id;
        public final String ad;
        public final String aciklama;
        public final GorevTuru tur;
        public final Material hedefMateryal; // TOPLA/OLDUR icin ilgili esya/varlik materyali (OLDUR icin yakin materyal karsiligi kullanilir)
        public final EntityType hedefVarlik;  // OLDUR icin
        public final int hedefMiktar;         // TOPLA: esya adedi, OLDUR: oldurme adedi
        public final double hedefKazanc;      // KAZAN: hedeflenen net kazanc
        public final double odul;
        public final String zorluk;

        private GorevTanim(String id, String ad, String aciklama, GorevTuru tur, Material hedefMateryal,
                            EntityType hedefVarlik, int hedefMiktar, double hedefKazanc, double odul, String zorluk) {
            this.id = id; this.ad = ad; this.aciklama = aciklama; this.tur = tur;
            this.hedefMateryal = hedefMateryal; this.hedefVarlik = hedefVarlik;
            this.hedefMiktar = hedefMiktar; this.hedefKazanc = hedefKazanc; this.odul = odul; this.zorluk = zorluk;
        }

        static GorevTanim topla(String id, String ad, String aciklama, Material mat, int miktar, double odul, String zorluk) {
            return new GorevTanim(id, ad, aciklama, GorevTuru.TOPLA, mat, null, miktar, 0, odul, zorluk);
        }

        static GorevTanim oldur(String id, String ad, String aciklama, EntityType varlik, int miktar, double odul, String zorluk) {
            return new GorevTanim(id, ad, aciklama, GorevTuru.OLDUR, null, varlik, miktar, 0, odul, zorluk);
        }

        static GorevTanim kazan(String id, String ad, String aciklama, double hedefKazanc, double odul, String zorluk) {
            return new GorevTanim(id, ad, aciklama, GorevTuru.KAZAN, null, null, 0, hedefKazanc, odul, zorluk);
        }
    }

    // İlk görev seti (temel altyapıyı kanıtlayan küçük bir havuz) - Başarım'ın Faz 1'deki
    // "tek kategoriyle başla" yaklaşımıyla aynı mantık; diğer türler/görevler ileride eklenecek.
    public static final List<GorevTanim> TUM_GOREVLER = List.of(
            GorevTanim.topla("odun_toplama_1", "Oduncu Çırağı", "32 adet meşe kütüğü topla ve Görev Panosu'na teslim et.", Material.OAK_LOG, 32, 300.0, "Kolay"),
            GorevTanim.topla("tasci_1", "Taş Ocağı", "64 adet taş topla ve Görev Panosu'na teslim et.", Material.COBBLESTONE, 64, 250.0, "Kolay"),
            GorevTanim.oldur("zombi_avi_1", "Gece Nöbeti", "10 zombi öldür.", EntityType.ZOMBIE, 10, 400.0, "Orta"),
            GorevTanim.oldur("iskelet_avi_1", "Kemik Avcısı", "15 iskelet öldür.", EntityType.SKELETON, 15, 500.0, "Orta"),
            GorevTanim.kazan("kazanc_1", "Girişimci", "Toplam $5.000 net kazanç elde et (ticaret, maaş, satış - her yoldan sayılır).", 5000.0, 200.0, "Orta")
    );

    private static final Map<String, GorevTanim> TANIM_INDEX = new HashMap<>();
    static {
        for (GorevTanim g : TUM_GOREVLER) TANIM_INDEX.put(g.id, g);
    }

    private static class Ilerleme {
        int sayac = 0;             // OLDUR icin
        double baslangicBakiye = 0; // KAZAN icin - gorev kabul edildigindeki bakiye
    }

    private final MeslekSistemi plugin;
    private final NamespacedKey gorevNpcKey;
    private final NamespacedKey gorevIdKey;
    private boolean veriYuklendi = false;

    private final Map<UUID, Map<String, Ilerleme>> aktifGorevler = new HashMap<>();
    private final Map<UUID, Set<String>> tamamlananGorevler = new HashMap<>();

    public GorevManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.gorevNpcKey = new NamespacedKey(plugin, "gorev_npc");
        this.gorevIdKey = new NamespacedKey(plugin, "gorev_id");
        veriYukle();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("gorevnpc")) {
            if (!player.hasPermission("meslek.admin") && !player.isOp()) {
                player.sendMessage(ChatColor.RED + "Bu komutu kullanma yetkin yok.");
                return true;
            }
            if (args.length < 1) {
                player.sendMessage(ChatColor.RED + "Kullanım: /gorevnpc kur | sil");
                return true;
            }
            if (args[0].equalsIgnoreCase("kur")) {
                Villager npc = (Villager) player.getWorld().spawnEntity(player.getLocation(), EntityType.VILLAGER);
                npc.setAI(false); npc.setInvulnerable(true); npc.setCollidable(false);
                npc.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + "Görev Panosu");
                npc.setCustomNameVisible(true);
                npc.setProfession(Villager.Profession.CARTOGRAPHER);
                npc.getPersistentDataContainer().set(gorevNpcKey, PersistentDataType.BYTE, (byte) 1);
                player.sendMessage(ChatColor.GREEN + "Görev Panosu NPC'si kuruldu!");
            } else if (args[0].equalsIgnoreCase("sil")) {
                int silinen = 0;
                for (Entity entity : player.getNearbyEntities(5, 5, 5)) {
                    if (entity instanceof Villager && entity.getPersistentDataContainer().has(gorevNpcKey, PersistentDataType.BYTE)) {
                        entity.remove(); silinen++;
                    }
                }
                player.sendMessage(silinen > 0 ? ChatColor.GREEN + "" + silinen + " Görev Panosu NPC'si silindi." : ChatColor.RED + "Yakında Görev Panosu NPC'si bulunamadı.");
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("gorevlerim")) {
            if (!veriYuklendi) {
                player.sendMessage(ChatColor.RED + "Sistem henüz hazır değil, birazdan tekrar dene.");
                return true;
            }
            Map<String, Ilerleme> aktif = aktifGorevler.getOrDefault(player.getUniqueId(), Map.of());
            Set<String> tamam = tamamlananGorevler.getOrDefault(player.getUniqueId(), Set.of());
            player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "=== Görevlerin ===");
            if (aktif.isEmpty()) {
                player.sendMessage(ChatColor.GRAY + "Aktif görevin yok. Görev Panosu'na gidip yeni görev kabul edebilirsin.");
            } else {
                for (Map.Entry<String, Ilerleme> e : aktif.entrySet()) {
                    GorevTanim g = TANIM_INDEX.get(e.getKey());
                    if (g == null) continue;
                    player.sendMessage(ChatColor.YELLOW + "- " + g.ad + ChatColor.GRAY + " (" + ilerlemeMetni(g, e.getValue()) + ")");
                }
            }
            player.sendMessage(ChatColor.DARK_GRAY + "Tamamlanan görev sayısı: " + tamam.size() + "/" + TUM_GOREVLER.size());
            return true;
        }
        return false;
    }

    private String ilerlemeMetni(GorevTanim g, Ilerleme i) {
        switch (g.tur) {
            case TOPLA:
                return "envanterinde kontrol edilecek, hedef: " + g.hedefMiktar + "x " + guzelAd(g.hedefMateryal);
            case OLDUR:
                return i.sayac + "/" + g.hedefMiktar + " " + guzelAd(g.hedefVarlik);
            case KAZAN:
                return "ilerleme panoda kontrol edilir, hedef: $" + fmt(g.hedefKazanc);
            default:
                return "henüz desteklenmiyor";
        }
    }

    private String guzelAd(Material m) { return m == null ? "" : m.name().toLowerCase(Locale.ROOT).replace('_', ' '); }
    private String guzelAd(EntityType e) { return e == null ? "" : e.name().toLowerCase(Locale.ROOT).replace('_', ' '); }
    private String fmt(double d) { return String.format(Locale.US, "%.2f", d); }

    @EventHandler
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Villager)) return;
        Villager npc = (Villager) event.getRightClicked();
        if (!npc.getPersistentDataContainer().has(gorevNpcKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        if (!veriYuklendi) {
            event.getPlayer().sendMessage(ChatColor.RED + "Sistem henüz hazır değil, birazdan tekrar dene.");
            return;
        }
        openGorevGUI(event.getPlayer());
    }

    private static final String GUI_BASLIK = ChatColor.GOLD + "Görev Panosu";

    private void openGorevGUI(Player player) {
        int size = Math.min(54, ((TUM_GOREVLER.size() / 9) + 1) * 9);
        Inventory gui = Bukkit.createInventory(null, size, GUI_BASLIK);
        Set<String> tamam = tamamlananGorevler.getOrDefault(player.getUniqueId(), Set.of());
        Map<String, Ilerleme> aktif = aktifGorevler.getOrDefault(player.getUniqueId(), Map.of());

        for (GorevTanim g : TUM_GOREVLER) {
            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();
            boolean bitti = tamam.contains(g.id);
            boolean aktifMi = aktif.containsKey(g.id);
            meta.setDisplayName((bitti ? ChatColor.DARK_GRAY : ChatColor.YELLOW) + g.ad + ChatColor.GRAY + " [" + g.zorluk + "]");
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.WHITE + g.aciklama);
            lore.add(ChatColor.GREEN + "Ödül: $" + fmt(g.odul));
            if (bitti) {
                lore.add(ChatColor.DARK_GRAY + "✔ Tamamlandı");
            } else if (aktifMi) {
                lore.add(ChatColor.AQUA + ilerlemeMetni(g, aktif.get(g.id)));
                lore.add(ChatColor.GREEN + "Tıkla: teslim etmeyi dene");
            } else {
                lore.add(ChatColor.YELLOW + "Tıkla: görevi kabul et");
            }
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(gorevIdKey, PersistentDataType.STRING, g.id);
            item.setItemMeta(meta);
            gui.addItem(item);
        }
        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(GUI_BASLIK)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() != Material.PAPER || !clicked.hasItemMeta()) return;
        String gorevId = clicked.getItemMeta().getPersistentDataContainer().get(gorevIdKey, PersistentDataType.STRING);
        if (gorevId == null) return;
        GorevTanim secilen = TANIM_INDEX.get(gorevId);
        if (secilen == null) return;
        if (!veriYuklendi) {
            player.sendMessage(ChatColor.RED + "Sistem henüz hazır değil, birazdan tekrar dene.");
            return;
        }

        Set<String> tamam = tamamlananGorevler.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
        if (tamam.contains(secilen.id)) {
            player.sendMessage(ChatColor.GRAY + "Bu görevi zaten tamamladın.");
            return;
        }

        Map<String, Ilerleme> aktif = aktifGorevler.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        if (!aktif.containsKey(secilen.id)) {
            // GÖREV KABUL
            Ilerleme yeni = new Ilerleme();
            if (secilen.tur == GorevTuru.KAZAN) {
                yeni.baslangicBakiye = plugin.bankaHesaplari.getOrDefault(player.getUniqueId(), 0.0);
            }
            aktif.put(secilen.id, yeni);
            veriKaydet();
            player.sendMessage(ChatColor.GREEN + "Görev kabul edildi: " + secilen.ad);
            player.closeInventory();
            return;
        }

        // GÖREV TAMAMLAMA DENEMESİ
        denemeTamamla(player, secilen, aktif.get(secilen.id));
    }

    private void denemeTamamla(Player player, GorevTanim g, Ilerleme ilerleme) {
        boolean tamam = false;
        switch (g.tur) {
            case TOPLA: {
                int envanterdeki = sayEsya(player, g.hedefMateryal);
                if (envanterdeki >= g.hedefMiktar) {
                    esyaCikar(player, g.hedefMateryal, g.hedefMiktar);
                    tamam = true;
                } else {
                    player.sendMessage(ChatColor.RED + "Yeterli eşyan yok! (" + envanterdeki + "/" + g.hedefMiktar + " " + guzelAd(g.hedefMateryal) + ")");
                }
                break;
            }
            case OLDUR: {
                if (ilerleme.sayac >= g.hedefMiktar) {
                    tamam = true;
                } else {
                    player.sendMessage(ChatColor.RED + "Henüz yeterli öldürme yapmadın! (" + ilerleme.sayac + "/" + g.hedefMiktar + ")");
                }
                break;
            }
            case KAZAN: {
                double guncelBakiye = plugin.bankaHesaplari.getOrDefault(player.getUniqueId(), 0.0);
                double kazanc = guncelBakiye - ilerleme.baslangicBakiye;
                if (kazanc >= g.hedefKazanc) {
                    tamam = true;
                } else {
                    player.sendMessage(ChatColor.RED + "Henüz hedefe ulaşmadın! (Şu ana kadarki kazanç: $" + fmt(Math.max(0, kazanc)) + "/$" + fmt(g.hedefKazanc) + ")");
                }
                break;
            }
            default:
                player.sendMessage(ChatColor.RED + "Bu görev türü bu sürümde henüz desteklenmiyor.");
                return;
        }

        if (!tamam) return;

        // Ödül ver - idempotent: aktifGorevler'den kaldırılıp tamamlananGorevler'e eklenmesi tek bir
        // senkron bloktaydı (ana thread'de calisan InventoryClickEvent), bu yüzden cift odeme riski yok.
        Map<String, Ilerleme> aktif = aktifGorevler.get(player.getUniqueId());
        if (aktif != null) aktif.remove(g.id);
        tamamlananGorevler.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(g.id);

        double bakiye = plugin.bankaHesaplari.getOrDefault(player.getUniqueId(), 0.0);
        plugin.bankaHesaplari.put(player.getUniqueId(), bakiye + g.odul);
        plugin.veriKaydet();
        veriKaydet();

        if (plugin.basarimManager != null) plugin.basarimManager.sayacArtir(player.getUniqueId(), "gorev", 1);
        plugin.ekonomiLog("gorev-tamamlandi", player.getName(), "Görev tamamlandı: " + g.id + " (" + g.ad + ") - ödül $" + fmt(g.odul));

        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "✔ GÖREV TAMAMLANDI: " + ChatColor.YELLOW + g.ad);
        player.sendMessage(ChatColor.GREEN + "Ödül: $" + fmt(g.odul) + " hesabına yatırıldı.");
        player.sendMessage("");
        try {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        } catch (Exception ignored) {
        }
        player.closeInventory();
    }

    private int sayEsya(Player player, Material mat) {
        int toplam = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == mat) toplam += it.getAmount();
        }
        return toplam;
    }

    private void esyaCikar(Player player, Material mat, int miktar) {
        int kalan = miktar;
        ItemStack[] icerik = player.getInventory().getContents();
        for (int i = 0; i < icerik.length && kalan > 0; i++) {
            ItemStack it = icerik[i];
            if (it == null || it.getType() != mat) continue;
            int cikarilacak = Math.min(kalan, it.getAmount());
            it.setAmount(it.getAmount() - cikarilacak);
            kalan -= cikarilacak;
            if (it.getAmount() <= 0) player.getInventory().setItem(i, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!veriYuklendi) return;
        LivingEntity olen = event.getEntity();
        Player katil = olen.getKiller();
        if (katil == null) return;

        Map<String, Ilerleme> aktif = aktifGorevler.get(katil.getUniqueId());
        if (aktif == null || aktif.isEmpty()) return;

        boolean degisti = false;
        for (Map.Entry<String, Ilerleme> e : aktif.entrySet()) {
            GorevTanim g = TANIM_INDEX.get(e.getKey());
            if (g == null || g.tur != GorevTuru.OLDUR) continue;
            if (g.hedefVarlik == olen.getType() && e.getValue().sayac < g.hedefMiktar) {
                e.getValue().sayac++;
                degisti = true;
                if (e.getValue().sayac >= g.hedefMiktar) {
                    katil.sendMessage(ChatColor.GREEN + "[Görev] \"" + g.ad + "\" hedefine ulaştın! Görev Panosu'na gidip teslim edebilirsin.");
                }
            }
        }
        if (degisti) veriKaydet();
    }

    // ---- Veri güvenliği: standVeriYuklendi kalıbının aynısı ----

    public void veriKaydet() {
        if (!veriYuklendi) {
            plugin.getLogger().warning("[Gorev] veriKaydet() veriYukle() tamamlanmadan çağrıldı, atlandı (veri kaybını önlemek için).");
            return;
        }
        plugin.getConfig().set("gorevler_aktif", null);
        for (Map.Entry<UUID, Map<String, Ilerleme>> oyuncu : aktifGorevler.entrySet()) {
            if (oyuncu.getValue().isEmpty()) continue;
            for (Map.Entry<String, Ilerleme> giris : oyuncu.getValue().entrySet()) {
                String yol = "gorevler_aktif." + oyuncu.getKey() + "." + giris.getKey();
                plugin.getConfig().set(yol + ".sayac", giris.getValue().sayac);
                plugin.getConfig().set(yol + ".baslangicBakiye", giris.getValue().baslangicBakiye);
            }
        }
        for (Map.Entry<UUID, Set<String>> oyuncu : tamamlananGorevler.entrySet()) {
            plugin.getConfig().set("gorevler_tamamlanan." + oyuncu.getKey(), new ArrayList<>(oyuncu.getValue()));
        }
        plugin.saveConfig();
    }

    public void veriYukle() {
        try {
            aktifGorevler.clear();
            tamamlananGorevler.clear();

            ConfigurationSection aktifSec = plugin.getConfig().getConfigurationSection("gorevler_aktif");
            if (aktifSec != null) {
                for (String uuidStr : aktifSec.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        ConfigurationSection oyuncuSec = aktifSec.getConfigurationSection(uuidStr);
                        Map<String, Ilerleme> map = new HashMap<>();
                        if (oyuncuSec != null) {
                            for (String gorevId : oyuncuSec.getKeys(false)) {
                                try {
                                    ConfigurationSection g = oyuncuSec.getConfigurationSection(gorevId);
                                    Ilerleme i = new Ilerleme();
                                    i.sayac = g.getInt("sayac", 0);
                                    i.baslangicBakiye = g.getDouble("baslangicBakiye", 0.0);
                                    map.put(gorevId, i);
                                } catch (Exception innerEx) {
                                    plugin.getLogger().warning("[Gorev] " + uuidStr + "/" + gorevId + " ilerlemesi okunamadı, atlandı: " + innerEx.getMessage());
                                }
                            }
                        }
                        aktifGorevler.put(uuid, map);
                    } catch (Exception innerEx) {
                        plugin.getLogger().warning("[Gorev] " + uuidStr + " için aktif görev verisi okunamadı, atlandı: " + innerEx.getMessage());
                    }
                }
            }

            ConfigurationSection tamamSec = plugin.getConfig().getConfigurationSection("gorevler_tamamlanan");
            if (tamamSec != null) {
                for (String uuidStr : tamamSec.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        List<String> liste = plugin.getConfig().getStringList("gorevler_tamamlanan." + uuidStr);
                        tamamlananGorevler.put(uuid, new HashSet<>(liste));
                    } catch (Exception innerEx) {
                        plugin.getLogger().warning("[Gorev] " + uuidStr + " için tamamlanan görev verisi okunamadı, atlandı: " + innerEx.getMessage());
                    }
                }
            }

            veriYuklendi = true;
        } catch (Exception e) {
            plugin.getLogger().severe("[Gorev] veriYukle() sırasında hata, veriYuklendi=false kalacak (kayıt atlanacak): " + e.getMessage());
            veriYuklendi = false;
        }
    }
}
