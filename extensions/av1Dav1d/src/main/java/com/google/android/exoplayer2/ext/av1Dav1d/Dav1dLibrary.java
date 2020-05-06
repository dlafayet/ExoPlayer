/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.ext.av1Dav1d;

import com.google.android.exoplayer2.ExoPlayerLibraryInfo;
import com.google.android.exoplayer2.util.LibraryLoader;

/** Configures and queries the underlying native library. */
public final class Dav1dLibrary {

  static {
    ExoPlayerLibraryInfo.registerModule("goog.exo.av1Dav1d");
  }
  /**
   * Returns the version of the underlying library if available, or null otherwise.
   */
  public static String getVersion() {
    return Dav1dGetVersion();
  }

  private static final LibraryLoader LOADER = new LibraryLoader("dav1dJNI");

  private Dav1dLibrary() {}

  /** Returns whether the underlying library is available, loading it if necessary. */
  public static boolean isAvailable() {
    return LOADER.isAvailable();
  }

  private static native String Dav1dGetVersion();
}
