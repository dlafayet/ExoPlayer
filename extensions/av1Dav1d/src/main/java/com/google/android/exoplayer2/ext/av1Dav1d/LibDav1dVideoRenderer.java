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

import static java.lang.Runtime.getRuntime;

import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;

import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.PlayerMessage.Target;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.drm.ExoMediaCrypto;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.TraceUtil;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.DecoderVideoRenderer;
import com.google.android.exoplayer2.video.VideoDecoderOutputBuffer;
import com.google.android.exoplayer2.video.VideoDecoderOutputBufferRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

/**
 * Decodes and renders video using libDav1d decoder.
 *
 * <p>This renderer accepts the following messages sent via {@link ExoPlayer#createMessage(Target)}
 * on the playback thread:
 *
 * <ul>
 *   <li>Message with type {@link C#MSG_SET_VIDEO_DECODER_OUTPUT_BUFFER_RENDERER} to set the output
 *       buffer renderer. The message payload should be the target {@link
 *       VideoDecoderOutputBufferRenderer}, or null.
 * </ul>
 */
public class LibDav1dVideoRenderer extends DecoderVideoRenderer {

  static final String TAG = "LibDav1dVideoRenderer";

  protected static final int DEFAULT_NUM_OF_INPUT_BUFFERS = 4;
  // private static final int DEFAULT_NUM_OF_INPUT_BUFFERS = 8;   // ref from vo library
  protected static final int DEFAULT_NUM_OF_OUTPUT_BUFFERS = 4;
  // private static final int DEFAULT_NUM_OF_OUTPUT_BUFFERS = 16;   // ref from vo library
  /* Default size based on 720p resolution video compressed by a factor of two. */
  protected static final int DEFAULT_INPUT_BUFFER_SIZE =
      Util.ceilDivide(1280, 64) * Util.ceilDivide(720, 64) * (64 * 64 * 3 / 2) / 2;
  //  buffer size from Vo library
  //  protected static final int INITIAL_INPUT_BUFFER_SIZE = 768 * 1024; // Value based on cs/SoftVo.cpp.

  private static final int RENDER_THRESHOLD = 41;
  private static final int WINDOW_SIZE = 16;

  /** The number of input buffers. */
  private final int numInputBuffers;
  /**
   * The number of output buffers. The renderer may limit the minimum possible value due to
   * requiring multiple output buffers to be dequeued at a time for it to make progress.
   */
  private final int numOutputBuffers;

  private final int threads;

  @Nullable protected Dav1dDecoder decoder;

  private long rendererStartTime;
  private long lastFrameRenderedTime;
  private int delayedFrameCount;
  private int renderedFrameCountInCurrentWindow;

  private int FRAMEDROP_MEASURE_WINDOW = 24;

  /**
   * array that contains the number of times when the number of dropped frames within an 1 second window exceeds
   * 1, 2, 4, 8, 16
   */
  private int[] droppedFramesStats = new int[5];
  private int renderedFrameCountInMeasureWindow;
  private int droppedFrameCountInMeasureWindow;

  /**
   * Creates a LibDav1dVideoRenderer.
   *
   * @param allowedJoiningTimeMs The maximum duration in milliseconds for which this video renderer
   *     can attempt to seamlessly join an ongoing playback.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param maxDroppedFramesToNotify The maximum number of frames that can be dropped between
   *     invocations of {@link VideoRendererEventListener#onDroppedFrames(int, long)}.
   */
  public LibDav1dVideoRenderer(
      long allowedJoiningTimeMs,
      @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify) {
    this(
        allowedJoiningTimeMs,
        eventHandler,
        eventListener,
        maxDroppedFramesToNotify,
        /* threads= */ getRuntime().availableProcessors(),
        DEFAULT_NUM_OF_INPUT_BUFFERS,
        DEFAULT_NUM_OF_OUTPUT_BUFFERS);
  }

