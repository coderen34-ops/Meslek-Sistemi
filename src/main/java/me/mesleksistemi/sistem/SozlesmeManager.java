package me.mesleksistemi.sistem;

import me.mesleksistemi.MeslekSistemi;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

public class SozlesmeManager implements Listener, CommandExecutor {

    private final MeslekSistemi plugin;
    
    private final Map<UUID, SozlesmeTaslagi> taslaklar = new HashMap<>();
    private final Map<String, AktifSozlesme> aktifSozlesmeler = new HashMap<>();

    public SozlesmeManager(MeslekSistemi plugin) {
        this.plugin = plugin;
    }

    private static class SozlesmeTaslagi {
        int asama = 1;
        String isciIsmi = "";
        double tutar = 0.0;
        String detay = "";
        String tip = "NORMAL"; 
    }

    private static class AktifSozlesme {
        String isverenAdi;
        UUID isverenUUID;
        String isciAdi;
        UUID isciUUID;
        double tutar;
        String detay;
        String durum; // TEKLIF, BEKLIYOR, ONAY_BEKLIYOR
        String tip; 
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("sozlesmeolustur")) {
            SozlesmeTaslagi taslak = new SozlesmeTaslagi();
            taslak.tip = "NORMAL";
            taslaklar.put(player.getUniqueId(), taslak);
            openPlayerMenu(player, "Sahsi");
            return true;
        }

        if (command.getName().equalsIgnoreCase("belediyesozlesmesi")) {
            if (!player.hasPermission("meslek.baskan")) {
                player.sendMessage(ChatColor.RED + "Bu yetki sadece Belediye Başkanına aittir.");
                return true;
            }
            if (plugin.kasaKonumu == null || !(plugin.kasaKonumu.getBlock().getState() instanceof Chest)) {
                player.sendMessage(ChatColor.RED + "Belediye kasası ayarlanmamış! Önce /kasaayarla yapın.");
                return true;
            }
            SozlesmeTaslagi taslak = new SozlesmeTaslagi();
            taslak.tip = "BELEDIYE";
            taslaklar.put(player.getUniqueId(), taslak);
            openPlayerMenu(player, "Belediye");
            return true;
        }

