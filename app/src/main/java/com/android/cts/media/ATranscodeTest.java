/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.cts.media;

import android.annotation.TargetApi;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.test.AndroidTestCase;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test for the integration of MediaMuxer and MediaCodec's encoder.
 *
 * <p>It uses MediaExtractor to get frames from a test stream, decodes them to a surface, uses a
 * shader to edit them, encodes them from the resulting surface, and then uses MediaMuxer to write
 * them into a file.
 *
 * <p>It does not currently check whether the result file is correct, but makes sure that nothing
 * fails along the way.
 *
 * <p>It also tests the way the codec config buffers need to be passed from the MediaCodec to the
 * MediaMuxer.
 */
@TargetApi(18)
public class ATranscodeTest extends AndroidTestCase {

    private static final String TAG = Utils.TAG;
    private static final boolean VERBOSE = true; // lots of logging

    /** How long to wait for the next buffer to become available. */

    /** Where to output the test files. */
    private static final File OUTPUT_FILENAME_DIR = Environment.getExternalStorageDirectory();

    // parameters for the video encoder
    private static final String OUTPUT_VIDEO_MIME_TYPE = "video/avc"; // H.264 Advanced Video Coding
    private static final int OUTPUT_VIDEO_BIT_RATE = 2000000; // 2Mbps
    private static final int OUTPUT_VIDEO_FRAME_RATE = 25; // 15fps
    private static final int OUTPUT_VIDEO_IFRAME_INTERVAL = 0; // 10 seconds between I-frames
    private static final int OUTPUT_VIDEO_COLOR_FORMAT =
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;

    // parameters for the audio encoder
    private static final String OUTPUT_AUDIO_MIME_TYPE = "audio/mp4a-latm"; // Advanced Audio Coding
    private static final int OUTPUT_AUDIO_CHANNEL_COUNT = 2; // Must match the input stream.
    private static final int OUTPUT_AUDIO_BIT_RATE = 128 * 1024;
    private static final int OUTPUT_AUDIO_AAC_PROFILE =
            MediaCodecInfo.CodecProfileLevel.AACObjectHE;
    private static final int OUTPUT_AUDIO_SAMPLE_RATE_HZ = 44100; // Must match the input stream.


    private MediaExtractor mVideoExtractor = null;
    private MediaExtractor mAudioExtractor = null;
//    private InputSurface mInputSurface = null;
//    private OutputSurface mOutputSurface = null;
    private MediaCodec mVideoDecoder = null;
    private MediaCodec mAudioDecoder = null;
    private MediaCodec mVideoEncoder = null;
    private MediaCodec mAudioEncoder = null;
    private MediaMuxer mMuxer = null;
    /**
     * Used for editing the frames.
     *
     * <p>Swaps green and blue channels by storing an RBGA color in an RGBA buffer.
     */


    /** Whether to copy the video from the test video. */
    private boolean mCopyVideo;
    /** Whether to copy the audio from the test video. */
    private boolean mCopyAudio;
    /** Width of the output frames. */
    private int mWidth = -1;
    /** Height of the output frames. */
    private int mHeight = -1;

    /** The raw resource used as the input file. */
    private int mSourceResId;

    /** The destination file for the encoded output. */
    private String mOutputFile;

//    public void testExtractDecodeEditEncodeMuxQCIF() throws Throwable {
//        setSize(176, 144);
//        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
//        setCopyVideo();
//        TestWrapper.runTest(this);
//    }
//
//    public void testExtractDecodeEditEncodeMuxQVGA() throws Throwable {
//        setSize(320, 240);
//        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
//        setCopyVideo();
//        TestWrapper.runTest(this);
//    }
//
//    public void testExtractDecodeEditEncodeMux720p() throws Throwable {
//        setSize(1280, 720);
//        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
//        setCopyVideo();
//        TestWrapper.runTest(this);
//    }
//
//    public void testExtractDecodeEditEncodeMuxAudio() throws Throwable {
//        setSize(1280, 720);
//        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
//        setCopyAudio();
//        TestWrapper.runTest(this);
//    }
    public void testExtractDecodeEditEncodeMuxAudioVideo() throws Throwable {
//        setSize(1280, 720);
        setSize(1920   , 1080);
//        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
        setSource(R.raw.bbb_s4_1280x720_mp4_h264_mp31_8mbps_30fps_aac_he_mono_40kbps_44100hz);
//        setCopyAudio();
        setCopyVideo();
        TestWrapper.runTest(this);
    }

    /** Wraps testExtractDecodeEditEncodeMux() */
    private static class TestWrapper implements Runnable {
        private Throwable mThrowable;
        private ATranscodeTest mTest;

        private TestWrapper(ATranscodeTest test) {
            mTest = test;
        }