  /**
   * Creates a LibDav1dVideoRenderer.
   *
   * @param allowedJoiningTimeMs The maximum duration in milliseconds for which this video renderer
   *     can attempt to seamlessly join an ongoing playback.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param maxDroppedFramesToNotify The maximum number of frames that can be dropped between
   *     invocations of {@link VideoRendererEventListener#onDroppedFrames(int, long)}.
   * @param threads Number of threads libgav1 will use to decode.
   * @param numInputBuffers Number of input buffers.
   * @param numOutputBuffers Number of output buffers.
   */
  public LibDav1dVideoRenderer(
      long allowedJoiningTimeMs,
      @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify,
      int threads,
      int numInputBuffers,
      int numOutputBuffers) {
    super(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify);
    this.threads = threads;
    this.numInputBuffers = numInputBuffers;
    this.numOutputBuffers = numOutputBuffers;
  }

  @Override
  protected Dav1dDecoder createDecoder(Format format, @Nullable ExoMediaCrypto mediaCrypto)
          throws Dav1dDecoderException {
    TraceUtil.beginSection("createDav1dDecoder");
    Log.d(TAG, "Create Dav1dDecoder");
    int initialInputBufferSize =
        format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
    Dav1dDecoder decoder =
        new Dav1dDecoder(numInputBuffers, numOutputBuffers, initialInputBufferSize, threads, threads);
    this.decoder = decoder;
    TraceUtil.endSection();
    return decoder;
  }

  @Override
  protected void releaseDecoder() {
    super.releaseDecoder();
    logOutputFrame(C.TIME_UNSET, C.TIME_UNSET);
    clearAdditionalDroppedFramesData();
  }

  // TODO: Vivian - logOutputFrame() are all skipped for now, it is used in NetflixEmbeddedAV1 to record lagging time

  @Override
  protected void renderOutputBuffer(
      VideoDecoderOutputBuffer outputBuffer, long presentationTimeUs, Format outputFormat)
      throws DecoderException {
    super.renderOutputBuffer(outputBuffer, presentationTimeUs, outputFormat);

    renderedFrameCountInCurrentWindow++;
    renderedFrameCountInMeasureWindow++;

    long currentRenderTime = SystemClock.elapsedRealtime();
    long renderDelay = currentRenderTime - lastFrameRenderedTime;
    Log.d(TAG, "rendered number of frames " + decoderCounters.renderedOutputBufferCount + ", average : " + (currentRenderTime - rendererStartTime)/decoderCounters.renderedOutputBufferCount + " current frame render time : " + (renderDelay));
    lastFrameRenderedTime = currentRenderTime;
    if (renderDelay > RENDER_THRESHOLD) {
      delayedFrameCount++;
    }
    maybeNotifyLateFrameCount();
    maybeNotifyTooManyDroppedFrames();
  }

  @Override
  protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws Dav1dDecoderException {
    if (decoder == null) {
      throw new Dav1dDecoderException(
          "Failed to render output buffer to surface: decoder is not initialized.");
    }
    decoder.renderToSurface(outputBuffer, surface);
    outputBuffer.release();
  }

  @Override
  protected void setDecoderOutputMode(@C.VideoOutputMode int outputMode) {
    if (decoder != null) {
      decoder.setOutputMode(outputMode);
    }
  }

  // PlayerMessage.Target implementation.

  @Override
  public void handleMessage(int messageType, @Nullable Object message) throws ExoPlaybackException {
    if (messageType == C.MSG_SET_VIDEO_DECODER_OUTPUT_BUFFER_RENDERER) {
      setOutputBufferRenderer((VideoDecoderOutputBufferRenderer) message);
    } else {
      super.handleMessage(messageType, message);
    }
  }

  @Override
  protected void onStarted() {
    super.onStarted();
    delayedFrameCount = 0;
    renderedFrameCountInCurrentWindow = 0;
    renderedFrameCountInMeasureWindow = 0;
    droppedFrameCountInMeasureWindow = 0;
    rendererStartTime = SystemClock.elapsedRealtime();
    decoderCounters.renderedOutputBufferCount = 0;
    lastFrameRenderedTime = rendererStartTime;
    Log.d(TAG, "LibDav1dVideoRenderer start time: " + rendererStartTime);
  }

