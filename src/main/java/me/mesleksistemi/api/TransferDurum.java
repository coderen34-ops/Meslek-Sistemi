package me.mesleksistemi.api;

/** Para transferi sonucu. Kurallar menüdeki transferle aynıdır (vergi yok, alt limit 1.0, üst limit/bekleme yok, mahkum gönderemez). */
public enum TransferDurum {
    BASARILI,
    GECERSIZ_MIKTAR,
    MIN_ALTI,
    MAHKUM,
    KENDINE,
    YETERSIZ_BAKIYE,
    ALICI_BILINMIYOR,
    GECERSIZ_ANAHTAR
}