        @Override
        public void run() {
            long beginTime=System.currentTimeMillis();
            try {

                mTest.extractDecodeEditEncodeMux();
            } catch (Throwable th) {
                mThrowable = th;
            }finally {
                long elapseTime = System.currentTimeMillis() - beginTime;
                Log.i(TAG, "elapse time:" + elapseTime);
//                Log.i(TAG, "size1:" + size1 / 1024 + ",size2:" + size2 / 1024 + ",elapseTimeHandlerBuffer:" + elapseTimeHandlerBuffer / 1000000 + ", elapseTimeWrite:" + elapseTimeWrite / 1000000);

            }
        }

        /**
         * Entry point.
         */
        public static void runTest(ATranscodeTest test) throws Throwable {
            test.setOutputFile();
            TestWrapper wrapper = new TestWrapper(test);
            Thread th = new Thread(wrapper, "codec test");
            th.start();
            th.join();
            if (wrapper.mThrowable != null) {
                throw wrapper.mThrowable;
            }
        }
    }

    /**
     * Sets the test to copy the video stream.
     */
    private void setCopyVideo() {
        mCopyVideo = true;
    }

    /**
     * Sets the test to copy the video stream.
     */
    private void setCopyAudio() {
        mCopyAudio = true;
    }

    /**
     * Sets the desired frame size.
     */
    private void setSize(int width, int height) {
        if ((width % 16) != 0 || (height % 16) != 0) {
            Log.w(TAG, "WARNING: width or height not multiple of 16");
        }
        mWidth = width;
        mHeight = height;
    }
    private static int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            if (isRecognizedFormat(colorFormat)) {
                return colorFormat;
            }
        }
        fail("couldn't find a good color format for " + codecInfo.getName() + " / " + mimeType);
        return 0;   // not reached
    }
    private static boolean isRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            // these are the formats we know how to handle for this test
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                return true;
            default:
                return false;
        }
    }
    /**
     * Sets the raw resource used as the source video.
     */
    private void setSource(int resId) {
        mSourceResId = resId;
    }

    /**
     * Sets the name of the output file based on the other parameters.
     *
     * <p>Must be called after {@link #setSize(int, int)} and {@link #setSource(int)}.
     */
    private void setOutputFile() {
        mOutputFile=Utils.generateNextMp4FileName();
    }

    /**
     * Tests encoding and subsequently decoding video from frames generated into a buffer.
     * <p>
     * We encode several frames of a video test pattern using MediaCodec, then decode the output
     * with MediaCodec and do some simple checks.
     */
    private void extractDecodeEditEncodeMux() throws Exception {
        // Exception that may be thrown during release.
        Exception exception = null;

        mDecoderOutputVideoFormat = null;
        mDecoderOutputAudioFormat = null;
        mEncoderOutputVideoFormat = null;
        mEncoderOutputAudioFormat = null;

        mOutputVideoTrack = -1;
        mOutputAudioTrack = -1;
        mVideoExtractorDone = false;
        mVideoDecoderDone = false;
        mVideoEncoderDone = false;
        mAudioExtractorDone = false;
        mAudioDecoderDone = false;
        mAudioEncoderDone = false;
        mPendingAudioDecoderOutputBufferIndices = new LinkedList<Integer>();
        mPendingAudioDecoderOutputBufferInfos = new LinkedList<MediaCodec.BufferInfo>();
        mPendingAudioEncoderInputBufferIndices = new LinkedList<Integer>();
        mPendingVideoEncoderOutputBufferIndices = new LinkedList<Integer>();
        mPendingVideoEncoderOutputBufferInfos = new LinkedList<MediaCodec.BufferInfo>();
        mPendingAudioEncoderOutputBufferIndices = new LinkedList<Integer>();
        mPendingAudioEncoderOutputBufferInfos = new LinkedList<MediaCodec.BufferInfo>();

        mPendingVideoDecoderOutputBufferIndices=new LinkedList<Integer>() ;
        mPendingVideoDecoderOutputBufferInfos=new LinkedList<MediaCodec.BufferInfo>();
        mPendingVideoEncoderInputBufferIndices=new LinkedList<Integer>() ;

        mMuxing = false;
        mVideoExtractedFrameCount = 0;
        mVideoDecodedFrameCount = 0;
        mVideoEncodedFrameCount = 0;
        mAudioExtractedFrameCount = 0;
        mAudioDecodedFrameCount = 0;
        mAudioEncodedFrameCount = 0;

        MediaCodecInfo videoCodecInfo = selectCodec(OUTPUT_VIDEO_MIME_TYPE);
        if (videoCodecInfo == null) {
            // Don't fail CTS if they don't have an AVC codec (not here, anyway).
            Log.e(TAG, "Unable to find an appropriate codec for " + OUTPUT_VIDEO_MIME_TYPE);
            return;
        }
        if (VERBOSE) Log.i(TAG, "video found codec: " + videoCodecInfo.getName());

        MediaCodecInfo audioCodecInfo = selectCodec(OUTPUT_AUDIO_MIME_TYPE);
        if (audioCodecInfo == null) {
            // Don't fail CTS if they don't have an AAC codec (not here, anyway).
            Log.e(TAG, "Unable to find an appropriate codec for " + OUTPUT_AUDIO_MIME_TYPE);
            return;
        }
        if (VERBOSE) Log.i(TAG, "audio found codec: " + audioCodecInfo.getName());

        try {
            // Creates a muxer but do not start or add tracks just yet.
            mMuxer = createMuxer();

            if (mCopyVideo) {
                mVideoExtractor = createExtractor();
                int videoInputTrack = getAndSelectVideoTrackIndex(mVideoExtractor);
                assertTrue("missing video track in test video", videoInputTrack != -1);
                MediaFormat inputFormat = mVideoExtractor.getTrackFormat(videoInputTrack);

                // We avoid the device-specific limitations on width and height by using values
                // that are multiples of 16, which all tested devices seem to be able to handle.
                MediaFormat outputVideoFormat =
                        MediaFormat.createVideoFormat(OUTPUT_VIDEO_MIME_TYPE, mWidth, mHeight);

                // Set some properties. Failing to specify some of these can cause the MediaCodec
                // configure() call to throw an unhelpful exception.

                outputVideoFormat.setInteger(
                        MediaFormat.KEY_COLOR_FORMAT, selectColorFormat(videoCodecInfo,OUTPUT_VIDEO_MIME_TYPE));
                outputVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_VIDEO_BIT_RATE);
                outputVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_VIDEO_FRAME_RATE);
                outputVideoFormat.setInteger(
                        MediaFormat.KEY_I_FRAME_INTERVAL, OUTPUT_VIDEO_IFRAME_INTERVAL);
                if (VERBOSE) Log.i(TAG, "video format: " + outputVideoFormat);

                // Create a MediaCodec for the desired codec, then configure it as an encoder with
                // our desired properties. Request a Surface to use for input.
                mVideoEncoder = createVideoEncoder(
                        videoCodecInfo, outputVideoFormat);
                mVideoDecoder = createVideoDecoder(inputFormat);
            }

            if (mCopyAudio) {
                mAudioExtractor = createExtractor();
                int audioInputTrack = getAndSelectAudioTrackIndex(mAudioExtractor);
                assertTrue("missing audio track in test video", audioInputTrack != -1);
                MediaFormat inputFormat = mAudioExtractor.getTrackFormat(audioInputTrack);

                MediaFormat outputAudioFormat =
                        MediaFormat.createAudioFormat(
                                OUTPUT_AUDIO_MIME_TYPE, OUTPUT_AUDIO_SAMPLE_RATE_HZ,
                                OUTPUT_AUDIO_CHANNEL_COUNT);
                outputAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_AUDIO_BIT_RATE);
                outputAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, OUTPUT_AUDIO_AAC_PROFILE);

                mAudioEncoder = createAudioEncoder(audioCodecInfo, outputAudioFormat);
                mAudioDecoder = createAudioDecoder(inputFormat);
            }

            awaitEncode();
        } finally {
            if (VERBOSE) Log.i(TAG, "releasing extractor, decoder, encoder, and muxer");
            // Try to release everything we acquired, even if one of the releases fails, in which
            // case we save the first exception we got and re-throw at the end (unless something
            // other exception has already been thrown). This guarantees the first exception thrown
            // is reported as the cause of the error, everything is (attempted) to be released, and
            // all other exceptions appear in the logs.
            try {
                if (mVideoExtractor != null) {
                    mVideoExtractor.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing videoExtractor", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (mAudioExtractor != null) {
                    mAudioExtractor.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing audioExtractor", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (mVideoDecoder != null) {
                    mVideoDecoder.stop();
                    mVideoDecoder.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing videoDecoder", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (mVideoEncoder != null) {
                    mVideoEncoder.stop();
                    mVideoEncoder.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing videoEncoder", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (mAudioDecoder != null) {
                    mAudioDecoder.stop();
                    mAudioDecoder.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing audioDecoder", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (mAudioEncoder != null) {
                    mAudioEncoder.stop();
                    mAudioEncoder.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing audioEncoder", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (mMuxer != null) {
                    mMuxer.stop();
                    mMuxer.release();
                }
            } catch(Exception e) {
                Log.e(TAG, "error while releasing muxer", e);
                if (exception == null) {
                    exception = e;
                }
            }
            if (mVideoDecoderHandlerThread != null) {
                mVideoDecoderHandlerThread.quitSafely();
            }
            mVideoExtractor = null;
            mAudioExtractor = null;
            mVideoDecoder = null;
            mAudioDecoder = null;
            mVideoEncoder = null;
            mAudioEncoder = null;
            mMuxer = null;
            mVideoDecoderHandlerThread = null;
        }
        if (exception != null) {
            throw exception;
        }
    }

    /**
     * Creates an extractor that reads its frames from {@link #mSourceResId}.
     */
    private MediaExtractor createExtractor() throws IOException {
        MediaExtractor extractor;
        AssetFileDescriptor srcFd = getContext().getResources().openRawResourceFd(mSourceResId);
        extractor = new MediaExtractor();
        extractor.setDataSource(srcFd.getFileDescriptor(), srcFd.getStartOffset(),
                srcFd.getLength());
        return extractor;
    }

    static class CallbackHandler extends Handler {
        CallbackHandler(Looper l) {
            super(l);
        }
        private MediaCodec mCodec;
        private boolean mEncoder;
        private MediaCodec.Callback mCallback;
        private String mMime;
        private boolean mSetDone;
        @Override
        public void handleMessage(Message msg) {
            try {
                mCodec = mEncoder ? MediaCodec.createEncoderByType(mMime) : MediaCodec.createDecoderByType(mMime);
            } catch (IOException ioe) {
            }
            mCodec.setCallback(mCallback);
            synchronized (this) {
                mSetDone = true;
                notifyAll();
            }
        }
        void create(boolean encoder, String mime, MediaCodec.Callback callback) {
            mEncoder = encoder;
            mMime = mime;
            mCallback = callback;
            mSetDone = false;
            sendEmptyMessage(0);
            synchronized (this) {
                while (!mSetDone) {
                    try {
                        wait();
                    } catch (InterruptedException ie) {
                    }
                }
            }
        }
        MediaCodec getCodec() {
            return mCodec;
        }
    }
    private HandlerThread mVideoDecoderHandlerThread;
    private CallbackHandler mVideoDecoderHandler;

    /**
     * Creates a decoder for the given format, which outputs to the given surface.
     *
     * @param inputFormat the format of the stream to decode
     */
    private MediaCodec createVideoDecoder(MediaFormat inputFormat) throws IOException {
        mVideoDecoderHandlerThread = new HandlerThread("DecoderThread");
        mVideoDecoderHandlerThread.start();
        mVideoDecoderHandler = new CallbackHandler(mVideoDecoderHandlerThread.getLooper());
        MediaCodec.Callback callback = new MediaCodec.Callback() {
            public void onError(MediaCodec codec, MediaCodec.CodecException exception) {
            }
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                mDecoderOutputVideoFormat = codec.getOutputFormat();
                if (VERBOSE) {
                    Log.i(TAG, "video decoder: output format changed: "
                            + mDecoderOutputVideoFormat);
                }
            }
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                // Extract video from file and feed to decoder.
                // We feed packets regardless of whether the muxer is set up or not.
                // If the muxer isn't set up yet, the encoder output will be queued up,
                // finally blocking the decoder as well.
                ByteBuffer decoderInputBuffer = codec.getInputBuffer(index);
                while (!mVideoExtractorDone) {
                    int size = mVideoExtractor.readSampleData(decoderInputBuffer, 0);
                    long presentationTime = mVideoExtractor.getSampleTime();
                    if (VERBOSE) {
                        Log.i(TAG, "video extractor: returned buffer of size " + size);
                        Log.i(TAG, "video extractor: returned buffer for time " + presentationTime);
                    }
                    if (size >= 0) {
                        codec.queueInputBuffer(
                                index,
                                0,
                                size,
                                presentationTime,
                                mVideoExtractor.getSampleFlags());
                    }
                    mVideoExtractorDone = !mVideoExtractor.advance();
                    if (mVideoExtractorDone) {
                        if (VERBOSE) Log.i(TAG, "video extractor: EOS");
                        codec.queueInputBuffer(
                                index,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                    mVideoExtractedFrameCount++;
                    logState();
                    if (size >= 0)
                        break;
                }
            }
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                if (VERBOSE) {
                    Log.i(TAG, "video decoder: returned output buffer: " + index+"\tsize:"+info.size);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    if (VERBOSE) Log.i(TAG, "video decoder: codec config buffer");
                    codec.releaseOutputBuffer(index, false);
                    return;
                }
                if (VERBOSE) {
                    Log.i(TAG, "video decoder: returned buffer for time " + info.presentationTimeUs+"\tsize:"+info.size);
                }
//                boolean render = info.size != 0;
//                codec.releaseOutputBuffer(index, render);
//                if (render) {
//                    mInputSurface.makeCurrent();
//                    if (VERBOSE) Log.i(TAG, "output surface: await new image");
//                    mOutputSurface.awaitNewImage();
//                    // Edit the frame and send it to the encoder.
//                    if (VERBOSE) Log.i(TAG, "output surface: draw image");
//                    mOutputSurface.drawImage();
//                    mInputSurface.setPresentationTime(
//                            info.presentationTimeUs * 1000);
//                    if (VERBOSE) Log.i(TAG, "input surface: swap buffers");
//                    mInputSurface.swapBuffers();
//                    if (VERBOSE) Log.i(TAG, "video encoder: notified of new frame");
//                    mInputSurface.releaseEGLContext();
//                }
//                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
//                    if (VERBOSE) Log.i(TAG, "video decoder: EOS");
//                    mVideoDecoderDone = true;
//                    mVideoEncoder.signalEndOfInputStream();
//                }
//                mVideoDecodedFrameCount++;


                mPendingVideoDecoderOutputBufferIndices.add(index);
                mPendingVideoDecoderOutputBufferInfos.add(info);
                mVideoDecodedFrameCount++;
                tryEncodeVideo("onOutputBufferAvailable");

                logState();
            }
        };
        // Create the decoder on a different thread, in order to have the callbacks there.
        // This makes sure that the blocking waiting and rendering in onOutputBufferAvailable
        // won't block other callbacks (e.g. blocking encoder output callbacks), which
        // would otherwise lead to the transcoding pipeline to lock up.

        // Since API 23, we could just do setCallback(callback, mVideoDecoderHandler) instead
        // of using a custom Handler and passing a message to create the MediaCodec there.

        // When the callbacks are received on a different thread, the updating of the variables
        // that are used for state logging (mVideoExtractedFrameCount, mVideoDecodedFrameCount,
        // mVideoExtractorDone and mVideoDecoderDone) should ideally be synchronized properly
        // against accesses from other threads, but that is left out for brevity since it's
        // not essential to the actual transcoding.
        mVideoDecoderHandler.create(false, getMimeTypeFor(inputFormat), callback);
        MediaCodec decoder = mVideoDecoderHandler.getCodec();
        decoder.configure(inputFormat, null, null, 0);
        decoder.start();
        return decoder;
    }

    /**
     * Creates an encoder for the given format using the specified codec, taking input from a
     * surface.
     *
     * <p>The surface to use as input is stored in the given reference.
     *
     * @param codecInfo of the codec to use
     * @param format of the stream to be produced
     */
    private MediaCodec createVideoEncoder(
            MediaCodecInfo codecInfo,
            MediaFormat format
            ) throws IOException {
        MediaCodec encoder = MediaCodec.createByCodecName(codecInfo.getName());
        encoder.setCallback(new MediaCodec.Callback() {
            public void onError(MediaCodec codec, MediaCodec.CodecException exception) {
                exception.printStackTrace();
            }
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                if (VERBOSE) Log.i(TAG, "video encoder: output format changed");
                if (mOutputVideoTrack >= 0) {
                    fail("video encoder changed its output format again?");
                }
                mEncoderOutputVideoFormat = codec.getOutputFormat();
                setupMuxer();
            }
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                Log.i(TAG, "video encoder: onInputBufferAvailable: " + index);
                mPendingVideoEncoderInputBufferIndices.add(index);
                tryEncodeVideo("onInputBufferAvailable");
            }
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                if (VERBOSE) {
                    Log.i(TAG, "video encoder: returned output buffer: " + index+"\t"+info.size);
                }
                muxVideo(index, info);
            }
        });
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        // Must be called before start() is.
        encoder.start();
        return encoder;
    }

    /**
     * Creates a decoder for the given format.
     *
     * @param inputFormat the format of the stream to decode
     */
    private MediaCodec createAudioDecoder(MediaFormat inputFormat) throws IOException {
        MediaCodec decoder = MediaCodec.createDecoderByType(getMimeTypeFor(inputFormat));
        decoder.setCallback(new MediaCodec.Callback() {
            public void onError(MediaCodec codec, MediaCodec.CodecException exception) {
            }
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                mDecoderOutputAudioFormat = codec.getOutputFormat();
                if (VERBOSE) {
                    Log.i(TAG, "audio decoder: output format changed: "
                            + mDecoderOutputAudioFormat);
                }
            }
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                ByteBuffer decoderInputBuffer = codec.getInputBuffer(index);
                while (!mAudioExtractorDone) {
                    int size = mAudioExtractor.readSampleData(decoderInputBuffer, 0);
                    long presentationTime = mAudioExtractor.getSampleTime();
                    if (VERBOSE) {
                        Log.i(TAG, "audio extractor: returned buffer of size " + size);
                        Log.i(TAG, "audio extractor: returned buffer for time " + presentationTime);
                    }
                    if (size >= 0) {
                        codec.queueInputBuffer(
                                index,
                                0,
                                size,
                                presentationTime,
                                mAudioExtractor.getSampleFlags());
                    }
                    mAudioExtractorDone = !mAudioExtractor.advance();
                    if (mAudioExtractorDone) {
                        if (VERBOSE) Log.i(TAG, "audio extractor: EOS");
                        codec.queueInputBuffer(
                                index,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                    mAudioExtractedFrameCount++;
                    logState();
                    if (size >= 0)
                        break;
                }
            }
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                if (VERBOSE) {
                    Log.i(TAG, "audio decoder: returned output buffer: " + index);
                }
                if (VERBOSE) {
                    Log.i(TAG, "audio decoder: returned buffer of size " + info.size);
                }
                ByteBuffer decoderOutputBuffer = codec.getOutputBuffer(index);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    if (VERBOSE) Log.i(TAG, "audio decoder: codec config buffer");
                    codec.releaseOutputBuffer(index, false);
                    return;
                }
                if (VERBOSE) {
                    Log.i(TAG, "audio decoder: returned buffer for time "
                            + info.presentationTimeUs);
                }
                mPendingAudioDecoderOutputBufferIndices.add(index);
                mPendingAudioDecoderOutputBufferInfos.add(info);
                mAudioDecodedFrameCount++;
                logState();
                tryEncodeAudio();
            }
        });
        decoder.configure(inputFormat, null, null, 0);
        decoder.start();
        return decoder;
    }

    /**
     * Creates an encoder for the given format using the specified codec.
     *
     * @param codecInfo of the codec to use
     * @param format of the stream to be produced
     */
    private MediaCodec createAudioEncoder(MediaCodecInfo codecInfo, MediaFormat format) throws IOException {
        MediaCodec encoder = MediaCodec.createByCodecName(codecInfo.getName());
        encoder.setCallback(new MediaCodec.Callback() {
            public void onError(MediaCodec codec, MediaCodec.CodecException exception) {
            }
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                if (VERBOSE) Log.i(TAG, "audio encoder: output format changed");
                if (mOutputAudioTrack >= 0) {
                    fail("audio encoder changed its output format again?");
                }

                mEncoderOutputAudioFormat = codec.getOutputFormat();
                setupMuxer();
            }
            public void onInputBufferAvailable(MediaCodec codec, int index) {
                if (VERBOSE) {
                    Log.i(TAG, "audio encoder: returned input buffer: " + index);
                }
                mPendingAudioEncoderInputBufferIndices.add(index);
                tryEncodeAudio();
            }
            public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                if (VERBOSE) {
                    Log.i(TAG, "audio encoder: returned output buffer: " + index);
                    Log.i(TAG, "audio encoder: returned buffer of size " + info.size);
                }
                muxAudio(index, info);
            }
        });
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        return encoder;
    }


    private void tryEncodeVideo(String tag) {
        
        if (mPendingVideoEncoderInputBufferIndices.size() == 0 ||
                mPendingVideoDecoderOutputBufferIndices.size() == 0)
            return;



        int decoderIndex = mPendingVideoDecoderOutputBufferIndices.poll();
        MediaCodec.BufferInfo info = mPendingVideoDecoderOutputBufferInfos.poll();

        if(info==null ||info.size<0){
            Log.i(TAG, "video trydecoder:000 sizeDecoderOutputBufferInfo size");
            return;
        }
        int encoderIndex = mPendingVideoEncoderInputBufferIndices.poll();
        Log.i(TAG, "video trydecoder: decoderIndex:"+decoderIndex+"\tencoderIndex:"+encoderIndex+"\tinfo:"+info.flags+"\tpts:"+info.presentationTimeUs+"\t"+tag+"\t"+info.size);

        ByteBuffer encoderInputBuffer = mVideoEncoder.getInputBuffer(encoderIndex);
        int size = info.size;
        long presentationTime = info.presentationTimeUs;

        if (size > 0) {
            ByteBuffer decoderOutputBuffer = mVideoDecoder.getOutputBuffer(decoderIndex).duplicate();
            decoderOutputBuffer.position(info.offset);
            decoderOutputBuffer.limit(info.offset + size);
            encoderInputBuffer.position(0);
            encoderInputBuffer.put(decoderOutputBuffer);

            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            mVideoEncoder.setParameters(b);

            mVideoEncoder.queueInputBuffer( encoderIndex,0,size,presentationTime,info.flags);
            Log.i(TAG, "video trydecoder: push buffer for time " + presentationTime +"\tsize:"+size+"\tflag:" +info.flags);
        }


        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            if (VERBOSE) Log.i(TAG, "video trydecoder: EOS");
            mVideoDecoderDone = true;
//            mVideoEncoder.signalEndOfInputStream();
            mVideoEncoder.queueInputBuffer( encoderIndex,0,0,presentationTime, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }

        mVideoDecoder.releaseOutputBuffer(decoderIndex, false);
        logState();
    }

    // No need to have synchronization around this, since both audio encoder and
    // decoder callbacks are on the same thread.
    private void tryEncodeAudio() {
        if (mPendingAudioEncoderInputBufferIndices.size() == 0 ||
            mPendingAudioDecoderOutputBufferIndices.size() == 0)
            return;
        int decoderIndex = mPendingAudioDecoderOutputBufferIndices.poll();
        int encoderIndex = mPendingAudioEncoderInputBufferIndices.poll();
        MediaCodec.BufferInfo info = mPendingAudioDecoderOutputBufferInfos.poll();

        ByteBuffer encoderInputBuffer = mAudioEncoder.getInputBuffer(encoderIndex);
        int size = info.size;
        long presentationTime = info.presentationTimeUs;
        if (VERBOSE) {
            Log.i(TAG, "audio decoder: processing pending buffer: "
                    + decoderIndex);
        }
        if (VERBOSE) {
            Log.i(TAG, "audio decoder: pending buffer of size " + size);
            Log.i(TAG, "audio decoder: pending buffer for time " + presentationTime);
        }
        if (size >= 0) {
            ByteBuffer decoderOutputBuffer = mAudioDecoder.getOutputBuffer(decoderIndex).duplicate();
            decoderOutputBuffer.position(info.offset);
            decoderOutputBuffer.limit(info.offset + size);
            encoderInputBuffer.position(0);
            encoderInputBuffer.put(decoderOutputBuffer);

            mAudioEncoder.queueInputBuffer(
                    encoderIndex,
                    0,
                    size,
                    presentationTime,
                    info.flags);
        }
        mAudioDecoder.releaseOutputBuffer(decoderIndex, false);
        if ((info.flags
                & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            if (VERBOSE) Log.i(TAG, "audio decoder: EOS");
            mAudioDecoderDone = true;
        }
        logState();
    }

    private void setupMuxer() {
        if (!mMuxing
                && (!mCopyAudio || mEncoderOutputAudioFormat != null)
                && (!mCopyVideo || mEncoderOutputVideoFormat != null)) {
            if (mCopyVideo) {
                Log.i(TAG, "muxer: adding video track.");
                mOutputVideoTrack = mMuxer.addTrack(mEncoderOutputVideoFormat);
            }
            if (mCopyAudio) {
                Log.i(TAG, "muxer: adding audio track.");
                mOutputAudioTrack = mMuxer.addTrack(mEncoderOutputAudioFormat);
            }
            Log.i(TAG, "muxer: starting");
            mMuxer.start();
            mMuxing = true;

            MediaCodec.BufferInfo info;
            while ((info = mPendingVideoEncoderOutputBufferInfos.poll()) != null) {
                int index = mPendingVideoEncoderOutputBufferIndices.poll().intValue();
                muxVideo(index, info);
            }
            while ((info = mPendingAudioEncoderOutputBufferInfos.poll()) != null) {
                int index = mPendingAudioEncoderOutputBufferIndices.poll().intValue();
                muxAudio(index, info);
            }
        }
    }

    private void muxVideo(int index, MediaCodec.BufferInfo info) {
        if (!mMuxing) {
            mPendingVideoEncoderOutputBufferIndices.add(new Integer(index));
            mPendingVideoEncoderOutputBufferInfos.add(info);
            return;
        }
        ByteBuffer encoderOutputBuffer = mVideoEncoder.getOutputBuffer(index);
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            if (VERBOSE) Log.i(TAG, "video mux: codec config buffer");
            // Simply ignore codec config buffers.
            mVideoEncoder.releaseOutputBuffer(index, false);
            return;
        }
        if (VERBOSE) {
            Log.i(TAG, "video mux: returned buffer for time " + info.presentationTimeUs+"\tsize:"+info.size);
        }
        if (info.size != 0) {
            mMuxer.writeSampleData(
                    mOutputVideoTrack, encoderOutputBuffer, info);
        }
        mVideoEncoder.releaseOutputBuffer(index, false);
        mVideoEncodedFrameCount++;
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            if (VERBOSE) Log.i(TAG, "video mux: EOS");
            synchronized (this) {
                mVideoEncoderDone = true;
                notifyAll();
            }
        }
        logState();
    }
    private void muxAudio(int index, MediaCodec.BufferInfo info) {
        if (!mMuxing) {
            mPendingAudioEncoderOutputBufferIndices.add(new Integer(index));
            mPendingAudioEncoderOutputBufferInfos.add(info);
            return;
        }
        ByteBuffer encoderOutputBuffer = mAudioEncoder.getOutputBuffer(index);
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            if (VERBOSE) Log.i(TAG, "audio encoder: codec config buffer");
            // Simply ignore codec config buffers.
            mAudioEncoder.releaseOutputBuffer(index, false);
            return;
        }
        if (VERBOSE) {
            Log.i(TAG, "audio encoder: returned buffer for time " + info.presentationTimeUs);
        }
        if (info.size != 0) {
            mMuxer.writeSampleData(
                    mOutputAudioTrack, encoderOutputBuffer, info);
        }
        mAudioEncoder.releaseOutputBuffer(index, false);
        mAudioEncodedFrameCount++;
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            if (VERBOSE) Log.i(TAG, "audio encoder: EOS");
            synchronized (this) {
                mAudioEncoderDone = true;
                notifyAll();
            }
        }
        logState();
    }

    /**
     * Creates a muxer to write the encoded frames.
     *
     * <p>The muxer is not started as it needs to be started only after all streams have been added.
     */
    private MediaMuxer createMuxer() throws IOException {
        return new MediaMuxer(mOutputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    private int getAndSelectVideoTrackIndex(MediaExtractor extractor) {
        for (int index = 0; index < extractor.getTrackCount(); ++index) {
            if (VERBOSE) {
                Log.i(TAG, "format for track " + index + " is "
                        + getMimeTypeFor(extractor.getTrackFormat(index)));
            }
            if (isVideoFormat(extractor.getTrackFormat(index))) {
                extractor.selectTrack(index);
                return index;
            }
        }
        return -1;
    }

    private int getAndSelectAudioTrackIndex(MediaExtractor extractor) {
        for (int index = 0; index < extractor.getTrackCount(); ++index) {
            if (VERBOSE) {
                Log.i(TAG, "format for track " + index + " is "
                        + getMimeTypeFor(extractor.getTrackFormat(index)));
            }
            if (isAudioFormat(extractor.getTrackFormat(index))) {
                extractor.selectTrack(index);
                return index;
            }
        }
        return -1;
    }

    // We will get these from the decoders when notified of a format change.
    private MediaFormat mDecoderOutputVideoFormat = null;
    private MediaFormat mDecoderOutputAudioFormat = null;
    // We will get these from the encoders when notified of a format change.
    private MediaFormat mEncoderOutputVideoFormat = null;
    private MediaFormat mEncoderOutputAudioFormat = null;

    // We will determine these once we have the output format.
    private int mOutputVideoTrack = -1;
    private int mOutputAudioTrack = -1;
    // Whether things are done on the video side.
    private boolean mVideoExtractorDone = false;
    private boolean mVideoDecoderDone = false;
    private boolean mVideoEncoderDone = false;
    // Whether things are done on the audio side.
    private boolean mAudioExtractorDone = false;
    private boolean mAudioDecoderDone = false;
    private boolean mAudioEncoderDone = false;
    private LinkedList<Integer> mPendingAudioDecoderOutputBufferIndices;
    private LinkedList<MediaCodec.BufferInfo> mPendingAudioDecoderOutputBufferInfos;
    private LinkedList<Integer> mPendingAudioEncoderInputBufferIndices;

    private LinkedList<Integer> mPendingVideoDecoderOutputBufferIndices;
    private LinkedList<MediaCodec.BufferInfo> mPendingVideoDecoderOutputBufferInfos;
    private LinkedList<Integer> mPendingVideoEncoderInputBufferIndices;

    private LinkedList<Integer> mPendingVideoEncoderOutputBufferIndices;
    private LinkedList<MediaCodec.BufferInfo> mPendingVideoEncoderOutputBufferInfos;
    private LinkedList<Integer> mPendingAudioEncoderOutputBufferIndices;
    private LinkedList<MediaCodec.BufferInfo> mPendingAudioEncoderOutputBufferInfos;

    private boolean mMuxing = false;

    private int mVideoExtractedFrameCount = 0;
    private int mVideoDecodedFrameCount = 0;
    private int mVideoEncodedFrameCount = 0;

    private int mAudioExtractedFrameCount = 0;
    private int mAudioDecodedFrameCount = 0;
    private int mAudioEncodedFrameCount = 0;

    private void logState() {
        if (VERBOSE) {
            Log.i(TAG, String.format(
                    "loop: "

                    + "V(%b){"
                    + "extracted:%d(done:%b) "
                    + "decoded:%d(done:%b) "
                    + "encoded:%d(done:%b)} "

                    + "A(%b){"
                    + "extracted:%d(done:%b) "
                    + "decoded:%d(done:%b) "
                    + "encoded:%d(done:%b) "

                    + "muxing:%b(V:%d,A:%d)",

                    mCopyVideo,
                    mVideoExtractedFrameCount, mVideoExtractorDone,
                    mVideoDecodedFrameCount, mVideoDecoderDone,
                    mVideoEncodedFrameCount, mVideoEncoderDone,

                    mCopyAudio,
                    mAudioExtractedFrameCount, mAudioExtractorDone,
                    mAudioDecodedFrameCount, mAudioDecoderDone,
                    mAudioEncodedFrameCount, mAudioEncoderDone,

                    mMuxing, mOutputVideoTrack, mOutputAudioTrack));
        }
    }

    private void awaitEncode() {
        synchronized (this) {
            while ((mCopyVideo && !mVideoEncoderDone) || (mCopyAudio && !mAudioEncoderDone)) {
                try {
                    wait();
                } catch (InterruptedException ie) {
                }
            }
        }
    Log.i(TAG,"awaitEncode");
        // Basic sanity checks.
        if (mCopyVideo) {
            assertEquals("encoded and decoded video frame counts should match",
                    mVideoDecodedFrameCount, mVideoEncodedFrameCount);
            assertTrue("decoded frame count should be less than extracted frame count",
                    mVideoDecodedFrameCount <= mVideoExtractedFrameCount);
        }
        if (mCopyAudio) {
            assertEquals("no frame should be pending", 0, mPendingAudioDecoderOutputBufferIndices.size());
        }

        // TODO: Check the generated output file.
    }

    private static boolean isVideoFormat(MediaFormat format) {
        return getMimeTypeFor(format).startsWith("video/");
    }

    private static boolean isAudioFormat(MediaFormat format) {
        return getMimeTypeFor(format).startsWith("audio/");
    }

    private static String getMimeTypeFor(MediaFormat format) {
        return format.getString(MediaFormat.KEY_MIME);
    }

    /**
     * Returns the first codec capable of encoding the specified MIME type, or null if no match was
     * found.
     */
    private static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

            if (!codecInfo.isEncoder()) {
                continue;
            }

            String[] types = codecInfo.getSupportedTypes();
            for (int j = 0; j < types.length; j++) {
                if (types[j].equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

}