package me.mesleksistemi.adliye;

import java.util.UUID;

public class AvukatTeklifi {
    public UUID davaId;
    public UUID avukatId;
    public String avukatAdi;
    public double ucret;
    public boolean isMusteki;
    
    public AvukatTeklifi(UUID davaId, UUID avukatId, String avukatAdi, double ucret, boolean isMusteki) {
        this.davaId = davaId;
        this.avukatId = avukatId;
        this.avukatAdi = avukatAdi;
        this.ucret = ucret;
        this.isMusteki = isMusteki;
    }
}