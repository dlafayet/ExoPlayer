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

import android.view.Surface;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.decoder.SimpleDecoder;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoDecoderInputBuffer;
import com.google.android.exoplayer2.video.VideoDecoderOutputBuffer;

import java.nio.ByteBuffer;

/** Dav1d decoder. */
public class Dav1dDecoder
    extends SimpleDecoder<VideoDecoderInputBuffer, VideoDecoderOutputBuffer, Dav1dDecoderException> implements VideoDecoderOutputBuffer.Owner<VideoDecoderOutputBuffer> {

    // LINT.IfChange
    private static final int DAV1D_ERROR = 0;
    private static final int DAV1D_OK = 1;
    private static final int DAV1D_DECODE_ONLY = 2;
    // LINT.ThenChange(../../../../../../../jni/dav1d_jni.cc)

    private final long dav1dDecoderContext;

    @C.VideoOutputMode private volatile int outputMode;

    /**
     * Creates a Dav1dDecoder.
     *
     * @param numInputBuffers        Number of input buffers.
     * @param numOutputBuffers       Number of output buffers.
     * @param initialInputBufferSize The initial size of each input buffer, in bytes.
     * @param threads                Number of threads libgav1 will use to decode.
     * @throws Dav1dDecoderException Thrown if an exception occurs when initializing the decoder.
     */
    public Dav1dDecoder(
        int numInputBuffers, int numOutputBuffers, int initialInputBufferSize, int threads, int tiles)
        throws Dav1dDecoderException {
        super(
            new VideoDecoderInputBuffer[numInputBuffers],
            new VideoDecoderOutputBuffer[numOutputBuffers]);
        if (!Dav1dLibrary.isAvailable()) {
            throw new Dav1dDecoderException("Failed to load decoder native library.");
        }

        dav1dDecoderContext = dav1dInit(threads, tiles);
        if (dav1dDecoderContext == DAV1D_ERROR) {
            throw new Dav1dDecoderException(
                "Failed to initialize decoder. ");
        }
        setInitialInputBufferSize(initialInputBufferSize);
    }

    @Override
    public String getName() {
        return "libDav1d" + Dav1dLibrary.getVersion();
    }

    /**
     * Sets the output mode for frames rendered by the decoder.
     *
     * @param outputMode The output mode.
     */
    public void setOutputMode(@C.VideoOutputMode int outputMode) {
        this.outputMode = outputMode;
    }

    @Override
    protected VideoDecoderInputBuffer createInputBuffer() {
        return new VideoDecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    }

    @Override
    protected VideoDecoderOutputBuffer createOutputBuffer() {
         return new VideoDecoderOutputBuffer(this);
    }

    @Nullable
    @Override
    protected Dav1dDecoderException decode(
        VideoDecoderInputBuffer inputBuffer, VideoDecoderOutputBuffer outputBuffer, boolean reset) {
        ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
        int inputSize = inputData.limit();

        if (dav1dDecode(dav1dDecoderContext, inputData, inputSize) == DAV1D_ERROR) {
            return new Dav1dDecoderException(
                "dav1dDecode error: " + dav1dGetErrorMessage(dav1dDecoderContext));
        }

        boolean decodeOnly = inputBuffer.isDecodeOnly();
        if (!decodeOnly) {
            outputBuffer.init(inputBuffer.timeUs, outputMode, /* supplementalData= */ null);
        }
        // We need to dequeue the decoded frame from the decoder even when the input data is
        // decode-only.
        int getFrameResult = dav1dGetFrame(dav1dDecoderContext, outputBuffer, decodeOnly);
        if (getFrameResult == DAV1D_ERROR) {
            return new Dav1dDecoderException(
                "dav1dGetFrame error: " + dav1dGetErrorMessage(dav1dDecoderContext));
        }
        if (getFrameResult == DAV1D_DECODE_ONLY) {
            outputBuffer.addFlag(C.BUFFER_FLAG_DECODE_ONLY);
        }
        if (!decodeOnly) {
            outputBuffer.format = inputBuffer.format;
        }

        return null;
    }

    @Override
    protected Dav1dDecoderException createUnexpectedDecodeException(Throwable error) {
        return new Dav1dDecoderException("Unexpected decode error", error);
    }

    @Override
    public void release() {
        super.release();
        dav1dClose(dav1dDecoderContext);
    }

    @Override
    public void releaseOutputBuffer(VideoDecoderOutputBuffer buffer) {
        super.releaseOutputBuffer(buffer);
    }

    /**
     * Renders output buffer to the given surface. Must only be called when in {@link
     * C#VIDEO_OUTPUT_MODE_SURFACE_YUV} mode.
     *
     * @param outputBuffer Output buffer.
     * @param surface      Output surface.
     * @throws Dav1dDecoderException Thrown if called with invalid output mode or frame rendering
     *                               fails.
     */
    public void renderToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
        throws Dav1dDecoderException {
    }

    /**
     * Initializes a libDav1d decoder.
     *
     * @param threads Number of threads to be used by a dav1d decoder.
     * @return The address of the decoder context or {@link #DAV1D_ERROR} if there was an error.
     */
    private native long dav1dInit(int threads, int tiles);

    /**
     * Deallocates the decoder context.
     *
     * @param context Decoder context.
     */
    private native void dav1dClose(long context);

    /**
     * Decodes the encoded data passed.
     *
     * @param context     Decoder context.
     * @param encodedData Encoded data.
     * @param length      Length of the data buffer.
     * @return {@link #DAV1D_OK} if successful, {@link #DAV1D_ERROR} if an error occurred.
     */
    private native int dav1dDecode(long context, ByteBuffer encodedData, int length);

    /**
     * Gets the decoded frame.
     *
     * @param context      Decoder context.
     * @param outputBuffer Output buffer for the decoded frame.
     * @return {@link #DAV1D_OK} if successful, {@link #DAV1D_DECODE_ONLY} if successful but the
     * frame
     * is decode-only, {@link #DAV1D_ERROR} if an error occurred.
     */
    private native int dav1dGetFrame(
        long context, VideoDecoderOutputBuffer outputBuffer, boolean decodeOnly);

    /**
     * Returns a human-readable string describing the last error encountered in the given context.
     *
     * @param context Decoder context.
     * @return A string describing the last encountered error.
     */
    private native String dav1dGetErrorMessage(long context);
}
