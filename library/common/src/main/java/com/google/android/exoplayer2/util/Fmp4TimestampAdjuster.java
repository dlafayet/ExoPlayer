package com.google.android.exoplayer2.util;

public interface Fmp4TimestampAdjuster {
  /**
   * Offsets a timestamp in microseconds.
   *
   * @param timeUs The timestamp to adjust in microseconds.
   * @return The adjusted timestamp in microseconds.
   */
  long adjustSampleTimestamp(long timeUs);
}
