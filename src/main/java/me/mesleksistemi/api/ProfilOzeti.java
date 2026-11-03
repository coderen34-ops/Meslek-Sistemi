package me.mesleksistemi.api;

import java.util.List;
import java.util.UUID;

public record ProfilOzeti(UUID uuid, String ad, String meslek, double bankaBakiyesi,
                          int begeni, int begenmeme, List<ProfilYorumu> yorumlar) { }
