package me.mesleksistemi.api;

/**
 * @param durum          sonuç
 * @param miktar         yuvarlanmış (2 hane) miktar; başarısızsa istenen miktar
 * @param gonderenBakiye işlemden sonra gönderenin banka bakiyesi
 * @param tekrar         true: aynı kimlik anahtarı daha önce kullanıldı, bu sonuç saklı olandır (para tekrar hareket etmedi)
 */
public record TransferSonucu(TransferDurum durum, double miktar, double gonderenBakiye, boolean tekrar) {
    public boolean basarili() { return durum == TransferDurum.BASARILI; }
}