        if (command.getName().equalsIgnoreCase("sozlesmekabul")) {
            if (args.length < 1) return true;
            String sozlesmeID = args[0];
            if (!aktifSozlesmeler.containsKey(sozlesmeID)) {
                player.sendMessage(ChatColor.RED + "Bu teklif artık geçerli değil.");
                return true;
            }
            AktifSozlesme sozlesme = aktifSozlesmeler.get(sozlesmeID);
            if (!sozlesme.isciUUID.equals(player.getUniqueId()) || !sozlesme.durum.equals("TEKLIF")) return true;

            sozlesme.durum = "BEKLIYOR";
            player.getInventory().remove(Material.WRITTEN_BOOK); 
            sendSozlesmeKitabi(player, sozlesmeID, sozlesme);
            
            player.sendMessage(ChatColor.GREEN + "Sözleşmeyi kabul ettiniz! Görev kitabınız verildi.");
            Player isveren = Bukkit.getPlayer(sozlesme.isverenUUID);
            if (isveren != null && isveren.isOnline()) {
                isveren.sendMessage(ChatColor.GREEN + player.getName() + " iş teklifinizi kabul etti ve çalışmaya başladı.");
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("sozlesmereddet")) {
            if (args.length < 1) return true;
            String sozlesmeID = args[0];
            if (!aktifSozlesmeler.containsKey(sozlesmeID)) return true;
            AktifSozlesme sozlesme = aktifSozlesmeler.get(sozlesmeID);
            if (!sozlesme.isciUUID.equals(player.getUniqueId()) || !sozlesme.durum.equals("TEKLIF")) return true;

            aktifSozlesmeler.remove(sozlesmeID);
            player.getInventory().remove(Material.WRITTEN_BOOK);
            player.sendMessage(ChatColor.RED + "İş teklifini reddettiniz.");
            
            Player isveren = Bukkit.getPlayer(sozlesme.isverenUUID);
            if (isveren != null && isveren.isOnline()) {
                isveren.sendMessage(ChatColor.RED + player.getName() + " iş teklifinizi REDDETTİ!");
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("sozlesmebitir")) {
            if (args.length < 1) return true;
            String sozlesmeID = args[0];
            
            if (!aktifSozlesmeler.containsKey(sozlesmeID)) {
                player.sendMessage(ChatColor.RED + "Bu sözleşme artık geçerli değil veya bulunamadı.");
                return true;
            }
            AktifSozlesme sozlesme = aktifSozlesmeler.get(sozlesmeID);
            if (!sozlesme.isciUUID.equals(player.getUniqueId()) || !sozlesme.durum.equals("BEKLIYOR")) return true;
            
            sozlesme.durum = "ONAY_BEKLIYOR";
            player.sendMessage(ChatColor.GREEN + "İş bitirme bildiriminiz işverene gönderildi!");
            player.getInventory().remove(Material.WRITTEN_BOOK); 

            Player isveren = Bukkit.getPlayer(sozlesme.isverenUUID);
            if (isveren != null && isveren.isOnline()) {
                sendFaturaKitabi(isveren, sozlesmeID, sozlesme);
                isveren.sendMessage(ChatColor.GOLD + "DİKKAT: " + ChatColor.WHITE + sozlesme.isciAdi + " işi bitirdiğini bildirdi. Fatura Raporu eklendi.");
            } else {
                player.sendMessage(ChatColor.YELLOW + "İşveren şu an çevrimdışı. Girdiğinde faturayı görecek.");
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("sozlesmeonayla")) {
            if (args.length < 2) return true;
            String sozlesmeID = args[0];
            String karar = args[1]; 
            
            if (!aktifSozlesmeler.containsKey(sozlesmeID)) {
                player.sendMessage(ChatColor.RED + "Bu sözleşme geçersiz.");
                return true;
            }
            AktifSozlesme sozlesme = aktifSozlesmeler.get(sozlesmeID);
            
            if (!sozlesme.isverenUUID.equals(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "Bunu sadece işveren yapabilir.");
                return true;
            }
            
            Player isci = Bukkit.getPlayer(sozlesme.isciUUID);
            player.getInventory().remove(Material.WRITTEN_BOOK); 

            if (karar.equalsIgnoreCase("KABUL")) {
                if (sozlesme.tip.equals("NORMAL")) {
                    double isverenBakiye = plugin.bankaHesaplari.getOrDefault(player.getUniqueId(), 0.0);
                    if (isverenBakiye < sozlesme.tutar) {
                        player.sendMessage(ChatColor.RED + "Dijital banka hesabınızda yeterli bakiye yok! Önce para yatırın.");
                        sendFaturaKitabi(player, sozlesmeID, sozlesme);
                        return true;
                    }
                    plugin.bankaHesaplari.put(player.getUniqueId(), isverenBakiye - sozlesme.tutar);
                    player.sendMessage(ChatColor.GREEN + "Ödeme onaylandı. " + sozlesme.tutar + "$ şahsi hesabınızdan kesildi.");

                } else if (sozlesme.tip.equals("BELEDIYE")) {
                    if (plugin.kasaKonumu == null || !(plugin.kasaKonumu.getBlock().getState() instanceof Chest)) {
                        player.sendMessage(ChatColor.RED + "Belediye kasası bulunamadı! Ödeme yapılamıyor.");
                        sendFaturaKitabi(player, sozlesmeID, sozlesme);
                        return true;
                    }
                    Chest kasa = (Chest) plugin.kasaKonumu.getBlock().getState();
                    double kasaBakiye = 0.0;
                    for (ItemStack item : kasa.getInventory().getContents()) {
                        Double val = plugin.getMoneyValue(item);
                        if (val != null) kasaBakiye += (val * item.getAmount());
                    }
                    if (kasaBakiye < sozlesme.tutar) {
                        player.sendMessage(ChatColor.RED + "Belediye kasasında yeterli fiziksel nakit kalmamış! Fatura bekletiliyor.");
                        sendFaturaKitabi(player, sozlesmeID, sozlesme);
                        return true;
                    }
                    
                    for (int i = 0; i < kasa.getInventory().getSize(); i++) {
                        if (plugin.getMoneyValue(kasa.getInventory().getItem(i)) != null) {
                            kasa.getInventory().setItem(i, null);
                        }
                    }
                    double remaining = kasaBakiye - sozlesme.tutar;
                    if (remaining > 0) kasa.getInventory().addItem(plugin.createEconomyNote(remaining));
                    
                    player.sendMessage(ChatColor.GREEN + "Ödeme onaylandı. " + sozlesme.tutar + "$ Belediye Kasasından kesildi.");
                }
                
                double isciBakiye = plugin.bankaHesaplari.getOrDefault(sozlesme.isciUUID, 0.0);
                plugin.bankaHesaplari.put(sozlesme.isciUUID, isciBakiye + sozlesme.tutar);
                plugin.veriKaydet();
                
                aktifSozlesmeler.remove(sozlesmeID);
                if (isci != null && isci.isOnline()) {
                    isci.sendMessage(ChatColor.GREEN + "İşveren işlemi onayladı! Hesabınıza " + sozlesme.tutar + "$ yatırıldı.");
                }
            } else if (karar.equalsIgnoreCase("RED")) {
                aktifSozlesmeler.remove(sozlesmeID);
                player.sendMessage(ChatColor.RED + "Sözleşme iptal edildi ve ödeme reddedildi.");
                if (isci != null && isci.isOnline()) {
                    isci.sendMessage(ChatColor.DARK_RED + "İşveren işi yetersiz buldu ve sözleşmeyi iptal etti. Ödeme yapılmadı.");
                }
            }
            return true;
        }
        return false;
    }

    private void openPlayerMenu(Player player, String tip) {
        int size = ((Bukkit.getOnlinePlayers().size() / 9) + 1) * 9;
        if (size < 9) size = 9;
        
        Inventory gui = Bukkit.createInventory(null, size, ChatColor.DARK_BLUE + "Oyuncu Seç: " + tip);
        
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(player.getUniqueId())) continue;
            
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(p);
            meta.setDisplayName(ChatColor.YELLOW + p.getName());
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Sözleşme teklif etmek için tıkla.");
            meta.setLore(lore);
            head.setItemMeta(meta);
            gui.addItem(head);
        }
        
        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        String title = event.getView().getTitle();
        if (title.startsWith(ChatColor.DARK_BLUE + "Oyuncu Seç: ")) {
            event.setCancelled(true);
            Player isveren = (Player) event.getWhoClicked();
            ItemStack clicked = event.getCurrentItem();
            
            if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) return;
            String isciName = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
            Player isci = Bukkit.getPlayer(isciName);
            
            if (isci == null) {
                isveren.sendMessage(ChatColor.RED + "Oyuncu çevrimiçi değil.");
                isveren.closeInventory();
                taslaklar.remove(isveren.getUniqueId());
                return;
            }

            if (!taslaklar.containsKey(isveren.getUniqueId())) return;
            SozlesmeTaslagi taslak = taslaklar.get(isveren.getUniqueId());
            
            taslak.isciIsmi = isci.getName();
            taslak.asama = 2; 
            
            isveren.closeInventory();
            String baslik = taslak.tip.equals("BELEDIYE") ? "BELEDİYE SÖZLEŞMESİ" : "ŞAHSİ SÖZLEŞME";
            isveren.sendMessage(ChatColor.AQUA + "--- YENİ " + baslik + " ---");
            isveren.sendMessage(ChatColor.GREEN + "İşçi Seçildi: " + isci.getName());
            isveren.sendMessage(ChatColor.YELLOW + "Adım 2: " + ChatColor.WHITE + "İş bittiğinde ödenecek net tutarı yazın (Örn: 5000):");
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (!taslaklar.containsKey(player.getUniqueId())) return;
        
        SozlesmeTaslagi taslak = taslaklar.get(player.getUniqueId());
        if (taslak.asama < 2) return; 
        
        event.setCancelled(true);
        String mesaj = event.getMessage().trim();
        
        // GÜNCELLEME: Çökmeyi engelleyen Senkron Görev (Main Thread Task)
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (mesaj.equalsIgnoreCase("iptal")) {
                taslaklar.remove(player.getUniqueId());
                player.sendMessage(ChatColor.RED + "Sözleşme oluşturma iptal edildi.");
                return;
            }

            if (taslak.asama == 2) { 
                try {
                    taslak.tutar = Double.parseDouble(mesaj);
                    if (taslak.tutar <= 0) throw new NumberFormatException();
                    
                    if (taslak.tip.equals("NORMAL")) {
                        double mevcutBakiye = plugin.bankaHesaplari.getOrDefault(player.getUniqueId(), 0.0);
                        if (mevcutBakiye < taslak.tutar) {
                            player.sendMessage(ChatColor.RED + "❌ SÖZLEŞME BAŞLATILAMADI!");
                            player.sendMessage(ChatColor.RED + "Şahsi dijital hesabınızda " + taslak.tutar + "$ bakiye yok! (Mevcut Paran: $" + mevcutBakiye + ")");
                            player.sendMessage(ChatColor.GRAY + "Daha düşük bir tutar girin veya iptal etmek için 'iptal' yazın.");
                            return; // Aşamayı 3'e geçirmeden bekler
                        }
                    } else if (taslak.tip.equals("BELEDIYE")) {
                        if (plugin.kasaKonumu == null || !(plugin.kasaKonumu.getBlock().getState() instanceof Chest)) {
                            player.sendMessage(ChatColor.RED + "❌ HATA: Belediye Kasası bulunamadı! İptal etmek için 'iptal' yazın.");
                            return;
                        }
                        Chest kasa = (Chest) plugin.kasaKonumu.getBlock().getState();
                        double kasaBakiye = 0.0;
                        for (ItemStack item : kasa.getInventory().getContents()) {
                            Double val = plugin.getMoneyValue(item);
                            if (val != null) kasaBakiye += (val * item.getAmount());
                        }
                        if (kasaBakiye < taslak.tutar) {
                            player.sendMessage(ChatColor.RED + "❌ SÖZLEŞME BAŞLATILAMADI!");
                            player.sendMessage(ChatColor.RED + "Belediye Kasasında yeterli fiziksel nakit yok! (Kasada Olan: $" + kasaBakiye + ")");
                            player.sendMessage(ChatColor.GRAY + "Daha düşük bir tutar girin veya iptal etmek için 'iptal' yazın.");
                            return; // Aşamayı 3'e geçirmeden bekler
                        }
                    }
                    
                    taslak.asama = 3;
                    String kaynak = taslak.tip.equals("NORMAL") ? "Şahsi Banka Hesabı" : "Belediye Kasası";
                    player.sendMessage(ChatColor.GREEN + "✔ Tutar Onaylandı: $" + taslak.tutar + " (" + kaynak + "ndan ödenecek)");
                    player.sendMessage(ChatColor.YELLOW + "Adım 3: " + ChatColor.WHITE + "İşin detayını ve şartlarını yazın:");
                    
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Hata: Lütfen sadece sayılardan oluşan geçerli bir tutar girin! (Örn: 500, 1500)");
                }
            } 
            else if (taslak.asama == 3) { 
                taslak.detay = mesaj;
                Player isci = Bukkit.getPlayer(taslak.isciIsmi);
                
                if (isci == null || !isci.isOnline()) {
                    player.sendMessage(ChatColor.RED + "İşçi şu anda oyunda değil, teklif iptal edildi.");
                    taslaklar.remove(player.getUniqueId());
                    return;
                }

                String sozlesmeID = UUID.randomUUID().toString().substring(0, 8);
                AktifSozlesme aktif = new AktifSozlesme();
                aktif.isverenAdi = player.getName();
                aktif.isverenUUID = player.getUniqueId();
                aktif.isciAdi = isci.getName();
                aktif.isciUUID = isci.getUniqueId();
                aktif.tutar = taslak.tutar;
                aktif.detay = taslak.detay;
                aktif.durum = "TEKLIF"; 
                aktif.tip = taslak.tip;
                aktifSozlesmeler.put(sozlesmeID, aktif);
                
                sendTeklifKitabi(isci, sozlesmeID, aktif);
                
                taslaklar.remove(player.getUniqueId());
                player.sendMessage(ChatColor.GREEN + "Teklif başarıyla oluşturuldu ve " + aktif.isciAdi + " adlı oyuncuya gönderildi. Onayı bekleniyor...");
            }
        });
    }

    private void sendTeklifKitabi(Player isci, String id, AktifSozlesme sozlesme) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        String baslik = sozlesme.tip.equals("BELEDIYE") ? "BELEDİYE İŞ TEKLİFİ" : "ŞAHSİ İŞ TEKLİFİ";
        meta.setTitle("İş Teklifi");
        meta.setAuthor(sozlesme.isverenAdi);

        TextComponent s1 = new TextComponent(ChatColor.DARK_BLUE + ChatColor.BOLD.toString() + baslik + "\n\n");
        s1.addExtra(ChatColor.BLACK + "Teklif Eden: " + ChatColor.DARK_GRAY + sozlesme.isverenAdi + "\n");
        s1.addExtra(ChatColor.BLACK + "Teklif Edilen Ücret: " + ChatColor.DARK_GREEN + "$" + sozlesme.tutar + "\n\n");
        s1.addExtra(ChatColor.DARK_RED + "İşin Tanımı:\n" + ChatColor.BLACK + sozlesme.detay + "\n\n");
        
        TextComponent kabul = new TextComponent(ChatColor.DARK_GREEN + ChatColor.BOLD.toString() + "[ KABUL ET ]\n\n");
        kabul.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sozlesmekabul " + id));
        
        TextComponent red = new TextComponent(ChatColor.DARK_RED + ChatColor.BOLD.toString() + "[ REDDET ]");
        red.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sozlesmereddet " + id));

        s1.addExtra(kabul);
        s1.addExtra(red);
        meta.spigot().addPage(new BaseComponent[]{s1});
        book.setItemMeta(meta);

        if (isci.getInventory().firstEmpty() != -1) isci.getInventory().addItem(book);
        else isci.getWorld().dropItem(isci.getLocation(), book);
        
        isci.sendMessage(ChatColor.GOLD + "[!] " + ChatColor.WHITE + sozlesme.isverenAdi + " size bir İş Teklifi gönderdi. Envanterinizi kontrol edin.");
    }

    private void sendSozlesmeKitabi(Player isci, String id, AktifSozlesme sozlesme) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        String baslik = sozlesme.tip.equals("BELEDIYE") ? "BELEDİYE SÖZLEŞMESİ" : "ŞAHSİ SÖZLEŞME";
        meta.setTitle(baslik);
        meta.setAuthor(sozlesme.isverenAdi);

        TextComponent s1 = new TextComponent(ChatColor.DARK_BLUE + ChatColor.BOLD.toString() + baslik + "\n\n");
        s1.addExtra(ChatColor.BLACK + "İşveren: " + ChatColor.DARK_GRAY + sozlesme.isverenAdi + "\n");
        s1.addExtra(ChatColor.BLACK + "Yüklenici: " + ChatColor.DARK_GRAY + sozlesme.isciAdi + "\n");
        s1.addExtra(ChatColor.BLACK + "Ücret: " + ChatColor.DARK_GREEN + "$" + sozlesme.tutar + "\n\n");
        s1.addExtra(ChatColor.DARK_RED + "İşin Tanımı:\n" + ChatColor.BLACK + sozlesme.detay + "\n\n");
        
        TextComponent buton = new TextComponent(ChatColor.DARK_GREEN + ChatColor.BOLD.toString() + "[ İŞİ BİTİRDİM - BİLDİR ]");
        buton.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sozlesmebitir " + id));
        s1.addExtra(buton);

        meta.spigot().addPage(new BaseComponent[]{s1});
        book.setItemMeta(meta);

        if (isci.getInventory().firstEmpty() != -1) isci.getInventory().addItem(book);
        else isci.getWorld().dropItem(isci.getLocation(), book);
    }

    private void sendFaturaKitabi(Player isveren, String id, AktifSozlesme sozlesme) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Fatura Raporu");
        meta.setAuthor("Devlet Sistemi");

        TextComponent s1 = new TextComponent(ChatColor.DARK_RED + ChatColor.BOLD.toString() + "İŞ BİTİRME RAPORU\n\n");
        s1.addExtra(ChatColor.BLACK + "Yüklenici: " + ChatColor.DARK_GRAY + sozlesme.isciAdi + " işi bitirdi.\n\n");
        s1.addExtra(ChatColor.BLACK + "Tutar: " + ChatColor.DARK_GREEN + "$" + sozlesme.tutar + "\n");
        String kaynak = sozlesme.tip.equals("BELEDIYE") ? "(Belediye Kasası)" : "(Şahsi Hesap)";
        s1.addExtra(ChatColor.DARK_GRAY + kaynak + "\n\n");

        TextComponent kabul = new TextComponent(ChatColor.DARK_GREEN + ChatColor.BOLD.toString() + "[ ÖDEMEYİ YAP ]\n\n");
        kabul.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sozlesmeonayla " + id + " KABUL"));
        
        TextComponent red = new TextComponent(ChatColor.DARK_RED + ChatColor.BOLD.toString() + "[ SÖZLEŞMEYİ İPTAL ET ]");
        red.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sozlesmeonayla " + id + " RED"));

        s1.addExtra(kabul);
        s1.addExtra(red);

        meta.spigot().addPage(new BaseComponent[]{s1});
        book.setItemMeta(meta);

        if (isveren.getInventory().firstEmpty() != -1) isveren.getInventory().addItem(book);
        else isveren.getWorld().dropItem(isveren.getLocation(), book);
    }
}