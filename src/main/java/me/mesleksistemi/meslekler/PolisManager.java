package me.mesleksistemi.meslekler;

import me.mesleksistemi.MeslekSistemi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

public class PolisManager implements Listener, CommandExecutor {

    private final MeslekSistemi plugin;
    
    private final NamespacedKey copKey;
    private final NamespacedKey modKey;
    private final NamespacedKey sikayetNpcKey;
    private final NamespacedKey davaDosyasiKey;
    
    public HashMap<String, Location> jailCells = new HashMap<>();
    public HashMap<UUID, Integer> jailedPlayers = new HashMap<>();
    private final HashMap<UUID, BukkitRunnable> jailTasks = new HashMap<>();
    public HashMap<UUID, Location> preJailLocations = new HashMap<>();
    
    // İŞTE YENİ HAFIZAMIZ: Oyuncu hapse girmeden önceki mesleğini burada tutuyoruz.
    public HashMap<UUID, String> preJailRoles = new HashMap<>();

    // Mahkumların hapse girerken geçici olarak el konulan eşyaları (ceza bitince iade edilir)
    private final HashMap<UUID, ItemStack[]> elKonulanEsyalar = new HashMap<>();

    private final HashMap<UUID, Long> dropConfirmations = new HashMap<>();
    
    public Location amirKursuKonumu = null;
    public HashMap<UUID, Sikayet> aktifSikayetler = new HashMap<>();
    public HashSet<String> arananOyuncular = new HashSet<>();
    
    private final java.util.concurrent.ConcurrentHashMap<UUID, Integer> sikayetAdimi = new java.util.concurrent.ConcurrentHashMap<>(); 
    private final java.util.concurrent.ConcurrentHashMap<UUID, String> geciciSikayetHedef = new java.util.concurrent.ConcurrentHashMap<>(); 
    
    // Belediye başkanının sağ tık menülerinde seçtiği polis/amir (başkan -> hedef)
    private final java.util.concurrent.ConcurrentHashMap<UUID, UUID> menuHedefleri = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String BASKAN_MENU = ChatColor.DARK_BLUE + "Başkan İşlemleri";
    private static final String RUTBE_MENU = ChatColor.GOLD + "Rütbe İşlemleri";

    // Copla art arda hapse atmayı (taciz) önlemek için: oyuncu serbest kaldıktan sonra kısa bir süre
    // tekrar copla hapse atılamaz. Ancak bu sürede birine vurmuş/birini öldürmüş ya da hakkında
    // şikayet (arananOyuncular) varsa bu koruma devre dışı kalır, çünkü artık meşru bir sebep var.
    private final java.util.concurrent.ConcurrentHashMap<UUID, Long> sonSalmaZamani = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<UUID, Long> sonSaldirganlikZamani = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long COP_COOLDOWN_MS = 3 * 60 * 1000L;

    // GENEL KURAL: Hakkında şikayet/arama olmayan, kimseyi öldürmemiş/bayıltmamış masum bir vatandaş
    // copla hapse atılamaz, sadece uyarı alır. "Öldürmüş" sayılması için cinayetin üzerinden bu kadar
    // zaman geçmemiş olmalı (sonsuza dek "meşru sebep" sayılmasın diye). Bayıltma durumu ise kurban
    // tedavi oluna kadar kendiliğinden geçerli kalır (SaglikManager.birisiniBayginaDusurduMu).
    private final java.util.concurrent.ConcurrentHashMap<UUID, Long> sonOlumZamani = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long GENEL_SEBEP_PENCERESI_MS = 20 * 60 * 1000L;

    // ===== BASKIN (RAID) SİSTEMİ =====
    // Bir claim'de polis/amir ile sivil arasında PVP yaşandığı an o claim için "baskın" başlar.
    // Birkaç dakika kavga olmazsa kendiliğinden sona erer. Key: GriefPrevention Claim ID.
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> baskinSonAktivite = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long BASKIN_HAREKETSIZLIK_MS = 3 * 60 * 1000L;

    /** Aranan bir sivilin, baskın atıldığı claim içinde polise karşı kendini savunma süresi.
     *  5 dakika boyunca aynı claim içinde hayatta/baygın olmadan dayanırsa arananlığı düşer. */
    private static class SavunmaDurumu {
        final long claimId;
        final long baslangic;
        SavunmaDurumu(long claimId, long baslangic) { this.claimId = claimId; this.baslangic = baslangic; }
    }
    private final java.util.concurrent.ConcurrentHashMap<UUID, SavunmaDurumu> savunmaDurumlari = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SAVUNMA_SURESI_MS = 5 * 60 * 1000L;

    /** Baskın sırasında bayılan polis/amirler: normal tedavi yöntemleri (bandaj/adrenalin/doktor/
     *  ambulans/hastane NPC'si) bunlar üzerinde işe yaramaz, sadece bir meslektaşı tarafından sırtına
     *  alınıp hastane NPC'sinin bulunduğu claime fiziken götürülerek kurtarılabilirler. */
    private static class BaskinBayginBilgisi {
        final long claimId;
        Long hastaneBaslangic; // null: henüz hastane claiminde değil
        BaskinBayginBilgisi(long claimId) { this.claimId = claimId; }
    }
    private final java.util.concurrent.ConcurrentHashMap<UUID, BaskinBayginBilgisi> baskindaBayilanlar = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long BASKIN_HASTANE_SURESI_MS = 2 * 60 * 1000L; // baskında bayılan polis için 2 dakika

    // Baskın sırasında bayılan aranan/şüpheliler: ambulans çağıramazlar, sadece polis tarafından
    // yakalanarak (ya da tamTedavi ile) bu durumdan çıkabilirler.
    private final HashSet<UUID> baskindaBayginAranan = new HashSet<>();

    private final Random random = new Random();

    // Tek seferlik polis kiti: kiti almış oyuncular (kalıcı, config: polis_kit_alanlar)
    private final java.util.Set<UUID> kitAlanlar = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final NamespacedKey kitEsyaKey;

    // Dünyası şu an yüklü olmayan kayıtlar: kaydederken silinmesinler diye ham haliyle saklanır
    private final Map<String, Map<String, Object>> yuklenemeyenHucreler = new HashMap<>();
    private final Map<String, Map<String, Object>> yuklenemeyenDonusKonumlari = new HashMap<>();

    // Hapisteyken kullanılabilecek komutlar (beyaz liste). Config: hapis.izinli_komutlar
    private static final String IZINLI_KOMUTLAR_YOLU = "hapis.izinli_komutlar";
    private static final List<String> VARSAYILAN_IZINLI_KOMUTLAR = Arrays.asList(
            "msg", "tell", "w", "r", "reply", "m",
            "yasalar", "rehber",
            "avukatkabul", "avukatred",
            "kira", "kirasozlesme");

    public PolisManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.copKey = new NamespacedKey(plugin, "polis_copu");
        this.modKey = new NamespacedKey(plugin, "cop_modu");
        this.sikayetNpcKey = new NamespacedKey(plugin, "sikayet_npc");
        this.davaDosyasiKey = new NamespacedKey(plugin, "dava_hedefi");
        this.kitEsyaKey = new NamespacedKey(plugin, "polis_kit_esyasi");

        // Liste config'de görünsün ki yöneticiler düzenleyebilsin
        if (!plugin.getConfig().contains(IZINLI_KOMUTLAR_YOLU)) {
            plugin.getConfig().set(IZINLI_KOMUTLAR_YOLU, VARSAYILAN_IZINLI_KOMUTLAR);
            plugin.saveConfig();
        }

