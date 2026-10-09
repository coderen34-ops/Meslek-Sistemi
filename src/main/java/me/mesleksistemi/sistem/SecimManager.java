package me.mesleksistemi.sistem;

import me.mesleksistemi.MeslekSistemi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public class SecimManager implements Listener, CommandExecutor {

    private final MeslekSistemi plugin;
    private final NamespacedKey ballotKey;

    private boolean secimAktif = false;
    private Location sandikKonumu = null;
    private List<String> adaylar = new ArrayList<>();
    private HashMap<String, Integer> oylar = new HashMap<>();
    private List<UUID> oyKullananlar = new ArrayList<>();
    private List<UUID> pusulaAlanlar = new ArrayList<>();
    
    // YENİ: Otomatik bitirme zamanlayıcısı
    private BukkitTask secimTimerTask = null;
    private static final long SECIM_SURESI_MS = 10 * 60 * 1000L;
    // Sandıkların kapanacağı an (yeniden başlatmada zamanlayıcı buradan devam eder)
    private long secimBitisZamani = 0L;
    // Her seçimin kimliği: pusulalar sadece çıktıkları seçimde geçerlidir
    private long secimId = 0L;
    
    // OYLAMA DEĞİŞKENLERİ
    public long sonSecimBitisZamani = 0L;
    public long sonBasarisizOylamaZamani = 0L; 
    private boolean oylamaAktif = false;
    private int evetOylari = 0;
    private int hayirOylari = 0;
    private HashSet<UUID> oylamaKullananlar = new HashSet<>();

    public SecimManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.ballotKey = new NamespacedKey(plugin, "ballot_paper");
        
        veriYukle();

        // Sunucu seçim sırasında kapandıysa sandıklar kalan sürenin sonunda kapanır
        if (secimAktif) {
            if (secimBitisZamani <= 0) secimBitisZamani = System.currentTimeMillis() + SECIM_SURESI_MS;
            secimZamanlayicisiKur();
        }
    }

    private void secimZamanlayicisiKur() {
        if (secimTimerTask != null) secimTimerTask.cancel();
        long kalanTick = Math.max(20L, (secimBitisZamani - System.currentTimeMillis()) / 50L);
        secimTimerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (secimAktif) {
                    bitirSecim(true); // Süre dolunca otomatik bittiğini belirtiyoruz
                }
            }
        }.runTaskLater(plugin, kalanTick);
    }

    public void onDisable() {
        if (secimTimerTask != null) secimTimerTask.cancel();
        veriKaydet();
    }

    private UUID getGercekBaskan() {
        if (plugin.oyuncuMeslekCache != null) {
            for (Map.Entry<UUID, String> entry : plugin.oyuncuMeslekCache.entrySet()) {
                if (entry.getValue().equalsIgnoreCase("BelediyeBaskani") || entry.getValue().equalsIgnoreCase("Belediyebaskani")) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    // Başkan bu süre kadar çevrimdışı kalırsa oylama bypass edilebilir (varsayılan 60 dk; eskiden 5 dk idi)
    private long bypassCevrimdisiMs() {
        double dk = plugin.getConfig().getDouble("secim-bypass-cevrimdisi-dakika", 60.0);
        if (!Double.isFinite(dk) || dk < 1.0) dk = 60.0;
        return (long) (dk * 60_000L);
    }

    private boolean isBypassAktif() {
        UUID baskanUUID = getGercekBaskan();
        
        if (baskanUUID == null) {
            return true;
        }
        
        Player baskan = Bukkit.getPlayer(baskanUUID);
        if (baskan != null && baskan.isOnline()) {
            if (!baskan.hasPermission("meslek.baskan")) {
                return true; 
            }
            return false; 
        }
        
        long sonGorulme = Bukkit.getOfflinePlayer(baskanUUID).getLastSeen();
        if (sonGorulme == 0) return true; 
        
        long offlineSure = System.currentTimeMillis() - sonGorulme;
        if (offlineSure < 0) offlineSure = 0; 
        
        return offlineSure >= bypassCevrimdisiMs(); // config: secim-bypass-cevrimdisi-dakika
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        
        if (command.getName().equalsIgnoreCase("secimoylama")) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;

            if (secimAktif) {
                player.sendMessage(ChatColor.RED + "Şu anda zaten bir seçim süreci devam ediyor!");
                return true;
            }

            boolean baskanYok = isBypassAktif();

            if (args.length == 0 && !oylamaAktif && !baskanYok) {
                UUID baskanUUID = getGercekBaskan();
                if (baskanUUID != null) {
                    Player baskan = Bukkit.getPlayer(baskanUUID);
                    if (baskan == null || !baskan.isOnline()) {
                        long sonGorulme = Bukkit.getOfflinePlayer(baskanUUID).getLastSeen();
                        if (sonGorulme > 0) {
                            long offlineSure = System.currentTimeMillis() - sonGorulme;
                            if (offlineSure < 0) offlineSure = 0;
                            
                            if (offlineSure < bypassCevrimdisiMs()) { // tolerans süresi içinde
                                long kalanSaniyeTop = (bypassCevrimdisiMs() - offlineSure) / 1000L;
                                long kalanDakika = kalanSaniyeTop / 60L;
                                long kalanSaniye = kalanSaniyeTop % 60L;
                                player.sendMessage(ChatColor.RED + "Başkan şu an çevrimdışı ancak tolerans süresinde!");
                                player.sendMessage(ChatColor.GRAY + "Bypass için " + kalanDakika + " dakika " + kalanSaniye + " saniye daha dönmemesi gerekiyor.");
                                return true;
                            }
                        }
                    }
                }

                long kalanSecimZamanMillis = (2 * 60 * 60 * 1000L) - (System.currentTimeMillis() - sonSecimBitisZamani);
                if (kalanSecimZamanMillis > 0) {
                    long kalanSaat = kalanSecimZamanMillis / 3600000L;
                    long kalanDakika = (kalanSecimZamanMillis / 60000L) % 60L;
                    String sureMesaji = (kalanSaat > 0) ? kalanSaat + " saat " + kalanDakika + " dakika" : kalanDakika + " dakika";
                    player.sendMessage(ChatColor.RED + "Yeni bir seçim başlatmak için " + sureMesaji + " beklemelisiniz.");
                    return true;
                }

                long kalanOylamaZamanMillis = (60 * 60 * 1000L) - (System.currentTimeMillis() - sonBasarisizOylamaZamani);
                if (kalanOylamaZamanMillis > 0) {
                    long kalanDakika = kalanOylamaZamanMillis / 60000L;
                    long kalanSaniye = (kalanOylamaZamanMillis / 1000L) % 60L;
                    player.sendMessage(ChatColor.RED + "Bir önceki oylama reddedildi! Tekrar oylama başlatmak için " + kalanDakika + " dakika " + kalanSaniye + " saniye beklemelisiniz.");
                    return true;
                }
            }

            if (args.length == 1) {
                if (!oylamaAktif) {
                    player.sendMessage(ChatColor.RED + "Şu anda aktif bir seçim oylaması yok.");
                    return true;
                }
                if (oylamaKullananlar.contains(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "Zaten oy kullandınız!");
                    return true;
                }

                if (args[0].equalsIgnoreCase("evet")) {
                    oylamaKullananlar.add(player.getUniqueId());
                    evetOylari++;
                    player.sendMessage(ChatColor.GREEN + "Seçim yapılması için EVET oyu verdiniz.");
                    checkOylamaSonucu(); 
                    return true;
                } 
                else if (args[0].equalsIgnoreCase("hayir") || args[0].equalsIgnoreCase("hayır")) {
                    oylamaKullananlar.add(player.getUniqueId());
                    hayirOylari++;
                    player.sendMessage(ChatColor.RED + "Seçim yapılmasına karşı çıkarak HAYIR oyu verdiniz.");
                    return true;
                } 
                else {
                    player.sendMessage(ChatColor.RED + "Hatalı kullanım! Lütfen '/secimoylama evet' veya '/secimoylama hayir' yazın.");
                    return true;
                }
            }

            if (oylamaAktif) {
                player.sendMessage(ChatColor.RED + "Şu anda zaten bir oylama devam ediyor. Katılmak için: /secimoylama <evet/hayir>");
                return true;
            }

            oylamaAktif = true;
            evetOylari = 1; 
            hayirOylari = 0;
            oylamaKullananlar.clear();
            oylamaKullananlar.add(player.getUniqueId());

            Bukkit.broadcastMessage(ChatColor.GOLD + "======================================");
            if (baskanYok) {
                Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + " DİKKAT: BAŞKANSIZLIK KRİZİ!");
                Bukkit.broadcastMessage(ChatColor.YELLOW + " Sunucuda aktif başkan olmadığı için cezalar kalktı ve erken seçim talebi başlatıldı!");
            } else {
                Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + " DİKKAT: HALK SEÇİM TALEBİ BAŞLATTI!");
            }
            Bukkit.broadcastMessage(ChatColor.YELLOW + " Başlatan: " + player.getName());
            Bukkit.broadcastMessage(ChatColor.YELLOW + " Desteklemek için: " + ChatColor.GREEN + "/secimoylama evet");
            Bukkit.broadcastMessage(ChatColor.YELLOW + " Reddetmek için: " + ChatColor.RED + "/secimoylama hayir");
            Bukkit.broadcastMessage(ChatColor.GRAY + " (%51 evet oyu gerek. Süre: 60 saniye)");
            Bukkit.broadcastMessage(ChatColor.GOLD + "======================================");

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (oylamaAktif) {
                        checkOylamaSonucuFinal();
                    }
                }
            }.runTaskLater(plugin, 1200L); 

            return true;
        }

        if (command.getName().equalsIgnoreCase("adayol")) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;

            if (!secimAktif) {
                player.sendMessage(ChatColor.RED + "Su anda aktif bir secim sureci bulunmuyor.");
                return true;
            }
            if (adaylar.contains(player.getName())) {
                player.sendMessage(ChatColor.YELLOW + "Zaten adaysin!");
                return true;
            }

            double adaylikUcreti = 5000.0;
            if (processPayment(player, adaylikUcreti)) {
                adaylar.add(player.getName());
                oylar.put(player.getName(), 0);
                veriKaydet();
                Bukkit.broadcastMessage(ChatColor.GOLD + "★ " + ChatColor.GREEN + player.getName() + ChatColor.YELLOW + " baskanlik icin resmen aday oldu!");
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("pusulaal")) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;
            
            if (!secimAktif) {
                player.sendMessage(ChatColor.RED + "Secim sureci henuz baslamadi. Sandiklar kuruldugunda pusulani alabilirsin.");
                return true;
            }
            if (pusulaAlanlar.contains(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "2. kez oy pusulasi alinamaz!");
                return true;
            }

            player.getInventory().addItem(createBallot());
            pusulaAlanlar.add(player.getUniqueId());
            veriKaydet();
            player.sendMessage(ChatColor.GREEN + "Muhurlu Oy Pusulan envanterine eklendi!");
            return true;
        }

        if (command.getName().equalsIgnoreCase("secim")) {
            if (!sender.hasPermission("secim.admin")) {
                sender.sendMessage(ChatColor.RED + "Yetkin yok.");
                return true;
            }
            if (args.length == 0) {
                sender.sendMessage(ChatColor.RED + "Kullanim: /secim <baslat|bitir|sandikkur>");
                return true;
            }

            if (args[0].equalsIgnoreCase("baslat")) {
                if (secimAktif) {
                    sender.sendMessage(ChatColor.RED + "Secim zaten devam ediyor.");
                    return true;
                }
                baslatSecim();
            } 
            else if (args[0].equalsIgnoreCase("bitir")) {
                if (!secimAktif) {
                    sender.sendMessage(ChatColor.RED + "Su anda bitirilecek bir secim sureci yok.");
                    return true;
                }
                bitirSecim(false); // Manuel olarak bitirildi
            }
            else if (args[0].equalsIgnoreCase("sandikkur")) {
                if (!(sender instanceof Player)) return true;
                Player p = (Player) sender;
                org.bukkit.block.Block targetBlock = p.getTargetBlockExact(5);
                
                if (targetBlock != null) {
                    sandikKonumu = targetBlock.getLocation();
                    veriKaydet();
                    p.sendMessage(ChatColor.GREEN + "Secim sandigi basariyla baktigin bloga kuruldu.");
                } else {
                    p.sendMessage(ChatColor.RED + "Gecerli bir bloga bakmalisin.");
                }
            }
            return true;
        }

        return false;
    }

    private void checkOylamaSonucu() {
        int onlineCount = Bukkit.getOnlinePlayers().size();
        if (onlineCount == 0) return;
        double yuzde = ((double) evetOylari / onlineCount) * 100.0;
        
        if (yuzde >= 51.0) {
            oylamaAktif = false;
            Bukkit.broadcastMessage(ChatColor.GREEN + "Seçim oylaması %" + String.format("%.1f", yuzde) + " evet oyuyla KABUL EDİLDİ!");
            baslatSecim();
        }
    }

    private void checkOylamaSonucuFinal() {
        if (!oylamaAktif) return;
        oylamaAktif = false;
        
        int onlineCount = Bukkit.getOnlinePlayers().size();
        double yuzde = onlineCount > 0 ? ((double) evetOylari / onlineCount) * 100.0 : 0.0;
        
        boolean baskanYok = isBypassAktif();

        if (yuzde >= 51.0) {
            Bukkit.broadcastMessage(ChatColor.GREEN + "Seçim oylaması KABUL EDİLDİ!");
            baslatSecim();
        } else if (baskanYok) {
            Bukkit.broadcastMessage(ChatColor.YELLOW + "Oylama reddedildi ANCAK başkan olmadığı için seçime İZİN VERİLDİ!");
            baslatSecim();
        } else {
            Bukkit.broadcastMessage(ChatColor.RED + "Seçim oylaması REDDEDİLDİ. (1 Saat Ceza Başladı)");
            sonBasarisizOylamaZamani = System.currentTimeMillis();
            veriKaydet();
        }
    }

    private void baslatSecim() {
        secimAktif = true;
        secimId = System.currentTimeMillis();
        secimBitisZamani = secimId + SECIM_SURESI_MS;
        
        // YENİ: Mevcut başkanı bulup otomatik aday yapma işlemi (Ücretsiz)
        UUID mevcutBaskan = getGercekBaskan();
        if (mevcutBaskan != null) {
            org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(mevcutBaskan);
            if (op.getName() != null && !adaylar.contains(op.getName())) {
                adaylar.add(op.getName());
                oylar.put(op.getName(), 0);
                Bukkit.broadcastMessage(ChatColor.GOLD + "★ " + ChatColor.GREEN + op.getName() + ChatColor.YELLOW + " mevcut baskan oldugu icin otomatik aday gosterildi!");
            }
        }
        
        veriKaydet();
        Bukkit.broadcastMessage(ChatColor.AQUA + "======================================");
        Bukkit.broadcastMessage(ChatColor.GOLD + "      " + ChatColor.BOLD + "BUYUK SECIM SURECI BASLADI");
        Bukkit.broadcastMessage(ChatColor.YELLOW + " ► $5.000 ile /adayol");
        Bukkit.broadcastMessage(ChatColor.YELLOW + " ► Oy icin /pusulaal");
        Bukkit.broadcastMessage(ChatColor.RED + " ► Sandiklar 10 dakika sonra otomatik kapanacaktir!");
        Bukkit.broadcastMessage(ChatColor.AQUA + "======================================");

        // YENİ: 10 Dakikalık otomatik seçim bitirme zamanlayıcısı
        secimZamanlayicisiKur();
    }

    // YENİ: Oyları sayıp başkanı belirleyen kodları temiz bir metoda ayırdık
    private void bitirSecim(boolean otomatikMi) {
        if (!secimAktif) return;
        secimAktif = false;
        secimBitisZamani = 0L;
        
        // Zamanlayıcıyı iptal et (eğer admin manuel bitirdiyse arkadan tekrar bitirmesin diye)
        if (secimTimerTask != null) {
            secimTimerTask.cancel();
            secimTimerTask = null;
        }

        String kazanan = "Yok (Kimse oy almadi)";
        int maxOy = 0;
        boolean beraberlik = false;
        
        for (Map.Entry<String, Integer> entry : oylar.entrySet()) {
            if (entry.getValue() > maxOy) {
                maxOy = entry.getValue();
                kazanan = entry.getKey();
                beraberlik = false;
            } else if (entry.getValue() == maxOy && maxOy > 0) {
                beraberlik = true;
            }
        }
        
        Bukkit.broadcastMessage(ChatColor.GOLD + "▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄▄");
        Bukkit.broadcastMessage(ChatColor.YELLOW + " ");
        if (otomatikMi) {
            Bukkit.broadcastMessage(ChatColor.WHITE + "        SURE DOLDU! SANDIKLAR KAPANDI");
        } else {
            Bukkit.broadcastMessage(ChatColor.WHITE + "        SECIM SANDIKLARI KAPANDI");
        }
        Bukkit.broadcastMessage(ChatColor.YELLOW + " ");
        
        if (beraberlik) {
            Bukkit.broadcastMessage(ChatColor.RED + "  Sonuc: " + ChatColor.WHITE + "Beraberlik! Secim tekrarlanmali.");
            Bukkit.broadcastMessage(ChatColor.GRAY + "  (30 dakika sonra yeni oylama baslatilabilir)");
            Bukkit.broadcastMessage(ChatColor.YELLOW + " ");
            Bukkit.broadcastMessage(ChatColor.GOLD + "▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀");
            sonSecimBitisZamani = System.currentTimeMillis() - (90 * 60 * 1000L); // 30 Dk Ceza
        } else if (maxOy == 0) {
            Bukkit.broadcastMessage(ChatColor.RED + "  Sonuc: " + ChatColor.WHITE + "Kimseye oy atilmadi.");
            Bukkit.broadcastMessage(ChatColor.GRAY + "  (30 dakika sonra yeni oylama baslatilabilir)");
            Bukkit.broadcastMessage(ChatColor.YELLOW + " ");
            Bukkit.broadcastMessage(ChatColor.GOLD + "▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀");
            sonSecimBitisZamani = System.currentTimeMillis() - (90 * 60 * 1000L); // 30 Dk Ceza
        } else {
            sonSecimBitisZamani = System.currentTimeMillis(); // Tam 2 Saat Ceza
            Bukkit.broadcastMessage(ChatColor.GREEN + "  SECILEN BASKAN: " + ChatColor.GOLD + ChatColor.BOLD + kazanan);
            Bukkit.broadcastMessage(ChatColor.YELLOW + "  Toplam Alinan Oy: " + ChatColor.WHITE + maxOy);
            Bukkit.broadcastMessage(ChatColor.GRAY + "  (Yetki devri 1 dakika icinde gerceklesecektir...)");
            Bukkit.broadcastMessage(ChatColor.YELLOW + " ");
            Bukkit.broadcastMessage(ChatColor.GOLD + "▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀▀");

            final String yeniBaskan = kazanan;
            
            new BukkitRunnable() {
                @Override
                public void run() {
                    List<UUID> silinecekBaskanlar = new ArrayList<>();
                    
                    if (plugin.oyuncuMeslekCache != null) {
                        for (Map.Entry<UUID, String> entry : plugin.oyuncuMeslekCache.entrySet()) {
                            if (entry.getValue().equalsIgnoreCase("BelediyeBaskani") || entry.getValue().equalsIgnoreCase("Belediyebaskani")) {
                                org.bukkit.OfflinePlayer p = Bukkit.getOfflinePlayer(entry.getKey());
                                if (p.getName() == null || !p.getName().equalsIgnoreCase(yeniBaskan)) {
                                    silinecekBaskanlar.add(entry.getKey());
                                }
                            }
                        }
                    }
                    
                    for (UUID eskiUUID : silinecekBaskanlar) {
                        org.bukkit.OfflinePlayer eskiPlayer = Bukkit.getOfflinePlayer(eskiUUID);
                        String eskiIsim = eskiPlayer.getName();
                        if (eskiIsim != null) {
                            if (eskiPlayer.isOnline()) {
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "meslekata " + eskiIsim + " vatandas");
                            } else {
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + eskiIsim + " parent set vatandas");
                                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag set " + eskiIsim + " vatandas");
                                plugin.oyuncuMeslekCache.put(eskiUUID, "vatandas");
                            }
                        }
                    }
                    
                    if (!yeniBaskan.contains("Yok")) {
                        Player yeniPlayer = Bukkit.getPlayerExact(yeniBaskan);
                        if (yeniPlayer != null && yeniPlayer.isOnline()) {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "meslekata " + yeniBaskan + " belediyebaskani");
                        } else {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + yeniBaskan + " parent set belediyebaskani");
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag set " + yeniBaskan + " belediyebaskani");
                            org.bukkit.OfflinePlayer yp = Bukkit.getOfflinePlayer(yeniBaskan);
                            if (plugin.oyuncuMeslekCache != null) {
                                plugin.oyuncuMeslekCache.put(yp.getUniqueId(), "belediyebaskani");
                            }
                        }
                        
                        Bukkit.broadcastMessage(ChatColor.GOLD + "★ " + ChatColor.GREEN + yeniBaskan + ChatColor.YELLOW + " resmen Belediye Baskani gorevine baslamistir! ★");
                    }
                    
                    plugin.veriKaydet();
                }
            }.runTaskLater(plugin, 1200L);
        }
        
        adaylar.clear();
        oylar.clear();
        oyKullananlar.clear();
        pusulaAlanlar.clear();
        veriKaydet();
    }

    @EventHandler
    public void onSandikClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (sandikKonumu == null || event.getClickedBlock() == null) return;
        
        if (event.getClickedBlock().getLocation().equals(sandikKonumu)) {
            event.setCancelled(true);
            Player player = event.getPlayer();

            if (!secimAktif) {
                player.sendMessage(ChatColor.RED + "Sandiklar kapali.");
                return;
            }
            if (oyKullananlar.contains(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "Zaten oy kullandin!");
                return;
            }
            if (!hasBallot(player)) {
                player.sendMessage(ChatColor.RED + "Envanterinde Oy Pusulasi olmali! (/pusulaal)");
                return;
            }
            if (adaylar.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Hic aday yok.");
                return;
            }
            openVotingGUI(player);
        }
    }

    private void openVotingGUI(Player player) {
        int size = ((adaylar.size() / 9) + 1) * 9;
        if (size < 9) size = 9;
        Inventory gui = Bukkit.createInventory(null, size, ChatColor.DARK_BLUE + "Oy Pusulasi");

        for (int i = 0; i < adaylar.size(); i++) {
            String adayAdi = adaylar.get(i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(adayAdi));
            meta.setDisplayName(ChatColor.YELLOW + adayAdi);
            meta.setLore(Collections.singletonList(ChatColor.GRAY + "Oy vermek icin tikla"));
            head.setItemMeta(meta);
            gui.setItem(i, head);
        }
        player.openInventory(gui);
    }

    @EventHandler
    public void onVotingClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals(ChatColor.DARK_BLUE + "Oy Pusulasi")) {
            event.setCancelled(true);
            // Sadece sandıktaki adaylar: kendi envanterindeki (isim verilmiş) kafalar oy sayılmaz
            if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
            Player player = (Player) event.getWhoClicked();
            ItemStack clickedItem = event.getCurrentItem();

            if (clickedItem == null || clickedItem.getType() != Material.PLAYER_HEAD) return;
            
            String secilenAday = ChatColor.stripColor(clickedItem.getItemMeta().getDisplayName());
            if (!secimAktif || !adaylar.contains(secilenAday)) {
                player.closeInventory();
                return;
            }

            if (oyKullananlar.contains(player.getUniqueId())) {
                player.closeInventory();
                return; 
            }

            if (consumeBallot(player)) {
                oylar.put(secilenAday, oylar.getOrDefault(secilenAday, 0) + 1);
                oyKullananlar.add(player.getUniqueId());
                veriKaydet();
                
                player.closeInventory();
                player.sendMessage(ChatColor.GREEN + "Oyunuz basariyla " + secilenAday + " isimli adaya atildi!");
            }
        }
    }

    private ItemStack createBallot() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Muhurlu Oy Pusulasi");
        meta.setLore(Collections.singletonList(ChatColor.GRAY + "Bu belge ile sandikta oy kullanabilirsiniz."));
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(ballotKey, PersistentDataType.LONG, secimId);
        item.setItemMeta(meta);
        return item;
    }

    // Pusula sadece bu seçimde verildiyse geçerlidir (önceki seçimlerden kalanlar sayılmaz)
    private boolean gecerliPusulaMi(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        // Eski sürümün pusulaları farklı tipte saklanıyordu; tip uymazsa geçersiz sayılır
        if (!data.has(ballotKey, PersistentDataType.LONG)) return false;
        Long id = data.get(ballotKey, PersistentDataType.LONG);
        return id != null && id == secimId;
    }

    private boolean hasBallot(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (gecerliPusulaMi(item)) return true;
        }
        return false;
    }

    private boolean consumeBallot(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (gecerliPusulaMi(item)) {
                item.setAmount(item.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    // Adaylık ücreti oyuncunun kağıt parasından alınıp Belediye Kasası'na aktarılır
    private boolean processPayment(Player player, double price) {
        return plugin.processPaymentToKasa(player, price);
    }

    private void veriKaydet() {
        plugin.getConfig().set("secim.secimAktif", secimAktif);
        plugin.getConfig().set("secim.bitisZamani", secimBitisZamani);
        plugin.getConfig().set("secim.id", secimId);
        
        if (sandikKonumu != null) {
            plugin.getConfig().set("secim.sandik.world", sandikKonumu.getWorld().getName());
            plugin.getConfig().set("secim.sandik.x", sandikKonumu.getX());
            plugin.getConfig().set("secim.sandik.y", sandikKonumu.getY());
            plugin.getConfig().set("secim.sandik.z", sandikKonumu.getZ());
        }
        
        plugin.getConfig().set("secim.adaylar", adaylar);
        plugin.getConfig().set("secim.oylar", null);
        for (String aday : adaylar) {
            plugin.getConfig().set("secim.oylar." + aday, oylar.getOrDefault(aday, 0));
        }
        
        List<String> oyKullananListesi = new ArrayList<>();
        for (UUID uuid : oyKullananlar) {
            oyKullananListesi.add(uuid.toString());
        }
        plugin.getConfig().set("secim.oyKullananlar", oyKullananListesi);

        List<String> pusulaAlanListesi = new ArrayList<>();
        for (UUID uuid : pusulaAlanlar) {
            pusulaAlanListesi.add(uuid.toString());
        }
        plugin.getConfig().set("secim.pusulaAlanlar", pusulaAlanListesi);
        
        plugin.getConfig().set("secim.sonSecimBitisZamani", sonSecimBitisZamani);
        plugin.getConfig().set("secim.sonBasarisizOylamaZamani", sonBasarisizOylamaZamani);
        
        plugin.saveConfig();
    }

    private void veriYukle() {
        secimAktif = plugin.getConfig().getBoolean("secim.secimAktif", false);
        secimBitisZamani = plugin.getConfig().getLong("secim.bitisZamani", 0L);
        secimId = plugin.getConfig().getLong("secim.id", 0L);
        sonSecimBitisZamani = plugin.getConfig().getLong("secim.sonSecimBitisZamani", 0L);
        sonBasarisizOylamaZamani = plugin.getConfig().getLong("secim.sonBasarisizOylamaZamani", 0L);
        
        if (plugin.getConfig().contains("secim.sandik.world")) {
            org.bukkit.World world = Bukkit.getWorld(plugin.getConfig().getString("secim.sandik.world"));
            double x = plugin.getConfig().getDouble("secim.sandik.x");
            double y = plugin.getConfig().getDouble("secim.sandik.y");
            double z = plugin.getConfig().getDouble("secim.sandik.z");
            if (world != null) {
                sandikKonumu = new Location(world, x, y, z);
            }
        }
        
        if (plugin.getConfig().contains("secim.adaylar")) {
            adaylar = plugin.getConfig().getStringList("secim.adaylar");
            if (plugin.getConfig().contains("secim.oylar")) {
                for (String aday : adaylar) {
                    oylar.put(aday, plugin.getConfig().getInt("secim.oylar." + aday, 0));
                }
            }
        }
        
        if (plugin.getConfig().contains("secim.oyKullananlar")) {
            List<String> uuidListesi = plugin.getConfig().getStringList("secim.oyKullananlar");
            for (String uuidStr : uuidListesi) {
                try {
                    oyKullananlar.add(UUID.fromString(uuidStr));
                } catch (Exception ignored) {}
            }
        }

        if (plugin.getConfig().contains("secim.pusulaAlanlar")) {
            List<String> uuidListesi = plugin.getConfig().getStringList("secim.pusulaAlanlar");
            for (String uuidStr : uuidListesi) {
                try {
                    pusulaAlanlar.add(UUID.fromString(uuidStr));
                } catch (Exception ignored) {}
            }
        }
    }
}