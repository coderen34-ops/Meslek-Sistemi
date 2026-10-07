package me.mesleksistemi.adliye;

import java.util.UUID;

public class DavaDosyasi {
    public UUID id;
    public String musteki;
    public String sanik;
    public String mustekiAvukati = null;
    public String sanikAvukati = null;
    public double talepEdilenMiktar;
    public double mustekiAvukatiUcreti = 0.0;
    public double sanikAvukatiUcreti = 0.0;
    public String sebep;
    public DavaDurumu durum;
    // Duruşmayı yöneten hakim (duruşma başlayınca atanır)
    public String hakim = null;

    public DavaDosyasi(UUID id, String musteki, String sanik, double talepEdilenMiktar, String sebep) {
        this.id = id;
        this.musteki = musteki;
        this.sanik = sanik;
        this.talepEdilenMiktar = talepEdilenMiktar;
        this.sebep = sebep;
        this.durum = DavaDurumu.MUSTEKI_AVUKATI_BEKLIYOR; 
    }
}