        baskinGorevleriniBaslat();
        kitKontrolGoreviniBaslat();
    }

    /** Her 30 saniyede bir: meslekten çıkmış (artık polis/amir olmayan) ve kit hakkı kayıtlı çevrimiçi
     *  oyuncuların kit eşyalarını siler ve hakkı sıfırlar. Çevrimdışıyken meslekten çıkanlar girişte
     *  (ilk turda) temizlenir. Hapisteki polis, mesleği geçici "mahkum" olduğu için atlanır. */
    private void kitKontrolGoreviniBaslat() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (kitAlanlar.isEmpty()) return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                UUID id = p.getUniqueId();
                if (!kitAlanlar.contains(id)) continue;
                if (jailedPlayers.containsKey(id) || preJailRoles.containsKey(id)) continue;
                if (polisMi(meslekOf(p))) continue;
                kitTemizle(p);
            }
        }, 600L, 600L);
    }

    /** Meslekten çıkan oyuncunun üzerindeki (envanter, zırh/off-hand, ender sandık, imleç) etiketli polis
     *  kit eşyalarını siler ve kit hakkını sıfırlar. Önce eşyalar silinir, hak en son kalkar. */
    private void kitTemizle(Player p) {
        int silinen = 0;
        var inv = p.getInventory();
        ItemStack[] icerik = inv.getContents(); // 0-35 depo, 36-39 zırh, 40 off-hand (setItem ile aynı indeks)
        for (int i = 0; i < icerik.length; i++) {
            if (isKitEsyasi(icerik[i])) { inv.setItem(i, null); silinen++; }
        }
        ItemStack[] ender = p.getEnderChest().getContents();
        for (int i = 0; i < ender.length; i++) {
            if (isKitEsyasi(ender[i])) { p.getEnderChest().setItem(i, null); silinen++; }
        }
        if (isKitEsyasi(p.getItemOnCursor())) { p.setItemOnCursor(null); silinen++; }
        kitAlanlar.remove(p.getUniqueId());
        plugin.veriKaydet();
        plugin.getLogger().info("[Polis] " + p.getName() + " meslekten çıktı: " + silinen + " kit eşyası silindi, kit hakkı sıfırlandı.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;
        String commandName = command.getName();

        if (commandName.equalsIgnoreCase("hucreolustur")) {
            if (!player.hasPermission("polis.admin")) return true;
            if (args.length == 0) { player.sendMessage(ChatColor.RED + "Kullanım: /hucreolustur <hücre_adı>"); return true; }
            String cellName = args[0].toLowerCase();
            jailCells.put(cellName, player.getLocation());
            yuklenemeyenHucreler.remove(cellName);
            plugin.veriKaydet();
            player.sendMessage(ChatColor.GREEN + "Hücre '" + cellName + "' oluşturuldu!");
            return true;
        }

        if (commandName.equalsIgnoreCase("hucresil")) {
            if (!player.hasPermission("polis.admin")) return true;
            if (args.length == 0) return true;
            String cellName = args[0].toLowerCase();
            boolean silindi = jailCells.remove(cellName) != null;
            silindi |= yuklenemeyenHucreler.remove(cellName) != null;
            if (silindi) {
                plugin.veriKaydet();
                player.sendMessage(ChatColor.GREEN + "Hücre silindi!");
            }
            return true;
        }

        if (commandName.equalsIgnoreCase("copal")) {
            String meslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas");
            if (!polisMi(meslek)) {
                player.sendMessage(ChatColor.RED + "Sadece emniyet mensupları cop alabilir!");
                return true;
            }
            // Üzerinde zaten cop varsa yenisi verilmez (sonsuz cop alma engellenir).
            for (ItemStack invItem : player.getInventory().getContents()) {
                if (isCop(invItem)) {
                    player.sendMessage(ChatColor.YELLOW + "[Polis] " + ChatColor.GRAY + "Zaten üzerinde bir cop var, yeniden verilmedi.");
                    return true;
                }
            }
            player.getInventory().addItem(createCop(1));
            player.sendMessage(ChatColor.BLUE + "[Polis] " + ChatColor.GREEN + "Polis copu verildi.");
            return true;
        }

        // /poliskit: TEK SEFERLİK. Kit bir kez alınır (kalıcı kayıt: polis_kit_alanlar). Yeniden alabilmek için
        // ÖNCE meslekten çıkılması gerekir; meslekten çıkınca kitTemizle() etiketli (PDC) kit eşyalarını
        // oyuncudan siler ve hakkı sıfırlar. VARSAYIM: kit eşyası kaybolur/çalınırsa hak geri gelmez;
        // yöneticiler "/poliskit sifirla <oyuncu>" ile hakkı sıfırlayabilir.
        if (commandName.equalsIgnoreCase("poliskit")) {
            if (args.length >= 2 && args[0].equalsIgnoreCase("sifirla")) {
                if (!player.hasPermission("polis.admin")) {
                    player.sendMessage(ChatColor.RED + "Bu komut sadece yöneticiler içindir.");
                    return true;
                }
                Player hedef = Bukkit.getPlayerExact(args[1]);
                UUID hedefId = hedef != null ? hedef.getUniqueId() : Bukkit.getOfflinePlayer(args[1]).getUniqueId();
                boolean silindi = kitAlanlar.remove(hedefId);
                plugin.veriKaydet();
                player.sendMessage(ChatColor.GREEN + "[Polis] " + args[1] + (silindi ? " için kit hakkı sıfırlandı." : " zaten kit almamış görünüyor."));
                return true;
            }
            String meslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas");
            if (!polisMi(meslek)) {
                player.sendMessage(ChatColor.RED + "Sadece emniyet mensupları polis kiti alabilir!");
                return true;
            }
            var inv = player.getInventory();

            // Önce envanterde yer var mı bak (eşya kaybı/yere düşme olmasın): cop + kılıç + dolu zırh slotları kadar boş yer gerek.
            int gerekli = 2;
            if (inv.getHelmet() != null) gerekli++;
            if (inv.getChestplate() != null) gerekli++;
            if (inv.getLeggings() != null) gerekli++;
            if (inv.getBoots() != null) gerekli++;
            int bos = 0;
            for (int i = 0; i < 36; i++) if (inv.getItem(i) == null) bos++;
            if (bos < gerekli) {
                player.sendMessage(ChatColor.RED + "[Polis] Envanterinde yeterli boş yer yok (" + gerekli + " slot gerekli). Yer açıp tekrar dene.");
                return true;
            }

            // add() atomiktir: aynı anda iki komut gelse bile sadece biri true döner (çift verme olmaz).
            if (!kitAlanlar.add(player.getUniqueId())) {
                player.sendMessage(ChatColor.YELLOW + "[Polis] " + ChatColor.GRAY
                        + "Polis kitini zaten aldın. Yeniden alabilmek için önce meslekten çıkman gerekir.");
                return true;
            }

            inv.addItem(createCop(1));
            // Zırh: boş slotlara takılır, oyuncunun kendi zırhı varsa kit parçası envantere gider.
            ItemStack kask = createPoliceArmorPiece(Material.DIAMOND_HELMET);
            ItemStack gogus = createPoliceArmorPiece(Material.DIAMOND_CHESTPLATE);
            ItemStack pantolon = createPoliceArmorPiece(Material.DIAMOND_LEGGINGS);
            ItemStack bot = createPoliceArmorPiece(Material.DIAMOND_BOOTS);
            if (inv.getHelmet() == null) inv.setHelmet(kask); else inv.addItem(kask);
            if (inv.getChestplate() == null) inv.setChestplate(gogus); else inv.addItem(gogus);
            if (inv.getLeggings() == null) inv.setLeggings(pantolon); else inv.addItem(pantolon);
            if (inv.getBoots() == null) inv.setBoots(bot); else inv.addItem(bot);
            inv.addItem(createPoliceSword());

            plugin.veriKaydet(); // kit alındı bilgisi hemen kalıcı olsun (restart/çıkışla sıfırlanmasın)
            player.sendMessage(ChatColor.BLUE + "[Polis] " + ChatColor.GREEN + "Polis kiti (cop, Protection 4 zırh, Sharpness 5 kılıç) verildi. Bu kit tek seferliktir.");
            return true;
        }

        if (commandName.equalsIgnoreCase("serbestbirak")) {
            String meslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas");
            if (!polisMi(meslek)) return true;
            if (args.length == 0) return true;
            Player target = Bukkit.getPlayer(args[0]);
            if (target != null && jailedPlayers.containsKey(target.getUniqueId())) {
                releasePlayer(target);
                player.sendMessage(ChatColor.GREEN + target.getName() + " serbest bırakıldı.");
            }
            return true;
        }

        // Şüpheli oyun dışıysa, kaçmışsa ya da dava dosyası kitabı kaybolmuşsa/düşmüşse normal
        // "dava dosyasıyla şüpheliye sağ tık" akışı çalışmaz. Bu, soruşturmayı her durumda kapatabilen
        // bir güvenlik kapısı: /davakapat <oyuncu>
        if (commandName.equalsIgnoreCase("davakapat")) {
            String meslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas");
            if (!polisMi(meslek)) {
                player.sendMessage(ChatColor.RED + "Sadece emniyet mensupları soruşturma kapatabilir!");
                return true;
            }
            if (args.length == 0) {
                player.sendMessage(ChatColor.RED + "Kullanım: /davakapat <oyuncu>");
                return true;
            }
            String hedefAd = args[0];
            String hedefAdKucuk = hedefAd.toLowerCase();
            if (!arananOyuncular.contains(hedefAdKucuk)) {
                player.sendMessage(ChatColor.RED + hedefAd + " hakkında açık bir soruşturma/arama kaydı bulunamadı.");
                return true;
            }
            arananOyuncular.remove(hedefAdKucuk);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag removesuffix " + hedefAd);
            plugin.veriKaydet();

            Player hedefOnline = Bukkit.getPlayerExact(hedefAd);
            String gercekAd = hedefOnline != null ? hedefOnline.getName() : hedefAd;
            if (hedefOnline != null) {
                hedefOnline.sendMessage(ChatColor.GREEN + "Hakkınızdaki arama/soruşturma kaldırıldı.");
            }
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "[MERKEZ] " + ChatColor.YELLOW + gercekAd
                    + ChatColor.GREEN + " hakkındaki soruşturma " + player.getName() + " tarafından kapatıldı, arananlar listesinden çıkarıldı.");
            player.sendMessage(ChatColor.GREEN + gercekAd + " için soruşturma kapatıldı.");
            return true;
        }
        
        if (commandName.equalsIgnoreCase("amirkursu")) {
            if (!player.hasPermission("polis.admin")) return true;
            Block targetBlock = player.getTargetBlockExact(5);
            if (targetBlock != null && targetBlock.getType() == Material.LECTERN) {
                amirKursuKonumu = targetBlock.getLocation();
                plugin.veriKaydet();
                player.sendMessage(ChatColor.GREEN + "Amir Masası başarıyla ayarlandı!");
            } else {
                player.sendMessage(ChatColor.RED + "Lütfen bir kürsüye bakarak komutu girin.");
            }
            return true;
        }
        
        if (commandName.equalsIgnoreCase("sikayetnpc")) {
            if (!player.hasPermission("polis.admin")) return true;
            if (args.length == 0) return true;
            
            if (args[0].equalsIgnoreCase("kur")) {
                Villager npc = (Villager) player.getWorld().spawnEntity(player.getLocation(), EntityType.VILLAGER);
                npc.setAI(false);
                npc.setInvulnerable(true);
                npc.setCollidable(false);
                npc.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + "Şikayet / İhbar Merkezi");
                npc.setCustomNameVisible(true);
                npc.setProfession(Villager.Profession.CLERIC);
                npc.getPersistentDataContainer().set(sikayetNpcKey, PersistentDataType.BYTE, (byte) 1);
                player.sendMessage(ChatColor.GREEN + "Şikayet NPC'si kuruldu!");
            } else if (args[0].equalsIgnoreCase("sil")) {
                int silinen = 0;
                for (Entity entity : player.getNearbyEntities(5, 5, 5)) {
                    if (entity instanceof Villager && entity.getPersistentDataContainer().has(sikayetNpcKey, PersistentDataType.BYTE)) {
                        entity.remove(); silinen++;
                    }
                }
                player.sendMessage(ChatColor.GREEN + "" + silinen + " adet NPC silindi.");
            }
            return true;
        }

        if (commandName.equalsIgnoreCase("sikayetkarar")) {
            String meslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas");
            if (!polisMi(meslek)) return true;
            if (args.length < 2) return true;
            UUID sikayetId;
            try { sikayetId = UUID.fromString(args[0]); }
            catch (IllegalArgumentException e) { player.sendMessage(ChatColor.RED + "Bu şikayet dosyası kapanmış veya bulunamadı."); return true; }
            String karar = args[1];
            
            if (!aktifSikayetler.containsKey(sikayetId)) {
                player.sendMessage(ChatColor.RED + "Bu şikayet dosyası kapanmış veya bulunamadı.");
                return true;
            }
            
            Sikayet sikayet = aktifSikayetler.get(sikayetId);
            aktifSikayetler.remove(sikayetId);
            plugin.veriKaydet();
            
            if (karar.equalsIgnoreCase("kapat")) {
                player.sendMessage(ChatColor.YELLOW + "Şikayet dosyası kapatıldı ve arşivlendi.");
            } else if (karar.equalsIgnoreCase("sorusturma")) {
                arananOyuncular.add(sikayet.sikayetEdilen.toLowerCase());
                // "Aranıyor" artık tab'de görünmez; sadece polislere kafa üstünde gösterilir (ArayuzManager)
                
                ItemStack davaDosyasi = new ItemStack(Material.WRITTEN_BOOK);
                BookMeta bMeta = (BookMeta) davaDosyasi.getItemMeta();
                bMeta.setTitle("Dava Dosyası: " + sikayet.sikayetEdilen);
                bMeta.setAuthor("Emniyet Müdürlüğü");
                bMeta.addPage(ChatColor.DARK_RED + "ARAMA EMRİ & DAVA\n\n" + ChatColor.BLACK + "Şüpheli: " + ChatColor.DARK_GRAY + sikayet.sikayetEdilen + "\n\n" + ChatColor.BLACK + "Müşteki: " + ChatColor.DARK_GRAY + sikayet.sikayetEden + "\n\n" + ChatColor.DARK_RED + "Olay:\n" + ChatColor.BLACK + sikayet.sebep);
                bMeta.getPersistentDataContainer().set(davaDosyasiKey, PersistentDataType.STRING, sikayet.sikayetEdilen);
                davaDosyasi.setItemMeta(bMeta);
                
                player.getInventory().addItem(davaDosyasi);
                
                Bukkit.broadcastMessage(ChatColor.DARK_RED + "[MERKEZ] " + ChatColor.YELLOW + "DİKKAT: " + ChatColor.RED + sikayet.sikayetEdilen + ChatColor.YELLOW + " hakkında soruşturma başlatılmıştır!");
                player.sendMessage(ChatColor.GREEN + "Soruşturma başlatıldı. Dava dosyası envanterinize eklendi!");
            }
            player.closeInventory();
            return true;
        }
        return false;
    }

    @EventHandler
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        if (event.getRightClicked() instanceof Villager) {
            Villager npc = (Villager) event.getRightClicked();
            if (npc.getPersistentDataContainer().has(sikayetNpcKey, PersistentDataType.BYTE)) {
                event.setCancelled(true);
                openSikayetSecimMenu(player, true); // Şikayet NPC'si: polisler de dahil, herkes şikayet edilebilir
                return;
            }
        }
        
        if (event.getRightClicked() instanceof Player) {
            Player target = (Player) event.getRightClicked();
            ItemStack item = player.getInventory().getItemInMainHand();
            
            if (item.getType() == Material.WRITTEN_BOOK && item.hasItemMeta()) {
                PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
                if (data.has(davaDosyasiKey, PersistentDataType.STRING)) {
                    String arananHedef = data.get(davaDosyasiKey, PersistentDataType.STRING);
                    if (target.getName().equalsIgnoreCase(arananHedef)) {
                        event.setCancelled(true);
                        openDavaKararMenu(player, target);
                    } else {
                        player.sendMessage(ChatColor.RED + "Bu dava dosyası " + target.getName() + " için değil, " + arananHedef + " için!");
                    }
                }
            } else if (item.getType() == Material.AIR && !player.isSneaking()) {
                // Boş elle sağ tık: başkan -> rütbe/şikayet menüsü, vatandaş -> şüpheli seçim menüsü (polis şikayet memuru)
                bosElleEtkilesim(event, player, target);
            }
        }
    }

    // ===================== AMİR ROLÜ / RÜTBE / ŞİKAYET (BOŞ ELLE SAĞ TIK) =====================

    private boolean polisMi(String meslek) {
        return meslek != null && (meslek.equalsIgnoreCase("polis") || meslek.equalsIgnoreCase("amir"));
    }

    private String meslekOf(Player p) {
        return plugin.oyuncuMeslekCache.getOrDefault(p.getUniqueId(), "vatandas");
    }

    private void bosElleEtkilesim(PlayerInteractEntityEvent event, Player player, Player target) {
        if (player.getUniqueId().equals(target.getUniqueId())) return;
        if (!polisMi(meslekOf(target))) return; // sadece polis ve amirler hedef olabilir

        String kendi = meslekOf(player);
        if (kendi.equalsIgnoreCase("belediyebaskani")) {
            event.setCancelled(true);
            menuHedefleri.put(player.getUniqueId(), target.getUniqueId());
            openBaskanMenu(player, target);
        } else if (!polisMi(kendi)
                && !kendi.equalsIgnoreCase("mahkum")
                && !kendi.equalsIgnoreCase("gocmen")
                && !kendi.equalsIgnoreCase("default")) {
            event.setCancelled(true);
            if (sikayetAdimi.containsKey(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "Zaten devam eden bir şikayet işleminiz var. İptal etmek için sohbete 'iptal' yazın.");
                return;
            }
            // Polis, şikayet memuru (NPC) gibi davranır: şüpheli oyuncu menüden seçilir.
            // Bu kısayol sadece sivil vatandaşlar içindir (yukarıdaki polisMi kontrolüne bakın);
            // emniyet mensupları listede gözükmez, onlar için şikayet NPC'si veya başka bir memur gerekir.
            openSikayetSecimMenu(player, false);
        }
    }

    private void sikayetBaslat(Player player, String hedefAdi) {
        geciciSikayetHedef.put(player.getUniqueId(), hedefAdi);
        sikayetAdimi.put(player.getUniqueId(), 2);

        player.sendMessage(ChatColor.YELLOW + "--- EMNİYET MÜDÜRLÜĞÜ ---");
        player.sendMessage(ChatColor.GREEN + "Şüpheli: " + ChatColor.RED + hedefAdi);
        player.sendMessage(ChatColor.GREEN + "Lütfen olayın detayını tek bir mesaj halinde anlatın:");
        player.sendMessage(ChatColor.GRAY + "(İptal etmek için 'iptal' yazın)");
    }

    private ItemStack hedefKafasi(Player target) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(target);
        meta.setDisplayName(ChatColor.YELLOW + target.getName());
        String rutbe = meslekOf(target).equalsIgnoreCase("amir") ? "Amir" : "Polis";
        meta.setLore(Collections.singletonList(ChatColor.GRAY + "Mevcut rütbe: " + ChatColor.WHITE + rutbe));
        head.setItemMeta(meta);
        return head;
    }

    private ItemStack menuEsyasi(Material mat, String ad, String aciklama) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ad);
        meta.setLore(Collections.singletonList(ChatColor.GRAY + aciklama));
        item.setItemMeta(meta);
        return item;
    }

    private void openBaskanMenu(Player baskan, Player target) {
        Inventory gui = Bukkit.createInventory(null, 27, BASKAN_MENU);
        gui.setItem(4, hedefKafasi(target));
        gui.setItem(10, menuEsyasi(Material.GOLD_INGOT, ChatColor.GOLD + "" + ChatColor.BOLD + "Terfi / Rütbe İşlemleri",
                "Amir yap veya polise düşür."));
        gui.setItem(13, menuEsyasi(Material.WRITABLE_BOOK, ChatColor.RED + "" + ChatColor.BOLD + "Şikayet Et",
                "Bu oyuncuyu şikayet et."));
        gui.setItem(16, menuEsyasi(Material.LIME_DYE, ChatColor.GREEN + "" + ChatColor.BOLD + "Beğeni / Etkileşim",
                "Beğen, yorum bırak veya para gönder."));
        gui.setItem(22, menuEsyasi(Material.BARRIER, ChatColor.DARK_RED + "" + ChatColor.BOLD + "Vatandaşlıktan Çıkar",
                "Göçmen yapar ve köyün dışına ışınlar."));
        baskan.openInventory(gui);
    }

    private void openRutbeMenu(Player baskan, Player target) {
        Inventory gui = Bukkit.createInventory(null, 27, RUTBE_MENU);
        gui.setItem(4, hedefKafasi(target));
        gui.setItem(11, menuEsyasi(Material.NETHER_STAR, ChatColor.GOLD + "" + ChatColor.BOLD + "Amir Yap",
                "Polisi amirliğe terfi ettirir."));
        gui.setItem(15, menuEsyasi(Material.IRON_INGOT, ChatColor.RED + "" + ChatColor.BOLD + "Polise Düşür",
                "Amiri tekrar polis rütbesine düşürür."));
        baskan.openInventory(gui);
    }

    private void rutbeDegistir(Player baskan, Player target, String yeniMeslek) {
        String eski = meslekOf(target);
        if (!polisMi(eski)) {
            baskan.sendMessage(ChatColor.RED + target.getName() + " artık emniyet mensubu değil!");
            return;
        }
        boolean amirYap = yeniMeslek.equalsIgnoreCase("amir");
        String yeniAd = amirYap ? "Amir" : "Polis";
        if (eski.equalsIgnoreCase(yeniMeslek)) {
            baskan.sendMessage(ChatColor.YELLOW + target.getName() + " zaten " + yeniAd + "!");
            return;
        }

        plugin.oyuncuMeslekCache.put(target.getUniqueId(), yeniMeslek);
        // "amir" tag tanımı yoksa TagPlugin'e oluşturtur (varsa dokunmaz); aksi halde "tag set" sessizce başarısız olurdu
        if (amirYap) {
            String amirTag = plugin.getConfig().getString("amir.tag-format", "&o&1[Amir]");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag ensure amir " + amirTag);
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + target.getName() + " parent set " + yeniMeslek);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag set " + target.getName() + " " + yeniMeslek);
        plugin.veriKaydet();

        if (amirYap) {
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "[MERKEZ] " + ChatColor.YELLOW + target.getName() + ChatColor.GREEN
                    + ", Belediye Başkanı " + baskan.getName() + " tarafından " + ChatColor.GOLD + "AMİRLİĞE" + ChatColor.GREEN + " terfi ettirildi!");
            target.sendMessage(ChatColor.GREEN + "Tebrikler! Amir rütbesine terfi ettiniz.");
        } else {
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "[MERKEZ] " + ChatColor.YELLOW + target.getName() + ChatColor.RED
                    + ", Belediye Başkanı " + baskan.getName() + " tarafından " + ChatColor.GOLD + "POLİS" + ChatColor.RED + " rütbesine düşürüldü.");
            target.sendMessage(ChatColor.RED + "Amir rütbeniz alındı, polis rütbesine düşürüldünüz.");
        }
        baskan.sendMessage(ChatColor.GREEN + target.getName() + " artık " + yeniAd + ".");
    }
    
    private void openDavaKararMenu(Player cop, Player target) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.DARK_RED + "Soruşturma: " + target.getName());
        
        ItemStack hapisHafif = new ItemStack(Material.IRON_BARS);
        ItemMeta hafifMeta = hapisHafif.getItemMeta();
        hafifMeta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Nezarete At (5 Dakika)");
        hafifMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Hafif suçlar için."));
        hapisHafif.setItemMeta(hafifMeta);

        ItemStack hapisOrta = new ItemStack(Material.COBWEB);
        ItemMeta ortaMeta = hapisOrta.getItemMeta();
        ortaMeta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Nezarete At (15 Dakika)");
        ortaMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Orta düzey suçlar için."));
        hapisOrta.setItemMeta(ortaMeta);

        ItemStack hapisAgir = new ItemStack(Material.NETHERITE_BLOCK);
        ItemMeta agirMeta = hapisAgir.getItemMeta();
        agirMeta.setDisplayName(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Nezarete At (30 Dakika)");
        agirMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Ağır suçlar için."));
        hapisAgir.setItemMeta(agirMeta);
        
        ItemStack beraat = new ItemStack(Material.PAPER);
        ItemMeta bMeta = beraat.getItemMeta();
        bMeta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Serbest Bırak / Dosyayı Kapat");
        bMeta.setLore(Collections.singletonList(ChatColor.GRAY + "Şüpheliyi aklayarak soruşturmayı sonlandırır."));
        beraat.setItemMeta(bMeta);
        
        gui.setItem(10, hapisHafif);
        gui.setItem(12, hapisOrta);
        gui.setItem(14, hapisAgir);
        gui.setItem(16, beraat);
        cop.openInventory(gui);
    }
    
    /**
     * @param polisGorunsun true ise listede polis/amirler de şüpheli olarak seçilebilir (şikayet NPC'si akışı).
     *                      false ise polis/amirler listeden gizlenir: bir emniyet mensubu, sadece başka bir
     *                      emniyet mensubu tarafından (NPC üzerinden) ya da şikayet NPC'sinden şikayet edilebilir.
     */
    private void openSikayetSecimMenu(Player player, boolean polisGorunsun) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.DARK_RED + "Şüpheli Seçimi");
        int slot = 0;
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.getUniqueId().equals(player.getUniqueId())) continue;
            if (!polisGorunsun && polisMi(meslekOf(target))) continue;
            if (slot > 53) break;

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(target);
            meta.setDisplayName(ChatColor.YELLOW + target.getName());
            meta.setLore(Collections.singletonList(ChatColor.GRAY + "Şikayet etmek için tıkla."));
            head.setItemMeta(meta);
            
            gui.setItem(slot, head);
            slot++;
        }
        player.openInventory(gui);
    }

    @EventHandler
    public void onLecternInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Block clickedBlock = event.getClickedBlock();
            if (clickedBlock != null && clickedBlock.getType() == Material.LECTERN) {
                if (amirKursuKonumu != null && clickedBlock.getLocation().equals(amirKursuKonumu)) {
                    event.setCancelled(true); 
                    Player player = event.getPlayer();
                    String meslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas");
                    if (polisMi(meslek)) {
                        openAmirMenu(player);
                    } else {
                        player.sendMessage(ChatColor.RED + "Bu dosyalara sadece emniyet mensupları erişebilir!");
                    }
                }
            }
        }
    }

    private void openAmirMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.DARK_BLUE + "Bekleyen Şikayetler");
        int slot = 0;
        for (Sikayet s : aktifSikayetler.values()) {
            if(slot > 53) break;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(s.sikayetEdilen));
            meta.setDisplayName(ChatColor.RED + "Şüpheli: " + s.sikayetEdilen);
            
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Şikayet Eden: " + ChatColor.YELLOW + s.sikayetEden);
            lore.add(ChatColor.DARK_GRAY + "Tıklayarak detaylı ifadeyi oku.");
            meta.setLore(lore);
            
            PersistentDataContainer data = meta.getPersistentDataContainer();
            data.set(new NamespacedKey(plugin, "sikayet_id"), PersistentDataType.STRING, s.id.toString());
            
            head.setItemMeta(meta);
            gui.setItem(slot, head);
            slot++;
        }
        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        String title = event.getView().getTitle();
        Player player = (Player) event.getWhoClicked();

        // BAŞKAN MENÜLERİ: (1) Terfi / Şikayet seçimi, (2) Amir Yap / Polise Düşür
        if (title.equals(BASKAN_MENU) || title.equals(RUTBE_MENU)) {
            event.setCancelled(true);
            if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR || clicked.getType() == Material.PLAYER_HEAD) return;

            UUID hedefId = menuHedefleri.get(player.getUniqueId());
            Player target = hedefId == null ? null : Bukkit.getPlayer(hedefId);
            if (!meslekOf(player).equalsIgnoreCase("belediyebaskani")) {
                player.closeInventory();
                player.sendMessage(ChatColor.RED + "Bu menüyü sadece Belediye Başkanı kullanabilir!");
                return;
            }
            if (target == null || !target.isOnline()) {
                player.closeInventory();
                menuHedefleri.remove(player.getUniqueId());
                player.sendMessage(ChatColor.RED + "Seçtiğiniz oyuncu oyunda değil!");
                return;
            }

            Material mat = clicked.getType();
            if (title.equals(BASKAN_MENU)) {
                if (mat == Material.GOLD_INGOT) {
                    if (!polisMi(meslekOf(target))) {
                        player.closeInventory();
                        player.sendMessage(ChatColor.RED + target.getName() + " artık emniyet mensubu değil!");
                        return;
                    }
                    openRutbeMenu(player, target);
                } else if (mat == Material.WRITABLE_BOOK) {
                    player.closeInventory();
                    if (sikayetAdimi.containsKey(player.getUniqueId())) {
                        player.sendMessage(ChatColor.RED + "Zaten devam eden bir şikayet işleminiz var. İptal etmek için sohbete 'iptal' yazın.");
                        return;
                    }
                    // Başkan menüsündeki "Şikayet Et" artık genel bir şikayet kısayolu (normal oyuncular için de
                    // geçerli), bu yüzden emniyet mensuplarını listede gizliyoruz; bir memuru şikayet etmek için
                    // şikayet NPC'si veya başka bir memur kullanılmalı.
                    openSikayetSecimMenu(player, false);
                } else if (mat == Material.LIME_DYE) {
                    player.closeInventory();
                    if (plugin.etkilesimManager != null) plugin.etkilesimManager.menuAc(player, target.getUniqueId());
                } else if (mat == Material.BARRIER) {
                    player.closeInventory();
                    if (plugin.koyManager != null) plugin.koyManager.vatandasliktanCikar(player, target);
                }
            } else {
                if (mat == Material.NETHER_STAR) {
                    rutbeDegistir(player, target, "amir");
                    player.closeInventory();
                } else if (mat == Material.IRON_INGOT) {
                    rutbeDegistir(player, target, "polis");
                    player.closeInventory();
                }
            }
            return;
        }

        if (title.equals(ChatColor.DARK_RED + "Şüpheli Seçimi")) {
            event.setCancelled(true);
            if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;
            
            String targetName = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
            player.closeInventory();
            
            sikayetBaslat(player, targetName);
            return;
        }
        
        if (title.equals(ChatColor.DARK_BLUE + "Bekleyen Şikayetler")) {
            event.setCancelled(true);
            if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;
            
            PersistentDataContainer data = clicked.getItemMeta().getPersistentDataContainer();
            NamespacedKey key = new NamespacedKey(plugin, "sikayet_id");
            if (data.has(key, PersistentDataType.STRING)) {
                UUID sId = UUID.fromString(data.get(key, PersistentDataType.STRING));
                if (aktifSikayetler.containsKey(sId)) {
                    openSikayetKitabi(player, aktifSikayetler.get(sId));
                }
            }
            return;
        }
        
        if (title.startsWith(ChatColor.DARK_RED + "Soruşturma: ")) {
            event.setCancelled(true);
            if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;
            
            String targetName = title.replace(ChatColor.DARK_RED + "Soruşturma: ", "").trim();
            Player target = Bukkit.getPlayer(targetName);
            
            if (target == null || !target.isOnline()) {
                player.sendMessage(ChatColor.RED + "Şüpheli oyunda değil veya kaçmış!");
                player.closeInventory();
                return;
            }
            
            Material clickedMat = clicked.getType();
            
            if (clickedMat == Material.IRON_BARS || clickedMat == Material.COBWEB || clickedMat == Material.NETHERITE_BLOCK) {
                if (jailCells.isEmpty()) {
                    player.sendMessage(ChatColor.RED + "Hücre ayarlanmamış!");
                } else {
                    int sure = 0;
                    String mesajSeviyesi = "";
                    
                    if (clickedMat == Material.IRON_BARS) { sure = 300; mesajSeviyesi = "Hafif Suç (5 Dk)"; }
                    else if (clickedMat == Material.COBWEB) { sure = 900; mesajSeviyesi = "Orta Suç (15 Dk)"; }
                    else if (clickedMat == Material.NETHERITE_BLOCK) { sure = 1800; mesajSeviyesi = "Ağır Suç (30 Dk)"; }

                    arananOyuncular.remove(target.getName().toLowerCase());
                    preJailLocations.put(target.getUniqueId(), target.getLocation());
                    
                    jailPlayer(target, sure); 
                    
                    Bukkit.broadcastMessage(ChatColor.DARK_RED + "[MERKEZ] " + ChatColor.YELLOW + target.getName() + ChatColor.GREEN + " tutuklanarak nezarete gönderildi! [" + mesajSeviyesi + "]");
                    elindekiDosyayiSil(player);
                }
            } else if (clickedMat == Material.PAPER) {
                arananOyuncular.remove(target.getName().toLowerCase());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag removesuffix " + target.getName()); 
                Bukkit.broadcastMessage(ChatColor.DARK_RED + "[MERKEZ] " + ChatColor.YELLOW + target.getName() + ChatColor.GREEN + " hakkındaki suçlamalar düşürüldü ve dosya kapatıldı.");
                elindekiDosyayiSil(player);
            }
            player.closeInventory();
        }
    }
    
    private void elindekiDosyayiSil(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item != null && item.getType() == Material.WRITTEN_BOOK && item.hasItemMeta()) {
            if (item.getItemMeta().getPersistentDataContainer().has(davaDosyasiKey, PersistentDataType.STRING)) {
                player.getInventory().setItemInMainHand(null);
            }
        }
    }

    private void openSikayetKitabi(Player amir, Sikayet sikayet) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Dosya No: " + sikayet.id.toString().substring(0, 5));
        meta.setAuthor("Emniyet");

        List<BaseComponent[]> pages = new ArrayList<>();
        String sebep = sikayet.sebep;
        List<String> chunks = new ArrayList<>();
        int chunkSize = 150; 
        int index = 0;
        
        while (index < sebep.length()) {
            int end = Math.min(index + chunkSize, sebep.length());
            if (end < sebep.length()) {
                int lastSpace = sebep.lastIndexOf(' ', end);
                if (lastSpace > index) end = lastSpace;
            }
            chunks.add(sebep.substring(index, end).trim());
            index = end;
        }

        TextComponent sayfa1 = new TextComponent(ChatColor.DARK_RED + ChatColor.BOLD.toString() + "ŞİKAYET DOSYASI\n\n");
        sayfa1.addExtra(new TextComponent(ChatColor.BLACK + "Şikayet Eden:\n" + ChatColor.DARK_GRAY + sikayet.sikayetEden + "\n\n"));
        sayfa1.addExtra(new TextComponent(ChatColor.BLACK + "Şüpheli:\n" + ChatColor.DARK_GRAY + sikayet.sikayetEdilen + "\n\n"));
        sayfa1.addExtra(new TextComponent(ChatColor.DARK_RED + "İfade Detayı: " + ChatColor.DARK_GRAY + "sonraki sayfada ►"));
        pages.add(new BaseComponent[]{sayfa1});

        // İfade ayrı sayfalardan başlar: başlık bilgileriyle aynı sayfada 14 satırı aşıp kesilmesin
        for (String parca : chunks) {
            pages.add(new BaseComponent[]{new TextComponent(ChatColor.DARK_RED + ChatColor.BOLD.toString() + "İFADE\n\n" + ChatColor.BLACK + parca)});
        }

        TextComponent sonSayfa = new TextComponent(ChatColor.DARK_BLUE + ChatColor.BOLD.toString() + "AMİR KARARI\n\n\n");
        TextComponent redBtn = new TextComponent(ChatColor.DARK_GRAY + ChatColor.BOLD.toString() + "[DOSYAYI KAPAT]\n\n\n");
        redBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sikayetkarar " + sikayet.id.toString() + " kapat"));
        
        TextComponent kabulBtn = new TextComponent(ChatColor.DARK_RED + ChatColor.BOLD.toString() + "[SORUŞTURMA BAŞLAT]");
        kabulBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sikayetkarar " + sikayet.id.toString() + " sorusturma"));
        
        sonSayfa.addExtra(redBtn); sonSayfa.addExtra(kabulBtn);
        pages.add(new BaseComponent[]{sonSayfa});

        for (BaseComponent[] page : pages) { meta.spigot().addPage(page); }
        book.setItemMeta(meta); amir.openBook(book);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!sikayetAdimi.containsKey(player.getUniqueId())) return;
        event.setCancelled(true);
        String msg = event.getMessage().trim();
        // Şikayet kaydı ana thread'de oluşturulur (chat olayı ayrı thread'den gelir)
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) sikayetMesajiIsle(player, msg);
        });
    }

    private void sikayetMesajiIsle(Player player, String msg) {
        UUID pId = player.getUniqueId();
        if (!sikayetAdimi.containsKey(pId)) return;

        if (msg.equalsIgnoreCase("iptal")) {
            sikayetAdimi.remove(pId);
            geciciSikayetHedef.remove(pId);
            player.sendMessage(ChatColor.YELLOW + "Şikayet işlemi iptal edildi.");
            return;
        }

        int adim = sikayetAdimi.get(pId);
        if (adim == 2) {
            String hedef = geciciSikayetHedef.get(pId);
            Sikayet yeniSikayet = new Sikayet(UUID.randomUUID(), player.getName(), hedef, msg);
            aktifSikayetler.put(yeniSikayet.id, yeniSikayet);

            sikayetAdimi.remove(pId);
            geciciSikayetHedef.remove(pId);
            plugin.veriKaydet();

            player.sendMessage(ChatColor.GREEN + "Tutanak başarıyla karakola iletildi.");

            for (Player p : Bukkit.getOnlinePlayers()) {
                String pMeslek = plugin.oyuncuMeslekCache.getOrDefault(p.getUniqueId(), "vatandas");
                if (polisMi(pMeslek)) {
                    p.sendMessage(ChatColor.DARK_RED + "[MERKEZ] " + ChatColor.YELLOW + "Karakola yeni bir ihbar/şikayet ulaştı! Amir Masasını kontrol edin.");
                }
            }
        }
    }

    @EventHandler
    public void onCopInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player.isSneaking() && (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK)) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (isCop(item)) {
                int currentMod = getCopMod(item);
                int nextMod = currentMod == 3 ? 1 : currentMod + 1; 
                
                ItemStack newCop = createCop(nextMod);
                player.getInventory().setItemInMainHand(newCop);
                
                String modName = nextMod == 1 ? "5 Dakika (Hafif Suç)" : nextMod == 2 ? "10 Dakika (Orta Suç)" : "30 Dakika (Ağır Suç)";
                player.sendMessage(ChatColor.BLUE + "[Polis] " + ChatColor.YELLOW + "Cop modu değiştirildi: " + ChatColor.RED + modName);
            }
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItemDrop().getItemStack();

        if (isCop(item)) {
            UUID uuid = player.getUniqueId();
            long currentTime = System.currentTimeMillis();

            if (dropConfirmations.containsKey(uuid) && (currentTime - dropConfirmations.get(uuid)) < 5000) {
                dropConfirmations.remove(uuid); 
                event.getItemDrop().remove(); 
                
                for (int i = 0; i < player.getInventory().getSize(); i++) {
                    ItemStack invItem = player.getInventory().getItem(i);
                    if (isCop(invItem)) player.getInventory().setItem(i, null);
                }
                
                player.sendMessage(ChatColor.BLUE + "[Polis] " + ChatColor.RED + "İstifa ettiniz! Polislik yetkileriniz alındı.");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "meslekata " + player.getName() + " vatandas");
                
            } else {
                event.setCancelled(true); 
                dropConfirmations.put(uuid, currentTime);
                player.sendMessage(ChatColor.YELLOW + "Polis mesleğinden ayrılmak istediğine emin misin?");
                player.sendMessage(ChatColor.YELLOW + "Eğer eminsen " + ChatColor.RED + "5 saniye içinde copu tekrar yere at (Q).");
            }
        }
    }

    /** Hedef, serbest kaldıktan sonraki COP_COOLDOWN_MS içinde mi ve bu sürede yeni bir meşru sebep
     *  (kendisi saldırmış/öldürmüş ya da hakkında şikayet var) oluşmamış mı? Öyleyse true döner. */
    private boolean copCooldundaMi(Player target) {
        Long salma = sonSalmaZamani.get(target.getUniqueId());
        if (salma == null) return false;
        long simdi = System.currentTimeMillis();
        if (simdi - salma >= COP_COOLDOWN_MS) return false;
        if (arananOyuncular.contains(target.getName().toLowerCase())) return false;
        Long saldirganlik = sonSaldirganlikZamani.get(target.getUniqueId());
        if (saldirganlik != null && simdi - saldirganlik < COP_COOLDOWN_MS) return false;
        return true;
    }

    // Bir oyuncu başka bir oyuncuya vurursa (copla değil, herhangi bir şekilde) ya da birini öldürürse
    // bu zaman damgalanır; bu sayede serbest kalma cooldown'u varsa bile meşru bir sebeple hapse atılabilir.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSaldirganlikIzle(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player) || !(event.getEntity() instanceof Player)) return;
        Player saldirgan = (Player) event.getDamager();
        sonSaldirganlikZamani.put(saldirgan.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onOlum(org.bukkit.event.entity.PlayerDeathEvent event) {
        Player katil = event.getEntity().getKiller();
        if (katil != null) {
            long simdi = System.currentTimeMillis();
            sonSaldirganlikZamani.put(katil.getUniqueId(), simdi);
            sonOlumZamani.put(katil.getUniqueId(), simdi);
        }
    }

    /** Masum bir vatandaşı (hakkında şikayet/arama yok) copla hapse atmak için meşru bir sebep var mı?
     *  Üç durumdan biri yeterli: (1) aranan/şikayetli, (2) yakın zamanda birini öldürmüş,
     *  (3) şu an hâlâ baygın olan birini bayıltmış olması. Yoksa polis sadece uyarı alır, hapse atamaz. */
    private boolean adaletliSebepVarMi(Player target) {
        if (arananOyuncular.contains(target.getName().toLowerCase())) return true;
        Long olum = sonOlumZamani.get(target.getUniqueId());
        if (olum != null && System.currentTimeMillis() - olum < GENEL_SEBEP_PENCERESI_MS) return true;
        return plugin.saglikManager != null && plugin.saglikManager.birisiniBayginaDusurduMu(target.getUniqueId());
    }

    /** Hedef şu an, erişim izni olmadığı bir GriefPrevention claim'inin içinde duruyorsa (izinsiz
     *  misafir/tecavüzcü), onu hapse atmak yerine claim'in dışına ışınlar. Hedef o claim'e zaten
     *  erişebiliyorsa (sahibi/trusted/wilderness) hiçbir şey yapmaz ve false döner. */
    private boolean claimdenTahliyeEt(Player copPlayer, Player target) {
        try {
            me.ryanhamshire.GriefPrevention.Claim claim = me.ryanhamshire.GriefPrevention.GriefPrevention.instance
                    .dataStore.getClaimAt(target.getLocation(), false, null);
            if (claim == null) return false;
            if (claim.allowAccess(target) == null) return false; // erişimi var, misafir değil

            Location disari = claimDisariIsinlaKonumu(claim, target.getLocation());
            if (disari == null) return false;

            target.teleport(disari);
            target.sendMessage(ChatColor.RED + "Bu alana girme izniniz yok! Polis sizi dışarı çıkardı.");
            copPlayer.sendMessage(ChatColor.BLUE + "[Polis] " + ChatColor.GREEN + target.getName()
                    + " izinsiz girdiği alandan tahliye edildi.");
            return true;
        } catch (Exception e) {
            return false; // GriefPrevention beklenmedik bir şey döndürürse sessizce atla
        }
    }

    /** Claim'in en yakın kenarının 2 blok dışında, o noktadaki en üst zemine göre güvenli bir konum. */
    private Location claimDisariIsinlaKonumu(me.ryanhamshire.GriefPrevention.Claim claim, Location icerde) {
        Location kucuk = claim.getLesserBoundaryCorner();
        Location buyuk = claim.getGreaterBoundaryCorner();
        double x = icerde.getX(), z = icerde.getZ();

        double solaUzaklik = x - kucuk.getX();
        double sagaUzaklik = buyuk.getX() - x;
        double onUzaklik = z - kucuk.getZ();
        double arkaUzaklik = buyuk.getZ() - z;
        double min = Math.min(Math.min(solaUzaklik, sagaUzaklik), Math.min(onUzaklik, arkaUzaklik));

        double yeniX = x, yeniZ = z;
        if (min == solaUzaklik) yeniX = kucuk.getX() - 2;
        else if (min == sagaUzaklik) yeniX = buyuk.getX() + 2;
        else if (min == onUzaklik) yeniZ = kucuk.getZ() - 2;
        else yeniZ = buyuk.getZ() + 2;

        org.bukkit.World world = icerde.getWorld();
        if (world == null) return null;
        int topY = world.getHighestBlockYAt((int) Math.floor(yeniX), (int) Math.floor(yeniZ));
        return new Location(world, yeniX + 0.5, topY + 1, yeniZ + 0.5, icerde.getYaw(), icerde.getPitch());
    }

    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player && event.getEntity() instanceof Player) {
            Player copPlayer = (Player) event.getDamager();
            Player targetPlayer = (Player) event.getEntity();
            ItemStack item = copPlayer.getInventory().getItemInMainHand();

            if (isCop(item)) {
                String meslek = plugin.oyuncuMeslekCache.getOrDefault(copPlayer.getUniqueId(), "vatandas");
                if (!polisMi(meslek)) return; 
                // Arena savaşındaki oyuncular (sağlık muafiyetli) copla hapse atılmaz; vuruş normal hasar sayılır
                if (savastaMi(targetPlayer.getUniqueId()) || savastaMi(copPlayer.getUniqueId())) return;
                event.setCancelled(true); 
                
                if (jailCells.isEmpty()) { copPlayer.sendMessage(ChatColor.RED + "Hiç hücre ayarlanmamış!"); return; }
                if (jailedPlayers.containsKey(targetPlayer.getUniqueId())) { copPlayer.sendMessage(ChatColor.RED + "Bu oyuncu zaten hapiste!"); return; }

                if (copCooldundaMi(targetPlayer)) {
                    copPlayer.sendMessage(ChatColor.RED + targetPlayer.getName() + " yakın zamanda serbest bırakıldı. Yeni bir sebep yoksa (saldırı/cinayet/şikayet) hemen tekrar hapse atılamaz.");
                    return;
                }

                // Masum vatandaş: hakkında şikayet/arama yok, kimseyi öldürmemiş ya da bayıltmamış.
                // Copla hapse atılamaz; sadece uyarı alır. İzinsiz bir claim'deyse oradan tahliye edilir.
                if (!adaletliSebepVarMi(targetPlayer)) {
                    if (!claimdenTahliyeEt(copPlayer, targetPlayer)) {
                        copPlayer.sendMessage(ChatColor.YELLOW + "[Polis] " + ChatColor.GRAY + targetPlayer.getName()
                                + " hakkında herhangi bir şikayet bulunmamaktadır. Bu kişi masumdur, sebepsiz yere hapse atılamaz.");
                    }
                    return;
                }

                // Meşru sebep olsa bile tutuklama ancak şüpheli etkisiz hale getirilince (bayılınca) yapılabilir;
                // polis onu önce dövüp bayıltmadan tek copla doğrudan hapse atamaz.
                boolean bayginMi = plugin.saglikManager != null && plugin.saglikManager.agirYaraliMi(targetPlayer.getUniqueId());
                if (!bayginMi) {
                    copPlayer.sendMessage(ChatColor.RED + "[Polis] " + targetPlayer.getName()
                            + " hâlâ ayakta! Tutuklamadan önce onu etkisiz hale getirip (bayıltıp) sonra copla vurmalısınız.");
                    return;
                }

                if (arananOyuncular.contains(targetPlayer.getName().toLowerCase())) {
                    arananOyuncular.remove(targetPlayer.getName().toLowerCase());
                    savunmaDurumlari.remove(targetPlayer.getUniqueId());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag removesuffix " + targetPlayer.getName());
                    Bukkit.broadcastMessage(ChatColor.DARK_RED + "[MERKEZ] " + ChatColor.GREEN + "Aranan şüpheli " + ChatColor.YELLOW + targetPlayer.getName() + ChatColor.GREEN + ", Memur " + copPlayer.getName() + " tarafından yakalanarak paketlendi!");
                }

                // Hapse girince baygınlık/kırık/kanama da biter; tutuklu hücrede "ayılmış" başlar.
                if (plugin.saglikManager != null) plugin.saglikManager.tamTedavi(targetPlayer);

                preJailLocations.put(targetPlayer.getUniqueId(), targetPlayer.getLocation());
                int mod = getCopMod(item);
                int durationSeconds = mod == 1 ? 300 : mod == 2 ? 600 : 1800; 
                
                jailPlayer(targetPlayer, durationSeconds);
                copPlayer.sendMessage(ChatColor.BLUE + "[Polis] " + ChatColor.GREEN + targetPlayer.getName() + " başarıyla hapse atıldı!");
            }
        }
    }

    /** ARAMA İZNİ: Aranan/şikayetli bir oyuncu bir claim'in içindeyse, polis/amir o claim'e ait
     *  kapı/kapak/geçide (Openable) erişimi olmasa da girebilir — sadece girer, bloklara dokunamaz.
     *  Aranan kimse o claim'de değilse GriefPrevention'ın normal "erişimin yok" engeli geçerli kalır. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAramaIzniKapiGecisi(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        org.bukkit.block.data.BlockData data = block.getBlockData();
        if (!(data instanceof org.bukkit.block.data.Openable)) return;

        Player player = event.getPlayer();
        String meslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas");
        if (!polisMi(meslek)) return;

        try {
            me.ryanhamshire.GriefPrevention.Claim claim = me.ryanhamshire.GriefPrevention.GriefPrevention.instance
                    .dataStore.getClaimAt(block.getLocation(), false, null);
            if (claim == null) return; // vahşi doğa, GriefPrevention zaten engellemiyor
            if (claim.allowAccess(player) == null) return; // polisin zaten erişimi var, karışmaya gerek yok

            boolean arananIcerde = false;
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (arananOyuncular.contains(online.getName().toLowerCase())
                        && claim.contains(online.getLocation(), false, false)) {
                    arananIcerde = true;
                    break;
                }
            }
            if (!arananIcerde) return; // arama izni için gerekçe yok, GP'nin engeli geçerli kalsın

            event.setCancelled(true);
            final org.bukkit.block.data.Openable openable = (org.bukkit.block.data.Openable) data;
            openable.setOpen(true);
            block.setBlockData(openable);
            player.sendMessage(ChatColor.BLUE + "[Polis] " + ChatColor.GREEN
                    + "Arama izniniz var, bu claim'e girebilirsiniz.");

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                org.bukkit.block.data.BlockData guncel = block.getBlockData();
                if (guncel instanceof org.bukkit.block.data.Openable) {
                    org.bukkit.block.data.Openable o2 = (org.bukkit.block.data.Openable) guncel;
                    o2.setOpen(false);
                    block.setBlockData(o2);
                }
            }, 60L);
        } catch (Exception ignored) {
            // GriefPrevention API beklenmedik bir şey döndürürse normal davranışa dokunma
        }
    }

    /** Vuruşu yapan oyuncu: doğrudan vuran ya da attığı ok/kartopu vb. mermisinin sahibi. */
    private Player saldiranOyuncuBul(Entity hasarVeren) {
        if (hasarVeren instanceof Player) return (Player) hasarVeren;
        if (hasarVeren instanceof org.bukkit.entity.Projectile) {
            Object atan = ((org.bukkit.entity.Projectile) hasarVeren).getShooter();
            if (atan instanceof Player) return (Player) atan;
        }
        return null;
    }

    /** Bu claim'in içinde şu an aranan (şikayetli) bir oyuncu var mı? Varsa orada polis baskını/araması sayılır. */
    private boolean claimdeArananVarMi(me.ryanhamshire.GriefPrevention.Claim claim) {
        if (claim == null) return false;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (arananOyuncular.contains(online.getName().toLowerCase())
                    && claim.contains(online.getLocation(), false, false)) return true;
        }
        return false;
    }

    /** POLİS ↔ SİVİL VURUŞ KURALI. Polis/amir ile sivil birbirine ancak baskın/kovalamaca sırasında vurabilir:
     *   - o claim'de baskın zaten aktifse (son 3 dk içinde vuruş olmuşsa) ya da claim'de aranan biri varsa, ya da
     *   - sivil taraf meşru sebepli ise (aranan/şikayetli, yakın zamanda öldürmüş, baygın bıraktığı biri var).
     *  Bunların hiçbiri yoksa vuruş iptal edilir (claim içinde de dışında da). Polis-polis, sivil-sivil ve arena
     *  savaşı vuruşlarına dokunulmaz.
     *
     *  ÖNEMLİ: LOW önceliği bilerek seçildi. GriefPrevention claim PVP korumasını LOWEST'te iptal eder; biz onun
     *  hemen ardından (LOW) kararı veririz. Böylece SaglikManager'in NORMAL'deki "ölümcül vuruş → baygın" işleyicisi
     *  bizden SONRA çalışır ve baskında vurulanlar ölmek yerine baygın düşer. (Eskiden HIGHEST'te iptali geri
     *  alıyorduk; bu, sağlık sisteminin iptal ettiği ölümcül vuruşu da geri açıp oyuncuyu öldürüyordu.) */
    @EventHandler(priority = EventPriority.LOW)
    public void onPolisSivilPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player magdur = (Player) event.getEntity();
        Player saldiran = saldiranOyuncuBul(event.getDamager());
        if (saldiran == null || saldiran.getUniqueId().equals(magdur.getUniqueId())) return;

        // Arena savaşındaki oyuncular (sağlık muafiyetli) kendi kurallarına tabi
        if (savastaMi(magdur.getUniqueId()) || savastaMi(saldiran.getUniqueId())) return;

        boolean magdurPolis = polisMi(plugin.oyuncuMeslekCache.getOrDefault(magdur.getUniqueId(), "vatandas"));
        boolean saldiranPolis = polisMi(plugin.oyuncuMeslekCache.getOrDefault(saldiran.getUniqueId(), "vatandas"));
        if (magdurPolis == saldiranPolis) return; // polis-polis ya da sivil-sivil: bu kuralın konusu değil

        Player sivil = saldiranPolis ? magdur : saldiran;

        me.ryanhamshire.GriefPrevention.Claim claim = null;
        try {
            claim = me.ryanhamshire.GriefPrevention.GriefPrevention.instance
                    .dataStore.getClaimAt(magdur.getLocation(), false, null);
        } catch (Exception ignored) {
        }

        // Baskın modu sadece sivilin KENDİ claim'inde (evinde) açılır; yabancı bir eve kaçması
        // o evi de "baskın altında" saymaz.
        boolean sivilinKendiClaimi = claim != null
                && claim.getOwnerID() != null
                && claim.getOwnerID().equals(sivil.getUniqueId());
        boolean baskinVar = sivilinKendiClaimi && (baskinAktifMi(claim) || claimdeArananVarMi(claim));
        boolean izin = baskinVar || adaletliSebepVarMi(sivil);

        if (!izin) {
            event.setCancelled(true);
            if (saldiran.isOnline()) {
                saldiran.sendMessage(ChatColor.RED + (saldiranPolis
                        ? "[Polis] " + magdur.getName() + " hakkında şikayet ya da aktif bir baskın yok; masum bir vatandaşa vuramazsınız."
                        : "Aktif bir baskın yokken polise vuramazsınız."));
            }
            return;
        }

        // Meşru sebep (aranan/öldürmüş/baygın bırakmış) ya da kendi evinde baskın varsa, GriefPrevention
        // vuruşu iptal etmiş olsa bile (claim içinde ya da dışında, vahşi doğada da) izin veriyoruz.
        if (event.isCancelled()) event.setCancelled(false);
        if (sivilinKendiClaimi) baskinaAktiviteKaydet(claim);

        // Aranan sivil, baskında "kendini savunma" sayacını başlatır (5 dk dayanırsa aranmalık düşer).
        if (sivilinKendiClaimi && arananOyuncular.contains(sivil.getName().toLowerCase())) {
            savunmaDurumlari.putIfAbsent(sivil.getUniqueId(), new SavunmaDurumu(claim.getID(), System.currentTimeMillis()));
        }
    }

    private static final double BASKINDA_ISI_OLMAYAN_CEZASI = 10000.0;

    /** BASKINDA İŞİ OLMAYANLAR: Bir baskın sırasında (aranan şüphelinin kendi claim'inde, polis/amir
     *  ekibi tarafından baskın aktifken), o claim'de trust/erişim hakkı olmayan (dolayısıyla korunma
     *  hakkı da olmayan) bir sivil ya da baskına dahil olmayan bir polis/amir birine vurursa (polis ↔
     *  aranan sivil vuruşu burada değil, onPolisSivilPvp'de ele alınıyor): vuruş hasar vermez, vuran
     *  kişi anında ağır yaralı/baygın düşer ve bankasından devlet kasasına 10.000$ ceza kesilir. */
    @EventHandler(priority = EventPriority.LOW)
    public void onBaskindaIsiOlmayanPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player magdur = (Player) event.getEntity();
        Player saldiran = saldiranOyuncuBul(event.getDamager());
        if (saldiran == null || saldiran.getUniqueId().equals(magdur.getUniqueId())) return;
        if (savastaMi(magdur.getUniqueId()) || savastaMi(saldiran.getUniqueId())) return;

        boolean magdurPolis = polisMi(plugin.oyuncuMeslekCache.getOrDefault(magdur.getUniqueId(), "vatandas"));
        boolean saldiranPolis = polisMi(plugin.oyuncuMeslekCache.getOrDefault(saldiran.getUniqueId(), "vatandas"));
        // Polis ↔ sivil çifti zaten onPolisSivilPvp'nin konusu; burada sadece sivil-sivil ya da
        // polis-polis (ör. baskına dahil olmayan bir memurun araya girmesi) ele alınır.
        if (magdurPolis != saldiranPolis) return;

        me.ryanhamshire.GriefPrevention.Claim claim;
        try {
            claim = me.ryanhamshire.GriefPrevention.GriefPrevention.instance
                    .dataStore.getClaimAt(magdur.getLocation(), false, null);
        } catch (Exception e) {
            claim = null;
        }
        if (claim == null || !baskinAktifMi(claim)) return; // sadece aktif bir baskının içinde geçerli

        // Vurana ceza: bu claim'de erişimi (trust'ı) olsa da, aktif baskının tarafı değilse işi yok.
        event.setCancelled(true);
        double bakiye = plugin.bankaHesaplari.getOrDefault(saldiran.getUniqueId(), 0.0);
        double kesinti = Math.min(bakiye, BASKINDA_ISI_OLMAYAN_CEZASI);
        plugin.bankaHesaplari.put(saldiran.getUniqueId(), bakiye - kesinti);
        plugin.kasayaParaEkle(kesinti);
        plugin.veriKaydet();

        if (plugin.saglikManager != null) plugin.saglikManager.anindaAgirYaraliYap(saldiran);
        saldiran.sendMessage(ChatColor.DARK_RED + "POLİS BASKININDA SİVİLİN İŞİ YOK!");
        saldiran.sendMessage(ChatColor.RED + "Bir baskına karışıp vuruş yaptığın için anında etkisiz kaldın ve bankandan $"
                + (long) kesinti + " devlet kasasına aktarıldı.");
    }

    private boolean baskinAktifMi(long claimId) {
        Long son = baskinSonAktivite.get(claimId);
        return son != null && System.currentTimeMillis() - son < BASKIN_HAREKETSIZLIK_MS;
    }

    private boolean baskinAktifMi(me.ryanhamshire.GriefPrevention.Claim claim) {
        return claim != null && baskinAktifMi(claim.getID());
    }

    private void baskinaAktiviteKaydet(me.ryanhamshire.GriefPrevention.Claim claim) {
        if (claim == null) return;
        baskinSonAktivite.put(claim.getID(), System.currentTimeMillis());
    }

    /** SaglikManager, bir oyuncu bayılınca bunu çağırır. Eğer bu bir polis/amirse ve o an bulunduğu
     *  claim'de aktif bir baskın varsa, bu polis "baskında bayılmış" sayılır: normal tedavi yöntemleri
     *  (bandaj/adrenalin/doktor/ambulans/hastane NPC'si) işe yaramaz, sadece bir meslektaşı tarafından
     *  sırtına alınıp hastane NPC'sinin bulunduğu claime fiziken götürülerek kurtarılabilir. */
    public void oyuncuBaygindaOldu(Player player) {
        String meslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas");
        try {
            me.ryanhamshire.GriefPrevention.Claim claim = me.ryanhamshire.GriefPrevention.GriefPrevention.instance
                    .dataStore.getClaimAt(player.getLocation(), false, null);
            if (claim == null || !baskinAktifMi(claim)) return;

            if (polisMi(meslek)) {
                baskindaBayilanlar.put(player.getUniqueId(), new BaskinBayginBilgisi(claim.getID()));
                player.sendMessage(ChatColor.DARK_RED + "Baskın sırasında bayıldın! Normal tedavi yöntemleri (bandaj/adrenalin/doktor/ambulans) senin üzerinde işe yaramaz.");
                player.sendMessage(ChatColor.YELLOW + "Bir meslektaşının seni sırtına alıp hastaneye götürmesini beklemelisin, ya da baskın bitene kadar dayanmalısın.");
                return;
            }

            // Aranan şüpheli kendi evinde baskın sırasında bayılırsa, ambulansla kaçamasın:
            // sadece polis tarafından yakalanarak (copla) bu durumdan çıkabilir.
            boolean kendiClaimi = claim.getOwnerID() != null && claim.getOwnerID().equals(player.getUniqueId());
            if (kendiClaimi && arananOyuncular.contains(player.getName().toLowerCase())) {
                baskindaBayginAranan.add(player.getUniqueId());
                player.sendMessage(ChatColor.DARK_RED + "Baskın sırasında bayıldın! Aranan biri olarak ambulans çağıramazsın, sadece polis tarafından yakalanabilirsin.");
            }
        } catch (Exception ignored) {
        }
    }

    /** Bu oyuncu şu an "baskında bayılmış" bir polis/amir mi? SaglikManager, normal tedavi
     *  yöntemlerini (doktor/ambulans/hastane NPC'si) buna göre engeller. */
    public boolean baskindaBayginMi(UUID uuid) {
        return baskindaBayilanlar.containsKey(uuid);
    }

    /** Bu oyuncu şu an, kendi evindeki bir baskın sırasında bayılmış aranan bir şüpheli mi?
     *  SaglikManager bunu /ambulanscagir'i engellemek için kullanır. */
    public boolean arananBaskindaBayginMi(UUID uuid) {
        return baskindaBayginAranan.contains(uuid);
    }

    /** SaglikManager.tamTedavi her çağrıldığında (ör. hapse atılma, adrenalin vb.) kaydı da temizler. */
    public void baskinBayginKaydiniSil(UUID uuid) {
        baskindaBayilanlar.remove(uuid);
        baskindaBayginAranan.remove(uuid);
    }

    /** 5 saniyede bir: aranan sivillerin 5 dakikalık "kendini savunma" süresini ve baskında bayılan
     *  polislerin hastanedeki (2 dakikalık) iyileşme sürecini kontrol eder. */
    /** Milisaniyeyi "dakika:saniye" olarak yazar, aksiyon çubuğu geri sayımları için. */
    private String sureYaz(long ms) {
        long saniyeToplam = Math.max(0, ms / 1000);
        long dk = saniyeToplam / 60;
        long sn = saniyeToplam % 60;
        return dk + ":" + (sn < 10 ? "0" + sn : String.valueOf(sn));
    }

    private void baskinGorevleriniBaslat() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long simdi = System.currentTimeMillis();

            // 1) Aranan oyuncuların kendini savunma süresi
            for (java.util.Iterator<Map.Entry<UUID, SavunmaDurumu>> it = savunmaDurumlari.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, SavunmaDurumu> giris = it.next();
                UUID uuid = giris.getKey();
                SavunmaDurumu sd = giris.getValue();
                Player p = Bukkit.getPlayer(uuid);

                if (p == null || !p.isOnline()) { it.remove(); continue; }
                if (!arananOyuncular.contains(p.getName().toLowerCase())) { it.remove(); continue; }
                if (plugin.saglikManager != null && plugin.saglikManager.agirYaraliMi(uuid)) { it.remove(); continue; }

                boolean halaAyniClaimde;
                try {
                    me.ryanhamshire.GriefPrevention.Claim mevcutClaim = me.ryanhamshire.GriefPrevention.GriefPrevention.instance
                            .dataStore.getClaimAt(p.getLocation(), false, null);
                    halaAyniClaimde = mevcutClaim != null && mevcutClaim.getID() == sd.claimId;
                } catch (Exception ex) {
                    halaAyniClaimde = false;
                }
                if (!halaAyniClaimde) { it.remove(); continue; }

                long kalanMs = SAVUNMA_SURESI_MS - (simdi - sd.baslangic);
                if (kalanMs > 0) {
                    p.sendActionBar(net.kyori.adventure.text.Component.text(
                            "⚔ Kendini savun! Aranmalık düşmesine kalan süre: " + sureYaz(kalanMs))
                            .color(net.kyori.adventure.text.format.NamedTextColor.RED));
                }

                if (simdi - sd.baslangic >= SAVUNMA_SURESI_MS) {
                    arananOyuncular.remove(p.getName().toLowerCase());
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag removesuffix " + p.getName());
                    Bukkit.broadcastMessage(ChatColor.DARK_RED + "[MERKEZ] " + ChatColor.GREEN + p.getName()
                            + ChatColor.GREEN + ", polis baskınına karşı 5 dakika boyunca kendini savunmayı başardı! Arananlık durumu düşürüldü.");
                    it.remove();
                }
            }

            // 2) Baskında bayılan polislerin hastanedeki iyileşmesi
            for (java.util.Iterator<Map.Entry<UUID, BaskinBayginBilgisi>> it = baskindaBayilanlar.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, BaskinBayginBilgisi> giris = it.next();
                UUID uuid = giris.getKey();
                BaskinBayginBilgisi bilgi = giris.getValue();
                Player p = Bukkit.getPlayer(uuid);

                if (p == null || !p.isOnline()) continue; // çevrimdışı: kayıt kalsın, girince devam etsin
                if (plugin.saglikManager == null || !plugin.saglikManager.agirYaraliMi(uuid)) { it.remove(); continue; }

                if (!baskinAktifMi(bilgi.claimId)) {
                    // Baskın sona erdi: artık normal tedavi yöntemleri (bandaj/doktor/ambulans) işe yarar.
                    it.remove();
                    p.sendMessage(ChatColor.YELLOW + "Baskın sona erdi! Artık normal yollarla (doktor/ambulans/hastane) tedavi edilebilirsin.");
                    continue;
                }

                boolean hastanede = plugin.saglikManager.lokasyonHastaneClaimindeMi(p.getLocation());
                if (!hastanede) {
                    bilgi.hastaneBaslangic = null;
                    continue;
                }
                if (bilgi.hastaneBaslangic == null) {
                    bilgi.hastaneBaslangic = simdi;
                    p.sendMessage(ChatColor.GREEN + "Hastaneye ulaştın, iyileşme süreci başladı. (" + (BASKIN_HASTANE_SURESI_MS / 1000 / 60) + " dakika)");
                    continue;
                }
                long kalanMs = BASKIN_HASTANE_SURESI_MS - (simdi - bilgi.hastaneBaslangic);
                if (kalanMs > 0) {
                    p.sendActionBar(net.kyori.adventure.text.Component.text(
                            "🏥 Tedavi sürüyor, kalan süre: " + sureYaz(kalanMs))
                            .color(net.kyori.adventure.text.format.NamedTextColor.GREEN));
                }
                if (simdi - bilgi.hastaneBaslangic >= BASKIN_HASTANE_SURESI_MS) {
                    plugin.saglikManager.tamTedavi(p);
                    p.sendMessage(ChatColor.GREEN + "Hastanede kendine geldin ve tamamen iyileştin!");
                    it.remove();
                }
            }
        }, 100L, 100L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        lpGrubunuSenkronla(player);
        if (jailedPlayers.containsKey(player.getUniqueId())) {
            esyalariAl(player);
            plugin.veriKaydet();
            int timeLeft = jailedPlayers.get(player.getUniqueId());
            startJailTimer(player, timeLeft);
            player.sendMessage(ChatColor.DARK_RED + "Cezanız devam ediyor! Kalan süre: " + Math.max(1, timeLeft / 60) + " dakika.");
            
            if (!jailCells.isEmpty()) {
                boolean isNearCell = false;
                for (Location cellLoc : jailCells.values()) {
                    if (player.getWorld().equals(cellLoc.getWorld()) && player.getLocation().distance(cellLoc) < 15) {
                        isNearCell = true; break;
                    }
                }
                if (!isNearCell) player.teleport(getBestCell());
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!jailedPlayers.containsKey(event.getPlayer().getUniqueId())) return;
        Location cell = getBestCell();
        if (cell != null) {
            event.setRespawnLocation(cell);
            event.getPlayer().sendMessage(ChatColor.RED + "Cezanız devam ediyor, hücrenize geri döndünüz.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (jailTasks.containsKey(player.getUniqueId())) {
            jailTasks.get(player.getUniqueId()).cancel(); 
            jailTasks.remove(player.getUniqueId());
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (jailedPlayers.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Hapisteyken blok kıramazsınız!");
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (jailedPlayers.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Hapisteyken blok koyamazsınız!");
        }
    }

    // Hapisteyken sadece beyaz listedeki komutlar çalışır. "/essentials:home" gibi
    // eklenti önekli yazımlar da öneki atılarak kontrol edilir.
    @EventHandler(ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!jailedPlayers.containsKey(player.getUniqueId())) return;
        if (player.hasPermission("polis.admin")) return;

        String komut = MeslekSistemi.komutAdi(event.getMessage());

        List<String> izinli = plugin.getConfig().getStringList(IZINLI_KOMUTLAR_YOLU);
        for (String izin : izinli) {
            if (izin.equalsIgnoreCase(komut)) return;
        }

        event.setCancelled(true);
        player.sendMessage(ChatColor.RED + "Hapisteyken bu komutu kullanamazsınız!");
        player.sendMessage(ChatColor.GRAY + "Kullanabileceğiniz komutlar: /" + String.join(", /", izinli));
    }
    
    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (jailedPlayers.containsKey(event.getPlayer().getUniqueId())) {
            if (event.getCause() == TeleportCause.ENDER_PEARL || event.getCause() == TeleportCause.CONSUMABLE_EFFECT) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "Hapisten bu şekilde kaçamazsınız!");
            }
        }
    }

    // Mahkeme kararı gibi dış sistemler için: oyuncu çevrimdışı olsa da ceza işlenir,
    // oyuna girdiğinde hücreye alınır. Zaten hapisteyse süre mevcut cezaya eklenir.
    public boolean hapseGonder(UUID uuid, String isim, int saniye) {
        if (saniye <= 0) return false;
        if (jailCells.isEmpty()) return false;

        if (jailedPlayers.containsKey(uuid)) {
            jailedPlayers.put(uuid, jailedPlayers.get(uuid) + saniye);
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) p.sendMessage(ChatColor.DARK_RED + "Cezanıza " + (saniye / 60) + " dakika eklendi!");
            plugin.veriKaydet();
            return true;
        }

        Player online = Bukkit.getPlayer(uuid);
        if (online != null && !savastaMi(uuid)) {
            preJailLocations.put(uuid, online.getLocation());
            jailPlayer(online, saniye);
        } else {
            // Çevrimdışı ya da arena savaşında: ceza kaydedilir, hücreye alma girişte / savaş bitince yapılır
            if (online != null) {
                online.sendMessage(ChatColor.DARK_RED + "Hapis cezası aldınız! Savaşınız bitince hücreye alınacaksınız.");
            }
            String eskiMeslek = plugin.oyuncuMeslekCache.getOrDefault(uuid, "vatandas");
            if (!eskiMeslek.equalsIgnoreCase("mahkum")) preJailRoles.put(uuid, eskiMeslek);
            jailedPlayers.put(uuid, saniye);
            plugin.oyuncuMeslekCache.put(uuid, "mahkum");
            if (isim != null) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + isim + " parent set mahkum");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag remove " + isim);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag set " + isim + " Mahkum");
            }
        }
        plugin.veriKaydet();
        return true;
    }

    /** Oyuncu arena savaşında mı? (Klan-Sistemi savaştakileri MeslekAPI ile sağlık sisteminden muaf tutar) */
    private boolean savastaMi(UUID uuid) {
        return plugin.saglikManager != null && plugin.saglikManager.muafMi(uuid);
    }

    /** Savaşı biten oyuncu savaş sırasında hapis cezası aldıysa hücreye alınır ve süresi başlar. */
    public void savasBitti(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null || !jailedPlayers.containsKey(uuid) || jailTasks.containsKey(uuid)) return;
        // Klan-Sistemi oyuncuyu geri ışınladıktan sonra çalışsın
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !jailedPlayers.containsKey(uuid) || jailTasks.containsKey(uuid)) return;
            preJailLocations.putIfAbsent(uuid, p.getLocation());
            esyalariAl(p);
            Location cell = getBestCell();
            if (cell != null) p.teleport(cell);
            startJailTimer(p, jailedPlayers.get(uuid));
            p.sendMessage(ChatColor.DARK_RED + "Savaşınız bitti, hapis cezanız başladı! Kalan süre: " + Math.max(1, jailedPlayers.get(uuid) / 60) + " dakika.");
        }, 2L);
    }

    // Hapisteki oyuncuyu hücresine geri gönderir (örn. duruşma bittikten sonra)
    public void hucreyeGonder(Player player) {
        Location cell = getBestCell();
        if (cell != null) player.teleport(cell);
    }

    /**
     * LuckPerms komutları arka planda sırasız çalışabildiği için art arda hapis/serbest bırakma işlemlerinde
     * oyuncunun grubu bozulabiliyordu (örn. başkan "default" grubuna düşüyordu). Bu yüzden son rol kısa bir
     * süre sonra ve her girişte, sadece ekleme/çıkarma yapan (diğer grupları silmeyen) komutlarla doğrulanır.
     */
    private void lpGrubunuSenkronla(Player p) {
        final UUID id = p.getUniqueId();
        final String ad = p.getName();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player online = Bukkit.getPlayer(id);
            if (online == null) return;
            String rol = plugin.oyuncuMeslekCache.get(id);
            if (rol == null || rol.isEmpty() || rol.equalsIgnoreCase("default")) return;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + ad + " parent add " + rol);
            if (!rol.equalsIgnoreCase("mahkum")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + ad + " parent remove mahkum");
            }
        }, 60L);
    }

    // ---- Mahkum eşyaları: hapse girerken geçici el koyma, çıkınca iade ----
    private void esyalariAl(Player p) {
        UUID id = p.getUniqueId();
        if (elKonulanEsyalar.containsKey(id)) return;
        ItemStack[] icerik = p.getInventory().getContents().clone();
        boolean bos = true;
        for (ItemStack it : icerik) if (it != null && it.getType() != Material.AIR) { bos = false; break; }
        ItemStack[] zirh = p.getInventory().getArmorContents().clone();
        boolean zirhBos = true;
        for (ItemStack it : zirh) if (it != null && it.getType() != Material.AIR) { zirhBos = false; break; }
        ItemStack ofHand = p.getInventory().getItemInOffHand();
        boolean ofHandBos = ofHand == null || ofHand.getType() == Material.AIR;
        if (bos && zirhBos && ofHandBos) return;

        // Zırh ve sol el de ana envanter dizisiyle birlikte tek parça olarak saklanır (36 slot + 4 zırh + 1 offhand)
        ItemStack[] tumu = new ItemStack[icerik.length + zirh.length + 1];
        System.arraycopy(icerik, 0, tumu, 0, icerik.length);
        System.arraycopy(zirh, 0, tumu, icerik.length, zirh.length);
        tumu[tumu.length - 1] = ofHand;

        elKonulanEsyalar.put(id, tumu);
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(null);
        p.updateInventory();
        p.sendMessage(ChatColor.GRAY + "Eşyalarına geçici olarak el konuldu. Cezan bitince iade edilecek.");
    }

    private void esyalariIade(Player p) {
        ItemStack[] tumu = elKonulanEsyalar.remove(p.getUniqueId());
        if (tumu == null) return;
        int zirhUzunluk = 4;
        int anaUzunluk = tumu.length - zirhUzunluk - 1;
        ItemStack[] icerik = new ItemStack[anaUzunluk];
        ItemStack[] zirh = new ItemStack[zirhUzunluk];
        System.arraycopy(tumu, 0, icerik, 0, anaUzunluk);
        System.arraycopy(tumu, anaUzunluk, zirh, 0, zirhUzunluk);
        ItemStack ofHand = tumu[tumu.length - 1];

        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.getInventory().setItemInOffHand(null);
        p.getInventory().setContents(icerik);
        p.getInventory().setArmorContents(zirh);
        p.getInventory().setItemInOffHand(ofHand);
        p.updateInventory();
        p.sendMessage(ChatColor.GREEN + "Eşyaların iade edildi.");
    }

    private void jailPlayer(Player player, int seconds) {
        // İŞTE BURASI: Oyuncu hapse girmeden hemen önce eski mesleğini hafızaya alıyoruz.
        String eskiMeslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "vatandas");
        if (!eskiMeslek.equalsIgnoreCase("mahkum")) {
            preJailRoles.put(player.getUniqueId(), eskiMeslek);
        }

        jailedPlayers.put(player.getUniqueId(), seconds);
        esyalariAl(player);
        Location cell = getBestCell();
        if (cell != null) player.teleport(cell);

        plugin.oyuncuMeslekCache.put(player.getUniqueId(), "mahkum");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player.getName() + " parent set mahkum");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag remove " + player.getName());
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag set " + player.getName() + " Mahkum");
        
        player.sendMessage(ChatColor.DARK_RED + "Hapse atıldınız! Yeni Statünüz: Mahkum. Ceza süreniz: " + (seconds / 60) + " dakika.");
        startJailTimer(player, seconds);
        lpGrubunuSenkronla(player);
        plugin.veriKaydet();
    }

    private void releasePlayer(Player player) {
        jailedPlayers.remove(player.getUniqueId());
        sonSalmaZamani.put(player.getUniqueId(), System.currentTimeMillis());
        if (jailTasks.containsKey(player.getUniqueId())) {
            jailTasks.get(player.getUniqueId()).cancel();
            jailTasks.remove(player.getUniqueId());
        }
        
        // İŞTE BURASI: Oyuncu çıkarken eski mesleğini geri alıyor
        String iadeEdilecekMeslek = preJailRoles.getOrDefault(player.getUniqueId(), "vatandas");
        preJailRoles.remove(player.getUniqueId()); // İade ettikten sonra hafızadan siliyoruz
        
        plugin.oyuncuMeslekCache.put(player.getUniqueId(), iadeEdilecekMeslek);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player.getName() + " parent set " + iadeEdilecekMeslek);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag remove " + player.getName());
        
        // Ana sınıftaki görünüm adını ("Doktor", "Polis" gibi) çekip başının üstüne onu yazdırıyoruz
        String tagAd = plugin.meslekGorunumAdlari.get(iadeEdilecekMeslek);
        if (tagAd == null) {
            if (iadeEdilecekMeslek.equalsIgnoreCase("vatandas")) tagAd = "Vatandaş";
            else if (iadeEdilecekMeslek.equalsIgnoreCase("belediyecalisani")) tagAd = "Memur";
            else tagAd = iadeEdilecekMeslek.substring(0, 1).toUpperCase() + iadeEdilecekMeslek.substring(1).toLowerCase();
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag set " + player.getName() + " " + tagAd);
        
        esyalariIade(player);
        player.sendMessage(ChatColor.GREEN + "Cezanız doldu! Eski sivil hayatınıza ve [" + tagAd + "] unvanınıza geri döndünüz!");
        lpGrubunuSenkronla(player);

        yuklenemeyenDonusKonumlari.remove(player.getUniqueId().toString());
        if (preJailLocations.containsKey(player.getUniqueId())) {
            Location returnLoc = preJailLocations.get(player.getUniqueId());
            player.teleport(returnLoc);
            preJailLocations.remove(player.getUniqueId()); 
        } else {
            player.teleport(player.getWorld().getSpawnLocation()); 
        }
        plugin.veriKaydet();
    }

    private void startJailTimer(Player player, int initialSeconds) {
        BukkitRunnable eski = jailTasks.remove(player.getUniqueId());
        if (eski != null) eski.cancel();
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) { this.cancel(); return; }
                Integer kalan = jailedPlayers.get(player.getUniqueId());
                if (kalan == null) { this.cancel(); jailTasks.remove(player.getUniqueId()); return; }
                int currentLeft = kalan;
                if (currentLeft <= 0) {
                    releasePlayer(player);
                    this.cancel();
                } else {
                    currentLeft--;
                    jailedPlayers.put(player.getUniqueId(), currentLeft);
                    if (currentLeft > 0 && currentLeft <= 5) {
                         player.sendMessage(ChatColor.YELLOW + "Tahliyenize " + currentLeft + " saniye kaldı...");
                    }
                }
            }
        };
        task.runTaskTimer(plugin, 20L, 20L); 
        jailTasks.put(player.getUniqueId(), task);
    }
    
    private Location getBestCell() {
        if (jailCells.isEmpty()) return null;
        List<Location> emptyCells = new ArrayList<>();
        for (Location cell : jailCells.values()) {
            boolean isOccupied = false;
            for (UUID uuid : jailedPlayers.keySet()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline() && p.getWorld().equals(cell.getWorld()) && p.getLocation().distance(cell) < 5.0) {
                    isOccupied = true; break;
                }
            }
            if (!isOccupied) emptyCells.add(cell);
        }
        if (!emptyCells.isEmpty()) return emptyCells.get(random.nextInt(emptyCells.size()));
        List<Location> allCells = new ArrayList<>(jailCells.values());
        return allCells.get(random.nextInt(allCells.size()));
    }

    private ItemStack createCop(int mod) {
        ItemStack cop = new ItemStack(Material.STICK);
        ItemMeta meta = cop.getItemMeta();
        String modName = mod == 1 ? "5 Dakika (Hafif Suç)" : mod == 2 ? "10 Dakika (Orta Suç)" : "30 Dakika (Ağır Suç)";
        meta.setDisplayName(ChatColor.BLUE + "" + ChatColor.BOLD + "Polis Copu");
        meta.setLore(Collections.singletonList(ChatColor.GRAY + "Mod: " + ChatColor.RED + modName));
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(this.copKey, PersistentDataType.BYTE, (byte) 1); 
        data.set(this.modKey, PersistentDataType.INTEGER, mod); 
        cop.setItemMeta(meta);
        return cop;
    }

    private ItemStack createPoliceArmorPiece(Material mat) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(Enchantment.PROTECTION, 4, true);
        meta.setUnbreakable(true);
        // Meslekten çıkınca tespit edip silebilmek için özel etiket (PDC)
        meta.getPersistentDataContainer().set(this.kitEsyaKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createPoliceSword() {
        ItemStack item = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.addEnchant(Enchantment.SHARPNESS, 5, true);
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(this.kitEsyaKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** Polis kitinden gelen (cop, zırh, kılıç) etiketli eşya mı? */
    private boolean isKitEsyasi(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        return data.has(this.kitEsyaKey, PersistentDataType.BYTE) || data.has(this.copKey, PersistentDataType.BYTE);
    }

    private boolean isCop(ItemStack item) {
        if (item == null || item.getType() != Material.STICK || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(this.copKey, PersistentDataType.BYTE);
    }
    
    private int getCopMod(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 1;
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        return data.has(this.modKey, PersistentDataType.INTEGER) ? data.get(this.modKey, PersistentDataType.INTEGER) : 1;
    }

    public void veriKaydet() {
        plugin.getConfig().set("jailed_players", null); 
        for (UUID uuid : jailedPlayers.keySet()) {
            plugin.getConfig().set("jailed_players." + uuid.toString(), jailedPlayers.get(uuid));
        }
        
        plugin.getConfig().set("pre_jail_locations", null);
        for (Map.Entry<String, Map<String, Object>> ham : yuklenemeyenDonusKonumlari.entrySet()) {
            plugin.getConfig().createSection("pre_jail_locations." + ham.getKey(), ham.getValue());
        }
        for (UUID uuid : preJailLocations.keySet()) {
            Location loc = preJailLocations.get(uuid);
            if (loc.getWorld() == null) continue;
            plugin.getConfig().set("pre_jail_locations." + uuid.toString() + ".world", loc.getWorld().getName());
            plugin.getConfig().set("pre_jail_locations." + uuid.toString() + ".x", loc.getX());
            plugin.getConfig().set("pre_jail_locations." + uuid.toString() + ".y", loc.getY());
            plugin.getConfig().set("pre_jail_locations." + uuid.toString() + ".z", loc.getZ());
        }

        // ESKİ MESLEK HAFIZASINI DA KAYDEDİYORUZ
        plugin.getConfig().set("pre_jail_roles", null);
        for (UUID uuid : preJailRoles.keySet()) {
            plugin.getConfig().set("pre_jail_roles." + uuid.toString(), preJailRoles.get(uuid));
        }

        plugin.getConfig().set("jail.cells", null);
        for (Map.Entry<String, Map<String, Object>> ham : yuklenemeyenHucreler.entrySet()) {
            if (!jailCells.containsKey(ham.getKey())) plugin.getConfig().createSection("jail.cells." + ham.getKey(), ham.getValue());
        }
        for (String cellName : jailCells.keySet()) {
            Location loc = jailCells.get(cellName);
            if (loc.getWorld() == null) continue;
            plugin.getConfig().set("jail.cells." + cellName + ".world", loc.getWorld().getName());
            plugin.getConfig().set("jail.cells." + cellName + ".x", loc.getX());
            plugin.getConfig().set("jail.cells." + cellName + ".y", loc.getY());
            plugin.getConfig().set("jail.cells." + cellName + ".z", loc.getZ());
            plugin.getConfig().set("jail.cells." + cellName + ".yaw", loc.getYaw());
            plugin.getConfig().set("jail.cells." + cellName + ".pitch", loc.getPitch());
        }

        if (amirKursuKonumu != null) {
            plugin.getConfig().set("amir_kursusu.world", amirKursuKonumu.getWorld().getName());
            plugin.getConfig().set("amir_kursusu.x", amirKursuKonumu.getX());
            plugin.getConfig().set("amir_kursusu.y", amirKursuKonumu.getY());
            plugin.getConfig().set("amir_kursusu.z", amirKursuKonumu.getZ());
        }

        plugin.getConfig().set("sikayetler", null);
        for (Sikayet s : aktifSikayetler.values()) {
            String sid = s.id.toString();
            plugin.getConfig().set("sikayetler." + sid + ".eden", s.sikayetEden);
            plugin.getConfig().set("sikayetler." + sid + ".edilen", s.sikayetEdilen);
            plugin.getConfig().set("sikayetler." + sid + ".sebep", s.sebep);
        }

        plugin.getConfig().set("arananlar", new ArrayList<>(arananOyuncular));

        List<String> kitListesi = new ArrayList<>();
        for (UUID kid : kitAlanlar) kitListesi.add(kid.toString());
        plugin.getConfig().set("polis_kit_alanlar", kitListesi);

        plugin.getConfig().set("jail_esyalar", null);
        for (Map.Entry<UUID, ItemStack[]> e : elKonulanEsyalar.entrySet()) {
            try {
                plugin.getConfig().set("jail_esyalar." + e.getKey(),
                        java.util.Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(e.getValue())));
            } catch (Exception ex) {
                plugin.getLogger().warning("[Polis] " + e.getKey() + " için el konulan eşyalar kaydedilemedi: " + ex.getMessage());
            }
        }

        plugin.saveConfig();
    }

    public void veriYukle() {
        if (plugin.getConfig().contains("jail.cells")) {
            for (String cellName : plugin.getConfig().getConfigurationSection("jail.cells").getKeys(false)) {
                ConfigurationSection hucre = plugin.getConfig().getConfigurationSection("jail.cells." + cellName);
                org.bukkit.World dunya = hucre == null ? null : Bukkit.getWorld(hucre.getString("world", ""));
                if (dunya == null) {
                    if (hucre != null) yuklenemeyenHucreler.put(cellName, hucre.getValues(false));
                    plugin.getLogger().warning("[Polis] '" + cellName + "' hücresinin dünyası yüklü değil, hücre şimdilik kullanılmayacak.");
                    continue;
                }
                Location loc = new Location(
                    dunya,
                    plugin.getConfig().getDouble("jail.cells." + cellName + ".x"),
                    plugin.getConfig().getDouble("jail.cells." + cellName + ".y"),
                    plugin.getConfig().getDouble("jail.cells." + cellName + ".z"),
                    (float) plugin.getConfig().getDouble("jail.cells." + cellName + ".yaw"),
                    (float) plugin.getConfig().getDouble("jail.cells." + cellName + ".pitch")
                );
                jailCells.put(cellName, loc);
            }
        }
        
        if (plugin.getConfig().contains("jailed_players")) {
            for (String uuidStr : plugin.getConfig().getConfigurationSection("jailed_players").getKeys(false)) {
                jailedPlayers.put(UUID.fromString(uuidStr), plugin.getConfig().getInt("jailed_players." + uuidStr));
            }
        }
        
        if (plugin.getConfig().contains("pre_jail_locations")) {
             for (String uuidStr : plugin.getConfig().getConfigurationSection("pre_jail_locations").getKeys(false)) {
                 ConfigurationSection konum = plugin.getConfig().getConfigurationSection("pre_jail_locations." + uuidStr);
                 org.bukkit.World dunya = konum == null ? null : Bukkit.getWorld(konum.getString("world", ""));
                 if (dunya == null) {
                     if (konum != null) yuklenemeyenDonusKonumlari.put(uuidStr, konum.getValues(false));
                     continue;
                 }
                 Location loc = new Location(
                     dunya,
                     plugin.getConfig().getDouble("pre_jail_locations." + uuidStr + ".x"),
                     plugin.getConfig().getDouble("pre_jail_locations." + uuidStr + ".y"),
                     plugin.getConfig().getDouble("pre_jail_locations." + uuidStr + ".z")
                 );
                 preJailLocations.put(UUID.fromString(uuidStr), loc);
             }
        }

        // ESKİ MESLEK HAFIZASINI SUNUCU AÇILIRKEN YÜKLÜYORUZ
        if (plugin.getConfig().contains("pre_jail_roles")) {
            for (String uuidStr : plugin.getConfig().getConfigurationSection("pre_jail_roles").getKeys(false)) {
                preJailRoles.put(UUID.fromString(uuidStr), plugin.getConfig().getString("pre_jail_roles." + uuidStr));
            }
        }

        if (plugin.getConfig().contains("jail_esyalar")) {
            ConfigurationSection esyaBolumu = plugin.getConfig().getConfigurationSection("jail_esyalar");
            if (esyaBolumu != null) {
                for (String uuidStr : esyaBolumu.getKeys(false)) {
                    try {
                        byte[] b = java.util.Base64.getDecoder().decode(plugin.getConfig().getString("jail_esyalar." + uuidStr));
                        elKonulanEsyalar.put(UUID.fromString(uuidStr), ItemStack.deserializeItemsFromBytes(b));
                    } catch (Exception ex) {
                        plugin.getLogger().warning("[Polis] " + uuidStr + " için el konulan eşyalar yüklenemedi: " + ex.getMessage());
                    }
                }
            }
        }

        if (plugin.getConfig().contains("amir_kursusu.world")) {
            org.bukkit.World w = Bukkit.getWorld(plugin.getConfig().getString("amir_kursusu.world"));
            if (w != null) {
                amirKursuKonumu = new Location(w, plugin.getConfig().getDouble("amir_kursusu.x"), plugin.getConfig().getDouble("amir_kursusu.y"), plugin.getConfig().getDouble("amir_kursusu.z"));
            }
        }

        if (plugin.getConfig().contains("sikayetler")) {
            for (String sid : plugin.getConfig().getConfigurationSection("sikayetler").getKeys(false)) {
                UUID id = UUID.fromString(sid);
                String eden = plugin.getConfig().getString("sikayetler." + sid + ".eden");
                String edilen = plugin.getConfig().getString("sikayetler." + sid + ".edilen");
                String sebep = plugin.getConfig().getString("sikayetler." + sid + ".sebep");
                aktifSikayetler.put(id, new Sikayet(id, eden, edilen, sebep));
            }
        }

        if (plugin.getConfig().contains("arananlar")) {
            arananOyuncular.addAll(plugin.getConfig().getStringList("arananlar"));
        }

        for (String kidStr : plugin.getConfig().getStringList("polis_kit_alanlar")) {
            try {
                kitAlanlar.add(UUID.fromString(kidStr));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("[Polis] Geçersiz kit kaydı atlandı: " + kidStr);
            }
        }
    }

    public static class Sikayet {
        UUID id; String sikayetEden; String sikayetEdilen; String sebep;
        public Sikayet(UUID id, String eden, String edilen, String sebep) {
            this.id = id; this.sikayetEden = eden; this.sikayetEdilen = edilen; this.sebep = sebep;
        }
    }
}