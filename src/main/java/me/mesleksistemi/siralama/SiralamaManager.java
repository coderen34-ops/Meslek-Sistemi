package me.mesleksistemi.siralama;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.configuration.file.YamlConfiguration;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import me.mesleksistemi.MeslekSistemi;

/**
 * Sitedeki "Sıralama" sayfası için veri üretir: siralama.json (plugin klasörü) ve ayarlıysa GitHub deposuna yükler.
 * Ayarlar (config.yml):  siralama.aralik-dk (10),  siralama.github.token / repo / dal / dosya
 * İstatistikler dünya klasöründeki stats/*.json dosyalarından okunur (çevrimdışı oyuncular dahil).
 */
public class SiralamaManager {

    private static final int SATIR = 10;
    private final MeslekSistemi plugin;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private BukkitTask gorev;
    private volatile String sonYuklenenHash = "";

    public SiralamaManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        long aralik = Math.max(1, plugin.getConfig().getLong("siralama.aralik-dk", 10)) * 60L * 20L;
        this.gorev = Bukkit.getScheduler().runTaskTimer(plugin, this::guncelle, 20L * 60, aralik);
    }

    public void kapat() { if (gorev != null) gorev.cancel(); }

    /** Ana thread: bellekteki verileri al, dosya okumayı async yap. */
    public void guncelle() {
        final Map<UUID, Double> zengin = new HashMap<>(plugin.bankaHesaplari);
        final Map<UUID, Integer> sabika = new HashMap<>();
        ConfigurationSection sc = plugin.getConfig().getConfigurationSection("sabika");
        if (sc != null) {
            for (String k : sc.getKeys(false)) {
                try { int v = sc.getInt(k); if (v > 0) sabika.put(UUID.fromString(k), v); } catch (Exception ignored) { }
            }
        }
        final Map<UUID, int[]> begeni = plugin.etkilesimManager != null ? plugin.etkilesimManager.begeniOzeti() : new HashMap<>();
        final File statKlasor = statKlasoru();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, long[]> stat = statOku(statKlasor);
            Map<String, double[]> ticaret = logOku();
            Map<UUID, Integer> arena = arenaOku();
            Bukkit.getScheduler().runTask(plugin, () -> olustur(zengin, sabika, stat, ticaret, begeni, arena));
        });
    }

    /** Arena Ligi (ayrı plugin) dövüşçü verisi: plugins/ArenaLigi/data.yml -> dovusculer.<uuid>.galibiyet */
    private Map<UUID, Integer> arenaOku() {
        Map<UUID, Integer> sonuc = new HashMap<>();
        try {
            File f = new File(plugin.getDataFolder().getParentFile(), "ArenaLigi/data.yml");
            if (!f.isFile()) return sonuc;
            org.bukkit.configuration.file.YamlConfiguration y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
            ConfigurationSection b = y.getConfigurationSection("dovusculer");
            if (b == null) return sonuc;
            for (String k : b.getKeys(false)) {
                try { int g = b.getInt(k + ".galibiyet"); if (g > 0) sonuc.put(UUID.fromString(k), g); } catch (Exception ignored) { }
            }
        } catch (Exception e) { plugin.getLogger().warning("Sıralama: Arena Ligi verisi okunamadı: " + e); }
        return sonuc;
    }

    /** Sunucuya en son ilk kez giren oyuncular (en yeni başta). */
    private List<Map<String, Object>> yeniKatilanlar() {
        List<org.bukkit.OfflinePlayer> liste = new ArrayList<>();
        for (org.bukkit.OfflinePlayer o : Bukkit.getOfflinePlayers()) {
            if (o.getName() != null && o.getFirstPlayed() > 0) liste.add(o);
        }
        liste.sort((a, b) -> Long.compare(b.getFirstPlayed(), a.getFirstPlayed()));
        List<Map<String, Object>> sonuc = new ArrayList<>();
        for (org.bukkit.OfflinePlayer o : liste) {
            if (sonuc.size() >= 12) break;
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("ad", o.getName());
            sonuc.add(m);
        }
        return sonuc;
    }

    private File statKlasoru() {
        if (Bukkit.getWorlds().isEmpty()) return null;
        // Yeni Paper sürümlerinde dünya klasörü world/dimensions/minecraft/overworld olabilir; istatistikler üst klasördeki
        // world/players/stats içinde durur. Bu yüzden klasörden başlayıp üst klasörlere doğru bakılır.
        File enDolu = null; int enCok = 0;
        File d = Bukkit.getWorlds().get(0).getWorldFolder();
        for (int derinlik = 0; d != null && derinlik < 6; derinlik++, d = d.getParentFile()) {
            File[] adaylar = { new File(d, "players/stats"), new File(d, "stats"), new File(d, "players/statistics") };
            for (File f : adaylar) {
                if (!f.isDirectory()) continue;
                String[] liste = f.list((x, n) -> n.endsWith(".json"));
                int n = liste == null ? 0 : liste.length;
                if (n > enCok) { enCok = n; enDolu = f; }
            }
        }
        if (enDolu == null) plugin.getLogger().warning("Sıralama: istatistik klasörü bulunamadı (dünya: " + Bukkit.getWorlds().get(0).getWorldFolder() + ")");
        return enDolu;
    }

    // [0]=oynama tick, [1]=oyuncu öldürme, [2]=ölüm, [3]=mob, [4]=kırılan blok
    private Map<UUID, long[]> statOku(File klasor) {
        Map<UUID, long[]> sonuc = new HashMap<>();
        if (klasor == null) return sonuc;
        File[] dosyalar = klasor.listFiles((d, n) -> n.endsWith(".json"));
        if (dosyalar == null) return sonuc;
        for (File f : dosyalar) {
            try {
                UUID id = UUID.fromString(f.getName().substring(0, f.getName().length() - 5));
                JsonObject kok = gson.fromJson(Files.readString(f.toPath(), StandardCharsets.UTF_8), JsonObject.class);
                JsonObject st = kok.has("stats") ? kok.getAsJsonObject("stats") : kok;
                long[] v = new long[5];
                JsonObject c = st.has("minecraft:custom") ? st.getAsJsonObject("minecraft:custom") : new JsonObject();
                v[0] = sayi(c, "minecraft:play_time", sayi(c, "minecraft:play_one_minute", 0));
                v[1] = sayi(c, "minecraft:player_kills", 0);
                v[2] = sayi(c, "minecraft:deaths", 0);
                v[3] = sayi(c, "minecraft:mob_kills", 0);
                if (st.has("minecraft:mined")) {
                    for (Map.Entry<String, JsonElement> e : st.getAsJsonObject("minecraft:mined").entrySet()) v[4] += e.getValue().getAsLong();
                }
                sonuc.put(id, v);
            } catch (Exception ignored) { }
        }
        return sonuc;
    }

    // ekonomi log'larından (loglar/ekonomi-*.log): [0]=pazar satılan adet, [1]=pazar ciro, [2]=toptancı ciro, [3]=ticaret işlem sayısı
    private static final java.util.regex.Pattern LOG = java.util.regex.Pattern.compile("^\\[[^\\]]+\\] \\[([a-z-]+)\\] (\\S+) \\| (.*)$");
    private static final java.util.regex.Pattern PAZAR = java.util.regex.Pattern.compile("^(\\d+)x \\S+ \\| stand: .*? \\| sahip: (\\S+) \\| toplam: \\$([\\d.,]+)");
    private static final java.util.regex.Pattern TRANSFER = java.util.regex.Pattern.compile("^-> (\\S+) \\$");
    private static final java.util.regex.Pattern TOPTANCI = java.util.regex.Pattern.compile("toplam: \\$([\\d.,]+)");

    private Map<String, double[]> logOku() {
        Map<String, double[]> r = new HashMap<>();
        File[] dosyalar = new File(plugin.getDataFolder(), "loglar").listFiles((d, n) -> n.startsWith("ekonomi-") && n.endsWith(".log"));
        if (dosyalar == null) return r;
        for (File f : dosyalar) {
            try {
                for (String satir : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    java.util.regex.Matcher m = LOG.matcher(satir);
                    if (!m.matches()) continue;
                    String tur = m.group(1), kim = m.group(2), ayrinti = m.group(3);
                    if (tur.equals("pazar-satis")) {
                        java.util.regex.Matcher pm = PAZAR.matcher(ayrinti);
                        if (!pm.find()) continue;
                        double tutar = para(pm.group(3));
                        double[] sat = r.computeIfAbsent(pm.group(2), k -> new double[4]);
                        sat[0] += Double.parseDouble(pm.group(1)); sat[1] += tutar; sat[3] += 1;
                        r.computeIfAbsent(kim, k -> new double[4])[3] += 1;
                    } else if (tur.equals("para-transfer")) {
                        java.util.regex.Matcher tm = TRANSFER.matcher(ayrinti);
                        if (!tm.find()) continue;
                        r.computeIfAbsent(kim, k -> new double[4])[3] += 1;
                        r.computeIfAbsent(tm.group(1), k -> new double[4])[3] += 1;
                    } else if (tur.equals("toptanci-satis")) {
                        java.util.regex.Matcher om = TOPTANCI.matcher(ayrinti);
                        if (!om.find()) continue;
                        double[] t = r.computeIfAbsent(kim, k -> new double[4]);
                        t[2] += para(om.group(1)); t[3] += 1;
                    }
                }
            } catch (Exception ignored) { }
        }
        return r;
    }

    private static double para(String s) {
        try { return Double.parseDouble(s.replace(",", "")); } catch (Exception e) { return 0; }
    }

    private static long sayi(JsonObject o, String k, long varsayilan) {
        try { return o.has(k) ? o.get(k).getAsLong() : varsayilan; } catch (Exception e) { return varsayilan; }
    }

    private String ad(UUID id) {
        String a = Bukkit.getOfflinePlayer(id).getName();
        return a != null ? a : id.toString().substring(0, 8);
    }

    private interface Deger { String bicim(double v); }

    private Map<String, Object> kategori(String id, String baslik, String aciklama, Map<UUID, ? extends Number> veri, Deger bicim) {
        Map<String, Double> adli = new HashMap<>();
        for (Map.Entry<UUID, ? extends Number> e : veri.entrySet()) adli.merge(ad(e.getKey()), e.getValue().doubleValue(), Double::sum);
        return kategoriAd(id, baslik, aciklama, adli, bicim);
    }

    private Map<String, Object> kategoriAd(String id, String baslik, String aciklama, Map<String, Double> veri, Deger bicim) {
        List<Map.Entry<String, Double>> liste = new ArrayList<>();
        for (Map.Entry<String, Double> e : veri.entrySet()) if (e.getValue() > 0) liste.add(e);
        liste.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<Map<String, Object>> satirlar = new ArrayList<>();
        int sira = 1;
        for (Map.Entry<String, Double> e : liste) {
            if (sira > SATIR) break;
            Map<String, Object> s = new HashMap<>();
            s.put("sira", sira++);
            s.put("ad", e.getKey());
            s.put("deger", bicim.bicim(e.getValue()));
            satirlar.add(s);
        }
        Map<String, Object> k = new java.util.LinkedHashMap<>();
        k.put("id", id); k.put("baslik", baslik); k.put("aciklama", aciklama); k.put("satirlar", satirlar);
        return k;
    }

    private static String sayiYaz(double v) {
        if (v >= 1_000_000_000) return String.format(java.util.Locale.US, "%.1fb", v / 1_000_000_000);
        if (v >= 1_000_000) return String.format(java.util.Locale.US, "%.1fm", v / 1_000_000);
        if (v >= 10_000) return String.format(java.util.Locale.US, "%.1fk", v / 1_000);
        return String.valueOf((long) v);
    }

    private void olustur(Map<UUID, Double> zengin, Map<UUID, Integer> sabika, Map<UUID, long[]> stat, Map<String, double[]> ticaret, Map<UUID, int[]> begeni, Map<UUID, Integer> arena) {
        Map<UUID, Long> oyna = new HashMap<>(), oldur = new HashMap<>(), olum = new HashMap<>(), mob = new HashMap<>(), kir = new HashMap<>();
        for (Map.Entry<UUID, long[]> e : stat.entrySet()) {
            long[] v = e.getValue();
            oyna.put(e.getKey(), v[0] / 20L); oldur.put(e.getKey(), v[1]); olum.put(e.getKey(), v[2]);
            mob.put(e.getKey(), v[3]); kir.put(e.getKey(), v[4]);
        }
        List<Map<String, Object>> kat = new ArrayList<>();
        kat.add(kategori("zengin", "En Zenginler", "Banka bakiyesi en yüksek oyuncular.", zengin, v -> "$" + String.format(java.util.Locale.US, "%,.0f", v)));
        kat.add(kategori("sabika", "En Çok Sabıkası Olanlar", "Sabıka kaydı en kabarık oyuncular.", sabika, v -> (long) v + " sabıka"));
        kat.add(kategori("oynama", "En Çok Oynayanlar", "Toplam oynama süresi.", oyna, v -> { long s = (long) v; return (s / 86400) + " gün " + ((s % 86400) / 3600) + " saat"; }));
        kat.add(kategori("arena", "Arena Ligi Şampiyonları", "Arena Ligi'nde en çok maç kazanan dövüşçüler.", arena, v -> (long) v + " galibiyet"));
        kat.add(kategori("olum", "En Çok Ölenler", "En çok ölen oyuncular.", olum, v -> sayiYaz(v) + " ölüm"));
        kat.add(kategori("mob", "En Çok Mob Avlayanlar", "Öldürülen canavar sayısı.", mob, v -> sayiYaz(v) + " mob"));
        kat.add(kategori("kirma", "En Çok Blok Kıranlar", "Kırılan toplam blok sayısı.", kir, v -> sayiYaz(v) + " blok"));
        Map<String, Double> pAdet = new HashMap<>(), pCiro = new HashMap<>(), tCiro = new HashMap<>(), tIslem = new HashMap<>();
        for (Map.Entry<String, double[]> e : ticaret.entrySet()) {
            pAdet.put(e.getKey(), e.getValue()[0]); pCiro.put(e.getKey(), e.getValue()[1]);
            tCiro.put(e.getKey(), e.getValue()[2]); tIslem.put(e.getKey(), e.getValue()[3]);
        }
        kat.add(kategoriAd("pazar", "En Çok Mal Satanlar", "Pazarda en çok ürün satan esnaflar.", pAdet, v -> sayiYaz(v) + " adet"));
        kat.add(kategoriAd("pazarciro", "En Yüksek Pazar Cirosu", "Pazar satışlarından en çok kazananlar.", pCiro, v -> "$" + String.format(java.util.Locale.US, "%,.0f", v)));
        kat.add(kategoriAd("devlet", "Devlete En Çok Mal Satanlar", "Madenci, oduncu ve çiftçilerden toptancıya en çok satış yapanlar.", tCiro, v -> "$" + String.format(java.util.Locale.US, "%,.0f", v)));
        Map<UUID, Integer> bArti = new HashMap<>(), bEksi = new HashMap<>();
        for (Map.Entry<UUID, int[]> e : begeni.entrySet()) { bArti.put(e.getKey(), e.getValue()[0]); bEksi.put(e.getKey(), e.getValue()[1]); }
        kat.add(kategori("begeni", "En Beğenilenler", "Diğer oyunculardan en çok beğeni alanlar.", bArti, v -> (long) v + " beğeni"));
        kat.add(kategori("begenilmeyen", "En Beğenilmeyenler", "En çok beğenmeme oyu alanlar.", bEksi, v -> (long) v + " beğenmeme"));
        kat.add(kategoriAd("ticaret", "En Çok Ticaret Yapanlar", "Pazar, para transferi ve toptancı işlemleri toplamı.", tIslem, v -> sayiYaz(v) + " işlem"));
        Map<String, Object> kok = new java.util.LinkedHashMap<>();
        kok.put("guncelleme", ZonedDateTime.now(ZoneId.of("Europe/Istanbul")).format(DateTimeFormatter.ofPattern("d.M.yyyy HH:mm")));
        kok.put("yeni", yeniKatilanlar());
        kok.put("kategoriler", kat);
        final String json = gson.toJson(kok);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> yaz(json));
    }

    private void yaz(String json) {
        try { Files.writeString(new File(plugin.getDataFolder(), "siralama.json").toPath(), json, StandardCharsets.UTF_8); }
        catch (Exception e) { plugin.getLogger().warning("siralama.json yazılamadı: " + e); }
        // Ayarlar ayrı dosyada (siralama.yml): config.yml sunucu kapanırken yeniden yazıldığı için elle eklenen satırlar silinebiliyor.
        YamlConfiguration ayar = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "siralama.yml"));
        String token = ayar.getString("github.token", plugin.getConfig().getString("siralama.github.token", ""));
        if (token == null || token.isBlank()) return;
        try {
            // Saat değiştikçe her seferinde commit atmamak için içerik (saat hariç) aynıysa yükleme.
            String hash = Integer.toHexString(json.replaceAll("\"guncelleme\": ?\"[^\"]*\"", "").hashCode());
            if (hash.equals(sonYuklenenHash)) return;
            String repo = ayar.getString("github.repo", plugin.getConfig().getString("siralama.github.repo", "coderen34-ops/Koninski-Site"));
            String dal = ayar.getString("github.dal", plugin.getConfig().getString("siralama.github.dal", "main"));
            String yol = ayar.getString("github.dosya", plugin.getConfig().getString("siralama.github.dosya", "siralama.json"));
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            String url = "https://api.github.com/repos/" + repo + "/contents/" + yol;
            HttpRequest get = HttpRequest.newBuilder(URI.create(url + "?ref=" + dal)).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token).header("Accept", "application/vnd.github+json").GET().build();
            HttpResponse<String> gr = http.send(get, HttpResponse.BodyHandlers.ofString());
            String sha = null;
            if (gr.statusCode() == 200) sha = gson.fromJson(gr.body(), JsonObject.class).get("sha").getAsString();
            else if (gr.statusCode() != 404) { plugin.getLogger().warning("Sıralama GitHub okuma hatası: HTTP " + gr.statusCode()); return; }
            JsonObject govde = new JsonObject();
            govde.addProperty("message", "siralama guncelle");
            govde.addProperty("content", Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
            govde.addProperty("branch", dal);
            if (sha != null) govde.addProperty("sha", sha);
            HttpRequest put = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token).header("Accept", "application/vnd.github+json")
                    .PUT(HttpRequest.BodyPublishers.ofString(govde.toString())).build();
            HttpResponse<String> pr = http.send(put, HttpResponse.BodyHandlers.ofString());
            if (pr.statusCode() / 100 == 2) sonYuklenenHash = hash;
            else plugin.getLogger().warning("Sıralama GitHub yükleme hatası: HTTP " + pr.statusCode());
        } catch (Exception e) {
            plugin.getLogger().warning("Sıralama GitHub yüklenemedi: " + e);
        }
    }
}
