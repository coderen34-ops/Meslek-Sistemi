package me.mesleksistemi.api;

/** Profil yorumu / beğeni yazma sonucu. */
public enum EtkilesimSonucu {
    BASARILI,
    /** Beğeni/beğenmeme aynı türden ikinci kez verildi: oy geri alındı (menüdeki davranışla aynı). */
    GERI_ALINDI,
    GOCMEN,
    MAHKUM,
    YETERSIZ_SURE,
    METIN_KISA,
    METIN_UZUN,
    BEKLEME,
    KENDINE,
    HEDEF_BILINMIYOR
}
