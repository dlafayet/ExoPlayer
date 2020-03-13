package com.google.android.exoplayer2;

/**
 * The DRC mode & output target ref level for xHE-AAC content.
 *
 * Note, these do not apply to HE-AAC content
 */
public final class AudioParameters {
    /**
     *
     * KEY_AAC_DRC_EFFECT_TYPE
     */
    public final int drcMode;

    /**
     * KEY_AAC_DRC_TARGET_REFERENCE_LEVEL
     */
    public final int target_ref_level;

    public AudioParameters(int drcMode, int target_ref_level) {
        this.drcMode = drcMode;
        this.target_ref_level = target_ref_level;
    }
}
