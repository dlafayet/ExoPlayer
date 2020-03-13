package com.google.android.exoplayer2.util;
/*
 *
 * Copyright (c) 2016 Netflix, Inc.  All rights reserved.
 */

import com.google.android.exoplayer2.util.Log;

import com.google.android.exoplayer2.C;

import java.util.Locale;

/**
 * intended for placing strategic logging inside exoplayer lib code
 * without have exoplayer depend on any external logging util
 */
public class NetflixExoLogUtil {
    private static final String TAG = "NfExo";
    public static void Log(String messageTemplate, Object... args){
        Log.d(TAG, toMessage(messageTemplate, args));
    }

    private static String toMessage(String messageTemplate, Object... args) {

        if(args == null || args.length < 1) {
            return messageTemplate;
        } else {
            return String.format(Locale.US, messageTemplate, args);
        }
    }

    public static String getTrackType(int tracktype) {
        switch (tracktype) {
            case C.TRACK_TYPE_AUDIO:
                return "Audio";
            case C.TRACK_TYPE_VIDEO:
                return "Video";
            case C.TRACK_TYPE_TEXT:
                return "Text";
            default:
                return "UnknownTrackType("+tracktype+")";
        }
    }
}
