package com.google.android.exoplayer2.source.chunk;

import androidx.annotation.Nullable;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.drm.DrmSessionEventListener;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.source.MediaSourceEventListener;
import com.google.android.exoplayer2.source.SequenceableLoader;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.LoadErrorHandlingPolicy;

public interface ChunkSampleStreamFactory<T extends ChunkSource> {

  ChunkSampleStream<T> create(
      int primaryTrackType,
      @Nullable int[] embeddedTrackTypes,
      @Nullable Format[] embeddedTrackFormats,
      T chunkSource,
      SequenceableLoader.Callback<ChunkSampleStream<T>> callback,
      Allocator allocator,
      long positionUs,
      DrmSessionManager drmSessionManager,
      DrmSessionEventListener.EventDispatcher drmEventDispatcher,
      LoadErrorHandlingPolicy loadErrorHandlingPolicy,
      MediaSourceEventListener.EventDispatcher mediaSourceEventDispatcher
  );
}