  @Override
  protected void onStopped() {
    super.onStopped();
    maybeNotifyLateFrameCount();
    clearAdditionalDroppedFramesData();
  }

  private void clearAdditionalDroppedFramesData() {
    droppedFrameCountInMeasureWindow = 0;
    renderedFrameCountInMeasureWindow = 0;
    for (int i = 0; i < droppedFramesStats.length; i++ ) {
      droppedFramesStats[i] = 0;
    }
  }

  private void maybeNotifyLateFrameCount() {
      // TODO: need access to event dispacher - fix me  , but this does not affect functionality
      // this sends info to debugging info
      /*
    if (renderedFrameCountInCurrentWindow > WINDOW_SIZE) {
      eventDispatcher.sendLateFrameCount(delayedFrameCount, SystemClock.elapsedRealtime());
      Log.d(TAG, "Number of frames delayed in current window: " + delayedFrameCount);
      delayedFrameCount = 0;
      renderedFrameCountInCurrentWindow = 0;
    } */
  }

  /**
   * Drops the specified output buffer and releases it.
   *
   * @param outputBuffer The output buffer to drop.
   */
  protected void dropOutputBuffer(VideoDecoderOutputBuffer outputBuffer) {
    // currently we send out an
    Log.d(TAG, "Drop current buffer");
    updateDroppedBufferCounters(1);
    droppedFrameCountInMeasureWindow++;
    if (droppedFrameCountInMeasureWindow > 15) {
      droppedFramesStats[4]++;
    } else if (droppedFrameCountInMeasureWindow > 7) {
      droppedFramesStats[3]++;
    } else if (droppedFrameCountInMeasureWindow > 3) {
      droppedFramesStats[2]++;
    } else if (droppedFrameCountInMeasureWindow > 1) {
      droppedFramesStats[1]++;
    } else {
      droppedFramesStats[0]++;
    }
    maybeNotifyTooManyDroppedFrames();
    Log.d(TAG, "Total dropped frames: " + decoderCounters.droppedBufferCount);
    outputBuffer.release();
  }

  private void maybeNotifyTooManyDroppedFrames() {
    if (droppedFramesStats[0] > 15 ||
        droppedFramesStats[1] > 7 ||
        droppedFramesStats[2] > 3 ||
        droppedFramesStats[3] > 1 ||
        droppedFramesStats[4] > 0) {
      //TODO: vivian - dispatch event and tell listener that we dropped too many frames
    //  eventDispatcher.droppedTooManyFrames();
      clearAdditionalDroppedFramesData();
    }
    if (renderedFrameCountInMeasureWindow + droppedFrameCountInMeasureWindow > FRAMEDROP_MEASURE_WINDOW) {
      Log.d(TAG, "Number of dropped frame in current window: " + droppedFrameCountInMeasureWindow);
      renderedFrameCountInMeasureWindow = 0;
      droppedFrameCountInMeasureWindow = 0;
    }
  }

  @Override
  protected boolean shouldDropBuffersToKeyframe(long earlyUs, long elapsedRealtimeUs) {
    return false;
  }

  protected void logOutputFrame(long timeUs, long positionUs){
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  public int supportsFormat(Format format) throws ExoPlaybackException {
    if (!MimeTypes.VIDEO_AV1.equalsIgnoreCase(format.sampleMimeType)
        || !Dav1dLibrary.isAvailable()) {
      return RendererCapabilities.create(FORMAT_UNSUPPORTED_TYPE);
    }
    return RendererCapabilities.create(FORMAT_HANDLED, ADAPTIVE_SEAMLESS, TUNNELING_NOT_SUPPORTED);
  }

  @Override
  protected boolean canKeepCodec(Format oldFormat, Format newFormat) {
    return true;
  }
}
