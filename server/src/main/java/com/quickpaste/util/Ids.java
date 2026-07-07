package com.quickpaste.util;

import com.github.f4b6a3.ulid.UlidCreator;

/** ULID generation — all persisted ids are 26-char Crockford ULIDs (SPEC §8.1). */
public final class Ids {

    private Ids() {
    }

    public static String newUlid() {
        return UlidCreator.getMonotonicUlid().toString();
    }
}
