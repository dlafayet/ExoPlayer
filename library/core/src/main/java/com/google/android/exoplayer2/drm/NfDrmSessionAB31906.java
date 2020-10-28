package com.google.android.exoplayer2.drm;

public interface NfDrmSessionAB31906 extends DrmSession{
    /**
     * SPY-32472
     * MediaCrypto could be stale when playback advance to a new media while codec was released.
     * This is the case for audio mode playgraph postplay. When this happens, we choose to reset
     * the MediaCrypto
     *
     * @return true in above scenario, to limit scope of impact to Audio Mode Test
     */
    boolean resetUnboundMediaCryptoOnFormatChange();
}
