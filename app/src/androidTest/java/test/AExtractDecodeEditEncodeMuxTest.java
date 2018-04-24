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

package test;

import android.annotation.TargetApi;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;
import android.view.Surface;

import com.android.cts.R;
import com.android.cts.Utils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertTrue;

/**
 * Test for the integration of MediaMuxer and MediaCodec's encoder.
 * <p>
 * <p>It uses MediaExtractor to get frames from a test stream, decodes them to a surface, uses a
 * shader to edit them, encodes them from the resulting surface, and then uses MediaMuxer to write
 * them into a file.
 * <p>
 * <p>It does not currently check whether the result file is correct, but makes sure that nothing
 * fails along the way.
 * <p>
 * <p>It also tests the way the codec config buffers need to be passed from the MediaCodec to the
 * MediaMuxer.
 */
@TargetApi(18)
@RunWith(AndroidJUnit4.class)
public class AExtractDecodeEditEncodeMuxTest extends AExampleInstrumentedTest {

    private static final String TAG = Utils.TAG;
    private static final boolean VERBOSE = true; // lots of logging

    /**
     * How long to wait for the next buffer to become available.
     */
    private static final int TIMEOUT_USEC = 10000;

    /**
     * Where to output the test files.
     */
    private static final File OUTPUT_FILENAME_DIR = Environment.getExternalStorageDirectory();

    // parameters for the video encoder
    private static final String OUTPUT_VIDEO_MIME_TYPE = "video/avc"; // H.264 Advanced Video Coding
    private static final int OUTPUT_VIDEO_BIT_RATE = 10000000; // 2Mbps
    private static final int OUTPUT_VIDEO_FRAME_RATE = 30; // 15fps
    private static final int OUTPUT_VIDEO_IFRAME_INTERVAL = 1; // 10 seconds between I-frames
    private static final int OUTPUT_VIDEO_COLOR_FORMAT =
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface;

    // parameters for the audio encoder
    private static final String OUTPUT_AUDIO_MIME_TYPE = "audio/mp4a-latm"; // Advanced Audio Coding
    private static final int OUTPUT_AUDIO_CHANNEL_COUNT = 2; // Must match the input stream.
    private static final int OUTPUT_AUDIO_BIT_RATE = 128 * 1024;
    private static final int OUTPUT_AUDIO_AAC_PROFILE =
            MediaCodecInfo.CodecProfileLevel.AACObjectHE;
    private static final int OUTPUT_AUDIO_SAMPLE_RATE_HZ = 44100; // Must match the input stream.

    /**
     * Used for editing the frames.
     * <p>
     * <p>Swaps green and blue channels by storing an RBGA color in an RGBA buffer.
     */
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = texture2D(sTexture, vTextureCoord).rbga;\n" +
                    "}\n";

    /**
     * Whether to copy the video from the test video.
     */
    private boolean mCopyVideo;
    /**
     * Whether to copy the audio from the test video.
     */
    private boolean mCopyAudio;
    /**
     * Width of the output frames.
     */
    private int mWidth = -1;
    /**
     * Height of the output frames.
     */
    private int mHeight = -1;

    /**
     * The raw resource used as the input file.
     */
    private int mSourceResId;

    /**
     * The destination file for the encoded output.
     */
    private String mOutputFile;

    private long size1 = 0;
    private long size2 = 0;
    private long elapseTimeHandlerBuffer = 0;
    private long elapseTimeWrite = 0;


    public void testExtractDecodeEditEncodeMuxQCIF() throws Throwable {
        setSize(176, 144);
        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
        setCopyVideo();
        TestWrapper.runTest(this);
    }

    public void testExtractDecodeEditEncodeMuxQVGA() throws Throwable {
        setSize(320, 240);
        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
        setCopyVideo();
        TestWrapper.runTest(this);
    }

    @Test
    public void testExtractDecodeEditEncodeMux720p() throws Throwable {
        setSize(1280, 720);
//        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
        setSource(R.raw.bbb_s4_1280x720_mp4_h264_mp31_8mbps_30fps_aac_he_mono_40kbps_44100hz);
        setCopyVideo();
        TestWrapper.runTest(this);
    }

    public void testExtractDecodeEditEncodeMuxAudio() throws Throwable {
        setSize(1280, 720);
        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
        setCopyAudio();
        TestWrapper.runTest(this);
    }

    public void testExtractDecodeEditEncodeMuxAudioVideo() throws Throwable {
        setSize(1280, 720);
        setSource(R.raw.video_480x360_mp4_h264_500kbps_30fps_aac_stereo_128kbps_44100hz);
        setCopyAudio();
        setCopyVideo();
        TestWrapper.runTest(this);
    }

    /**
     * Wraps testExtractDecodeEditEncodeMux()
     */
    private static class TestWrapper implements Runnable {
        private Throwable mThrowable;
        private AExtractDecodeEditEncodeMuxTest mTest;

        private TestWrapper(AExtractDecodeEditEncodeMuxTest test) {
            mTest = test;
        }

        @Override
        public void run() {
            try {
                mTest.extractDecodeEditEncodeMux();
            } catch (Throwable th) {
                mThrowable = th;
            }
        }

        /**
         * Entry point.
         */
        public static void runTest(AExtractDecodeEditEncodeMuxTest test) throws Throwable {
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

    /**
     * Sets the raw resource used as the source video.
     */
    private void setSource(int resId) {
        mSourceResId = resId;
    }

    /**
     * Sets the name of the output file based on the other parameters.
     * <p>
     * <p>Must be called after {@link #setSize(int, int)} and {@link #setSource(int)}.
     */
    private void setOutputFile() {
//        StringBuilder sb = new StringBuilder();
//        sb.append(OUTPUT_FILENAME_DIR.getAbsolutePath());
//        sb.append("/cts-media-");
//        sb.append(getClass().getSimpleName());
//        assertTrue("should have called setSource() first", mSourceResId != -1);
//        sb.append('-');
//        sb.append(mSourceResId);
//        if (mCopyVideo) {
//            assertTrue("should have called setSize() first", mWidth != -1);
//            assertTrue("should have called setSize() first", mHeight != -1);
//            sb.append('-');
//            sb.append("video");
//            sb.append('-');
//            sb.append(mWidth);
//            sb.append('x');
//            sb.append(mHeight);
//        }
//        if (mCopyAudio) {
//            sb.append('-');
//            sb.append("audio");
//        }
//
//        sb.append(".mp4");
//        mOutputFile = sb.toString();
        mOutputFile = generateProjectId() + ".mp4";
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

        MediaCodecInfo videoCodecInfo = selectCodec(OUTPUT_VIDEO_MIME_TYPE);
        if (videoCodecInfo == null) {
            // Don't fail CTS if they don't have an AVC codec (not here, anyway).
            Log.e(TAG, "Unable to find an appropriate codec for " + OUTPUT_VIDEO_MIME_TYPE);
            return;
        }
        if (VERBOSE) Log.d(TAG, "video found codec: " + videoCodecInfo.getName());

        MediaCodecInfo audioCodecInfo = selectCodec(OUTPUT_AUDIO_MIME_TYPE);
        if (audioCodecInfo == null) {
            // Don't fail CTS if they don't have an AAC codec (not here, anyway).
            Log.e(TAG, "Unable to find an appropriate codec for " + OUTPUT_AUDIO_MIME_TYPE);
            return;
        }
        if (VERBOSE) Log.d(TAG, "audio found codec: " + audioCodecInfo.getName());

        MediaExtractor videoExtractor = null;
        MediaExtractor audioExtractor = null;
        test.OutputSurface outputSurface = null;
        MediaCodec videoDecoder = null;
        MediaCodec audioDecoder = null;
        MediaCodec videoEncoder = null;
        MediaCodec audioEncoder = null;
        MediaMuxer muxer = null;
        FileOutputStream fileOutputStream = null;
        InputSurface inputSurface = null;
        long beginTime = System.currentTimeMillis();
        try {
            if (mCopyVideo) {
                videoExtractor = createExtractor();
                int videoInputTrack = getAndSelectVideoTrackIndex(videoExtractor);
                assertTrue("missing video track in test video", videoInputTrack != -1);
                MediaFormat inputFormat = videoExtractor.getTrackFormat(videoInputTrack);

                // We avoid the device-specific limitations on width and height by using values
                // that are multiples of 16, which all tested devices seem to be able to handle.
                MediaFormat outputVideoFormat =
                        MediaFormat.createVideoFormat(OUTPUT_VIDEO_MIME_TYPE, mWidth, mHeight);

                // Set some properties. Failing to specify some of these can cause the MediaCodec
                // configure() call to throw an unhelpful exception.
                outputVideoFormat.setInteger(
                        MediaFormat.KEY_COLOR_FORMAT, OUTPUT_VIDEO_COLOR_FORMAT);
                outputVideoFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_VIDEO_BIT_RATE);
                outputVideoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_VIDEO_FRAME_RATE);
                outputVideoFormat.setInteger(
                        MediaFormat.KEY_I_FRAME_INTERVAL, OUTPUT_VIDEO_IFRAME_INTERVAL);
                if (VERBOSE) Log.d(TAG, "video format: " + outputVideoFormat);

                // Create a MediaCodec for the desired codec, then configure it as an encoder with
                // our desired properties. Request a Surface to use for input.
                AtomicReference<Surface> inputSurfaceReference = new AtomicReference<Surface>();
                videoEncoder = createVideoEncoder(
                        videoCodecInfo, outputVideoFormat, inputSurfaceReference);
                inputSurface = new InputSurface(inputSurfaceReference.get());
                inputSurface.makeCurrent();
                // Create a MediaCodec for the decoder, based on the extractor's format.
                outputSurface = new test.OutputSurface();
                outputSurface.changeFragmentShader(FRAGMENT_SHADER);
                videoDecoder = createVideoDecoder(inputFormat, outputSurface.getSurface());
            }

            if (mCopyAudio) {
                audioExtractor = createExtractor();
                int audioInputTrack = getAndSelectAudioTrackIndex(audioExtractor);
                assertTrue("missing audio track in test video", audioInputTrack != -1);
                MediaFormat inputFormat = audioExtractor.getTrackFormat(audioInputTrack);

                MediaFormat outputAudioFormat =
                        MediaFormat.createAudioFormat(
                                OUTPUT_AUDIO_MIME_TYPE, OUTPUT_AUDIO_SAMPLE_RATE_HZ,
                                OUTPUT_AUDIO_CHANNEL_COUNT);
                outputAudioFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_AUDIO_BIT_RATE);
                outputAudioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, OUTPUT_AUDIO_AAC_PROFILE);

                // Create a MediaCodec for the desired codec, then configure it as an encoder with
                // our desired properties. Request a Surface to use for input.
                audioEncoder = createAudioEncoder(audioCodecInfo, outputAudioFormat);
                // Create a MediaCodec for the decoder, based on the extractor's format.
                audioDecoder = createAudioDecoder(inputFormat);
            }

            // Creates a muxer but do not start or add tracks just yet.
            muxer = createMuxer();
            fileOutputStream = createFileOutputStream();

            doExtractDecodeEditEncodeMux(
                    videoExtractor,
                    audioExtractor,
                    videoDecoder,
                    audioDecoder,
                    fileOutputStream
            );
        } finally {
            if (VERBOSE) Log.d(TAG, "releasing extractor, decoder, encoder, and muxer");
            // Try to release everything we acquired, even if one of the releases fails, in which
            // case we save the first exception we got and re-throw at the end (unless something
            // other exception has already been thrown). This guarantees the first exception thrown
            // is reported as the cause of the error, everything is (attempted) to be released, and
            // all other exceptions appear in the logs.
            long elapseTime = System.currentTimeMillis() - beginTime;
            Log.i(TAG, "elapse time:" + elapseTime);
            Log.i(TAG, "size1:" + size1  + ",size2:" + size2  + ",elapseTimeHandlerBuffer:" + elapseTimeHandlerBuffer / 1000000 + ", elapseTimeWrite:" + elapseTimeWrite / 1000000);
            try {
                if (videoExtractor != null) {
                    videoExtractor.release();
                }
            } catch (Exception e) {
                Log.e(TAG, "error while releasing videoExtractor", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (audioExtractor != null) {
                    audioExtractor.release();
                }
            } catch (Exception e) {
                Log.e(TAG, "error while releasing audioExtractor", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (videoDecoder != null) {
                    videoDecoder.stop();
                    videoDecoder.release();
                }
            } catch (Exception e) {
                Log.e(TAG, "error while releasing videoDecoder", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (outputSurface != null) {
                    outputSurface.release();
                }
            } catch (Exception e) {
                Log.e(TAG, "error while releasing outputSurface", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (videoEncoder != null) {
                    videoEncoder.stop();
                    videoEncoder.release();
                }
            } catch (Exception e) {
                Log.e(TAG, "error while releasing videoEncoder", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (audioDecoder != null) {
                    audioDecoder.stop();
                    audioDecoder.release();
                }
            } catch (Exception e) {
                Log.e(TAG, "error while releasing audioDecoder", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (audioEncoder != null) {
                    audioEncoder.stop();
                    audioEncoder.release();
                }
            } catch (Exception e) {
                Log.e(TAG, "error while releasing audioEncoder", e);
                if (exception == null) {
                    exception = e;
                }
            }
            try {
                if (fileOutputStream != null) {
                    fileOutputStream.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "error while releasing fileOutputStream", e);
                if (exception == null) {
                    exception = e;
                }
            }
//            try {
//                if (muxer != null) {
//                    muxer.stop();
//                    muxer.release();
//                }
//            } catch (Exception e) {
//                Log.e(TAG, "error while releasing muxer", e);
//                if (exception == null) {
//                    exception = e;
//                }
//            }
            try {
                if (inputSurface != null) {
                    inputSurface.release();
                }
            } catch (Exception e) {
                Log.e(TAG, "error while releasing inputSurface", e);
                if (exception == null) {
                    exception = e;
                }
            }
        }
        if (exception != null) {
            throw exception;
        }
    }

    /**
     * Creates an extractor that reads its frames from {@link #mSourceResId}.
     */
    private MediaExtractor createExtractor() {
        try {
            MediaExtractor extractor;
            AssetFileDescriptor srcFd = getContext().getResources().openRawResourceFd(mSourceResId);
            extractor = new MediaExtractor();
            extractor.setDataSource(srcFd.getFileDescriptor(), srcFd.getStartOffset(),
                    srcFd.getLength());
            return extractor;
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a decoder for the given format, which outputs to the given surface.
     *
     * @param inputFormat the format of the stream to decode
     * @param surface     into which to decode the frames
     */
    private MediaCodec createVideoDecoder(MediaFormat inputFormat, Surface surface) {
        try {
            MediaCodec decoder = MediaCodec.createDecoderByType(getMimeTypeFor(inputFormat));
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();
            return decoder;
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates an encoder for the given format using the specified codec, taking input from a
     * surface.
     * <p>
     * <p>The surface to use as input is stored in the given reference.
     *
     * @param codecInfo        of the codec to use
     * @param format           of the stream to be produced
     * @param surfaceReference to store the surface to use as input
     */
    private MediaCodec createVideoEncoder(
            MediaCodecInfo codecInfo,
            MediaFormat format,
            AtomicReference<Surface> surfaceReference) {
        try {
            MediaCodec encoder = MediaCodec.createByCodecName(codecInfo.getName());
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

            // Must be called before start() is.
            surfaceReference.set(encoder.createInputSurface());
            encoder.start();
            return encoder;
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a decoder for the given format.
     *
     * @param inputFormat the format of the stream to decode
     */
    private MediaCodec createAudioDecoder(MediaFormat inputFormat) {
        try {
            MediaCodec decoder = MediaCodec.createDecoderByType(getMimeTypeFor(inputFormat));
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();
            return decoder;
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates an encoder for the given format using the specified codec.
     *
     * @param codecInfo of the codec to use
     * @param format    of the stream to be produced
     */
    private MediaCodec createAudioEncoder(MediaCodecInfo codecInfo, MediaFormat format) {
        try {
            MediaCodec encoder = MediaCodec.createByCodecName(codecInfo.getName());
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            return encoder;
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates a muxer to write the encoded frames.
     * <p>
     * <p>The muxer is not started as it needs to be started only after all streams have been added.
     */
    private MediaMuxer createMuxer() throws IOException {

        return new MediaMuxer(mOutputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    private FileOutputStream createFileOutputStream() throws FileNotFoundException {
        FileOutputStream fout = new FileOutputStream(mOutputFile);
        return fout;
    }

    private int getAndSelectVideoTrackIndex(MediaExtractor extractor) {
        for (int index = 0; index < extractor.getTrackCount(); ++index) {
            if (VERBOSE) {
                Log.d(TAG, "format for track " + index + " is "
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
                Log.d(TAG, "format for track " + index + " is "
                        + getMimeTypeFor(extractor.getTrackFormat(index)));
            }
            if (isAudioFormat(extractor.getTrackFormat(index))) {
                extractor.selectTrack(index);
                return index;
            }
        }
        return -1;
    }

    /**
     * Does the actual work for extracting, decoding, encoding and muxing.
     */
    private void doExtractDecodeEditEncodeMux(
            MediaExtractor videoExtractor,
            MediaExtractor audioExtractor,
            MediaCodec videoDecoder,
            MediaCodec audioDecoder,
            FileOutputStream fileOutputStream

    ) {

        ByteBuffer[] videoDecoderInputBuffers = null;
        ByteBuffer[] videoDecoderOutputBuffers = null;
        ByteBuffer[] videoEncoderOutputBuffers = null;
        MediaCodec.BufferInfo videoDecoderOutputBufferInfo = null;
        MediaCodec.BufferInfo videoEncoderOutputBufferInfo = null;
        if (mCopyVideo) {
            videoDecoderInputBuffers = videoDecoder.getInputBuffers();
            videoDecoderOutputBuffers = videoDecoder.getOutputBuffers();
            videoDecoderOutputBufferInfo = new MediaCodec.BufferInfo();
            videoEncoderOutputBufferInfo = new MediaCodec.BufferInfo();
        }
        ByteBuffer[] audioDecoderInputBuffers = null;
        ByteBuffer[] audioDecoderOutputBuffers = null;
        ByteBuffer[] audioEncoderInputBuffers = null;
        ByteBuffer[] audioEncoderOutputBuffers = null;
        MediaCodec.BufferInfo audioDecoderOutputBufferInfo = null;
        MediaCodec.BufferInfo audioEncoderOutputBufferInfo = null;
        if (mCopyAudio) {
            audioDecoderInputBuffers = audioDecoder.getInputBuffers();
            audioDecoderOutputBuffers = audioDecoder.getOutputBuffers();
            audioDecoderOutputBufferInfo = new MediaCodec.BufferInfo();
            audioEncoderOutputBufferInfo = new MediaCodec.BufferInfo();
        }
        // We will get these from the decoders when notified of a format change.
        MediaFormat decoderOutputVideoFormat = null;
        MediaFormat decoderOutputAudioFormat = null;
        // We will get these from the encoders when notified of a format change.
        MediaFormat encoderOutputVideoFormat = null;
        MediaFormat encoderOutputAudioFormat = null;
        // We will determine these once we have the output format.
        int outputVideoTrack = -1;
        int outputAudioTrack = -1;
        // Whether things are done on the video side.
        boolean videoExtractorDone = false;
        boolean videoDecoderDone = false;
        boolean videoEncoderDone = false;
        // Whether things are done on the audio side.
        boolean audioExtractorDone = false;
        boolean audioDecoderDone = false;
        boolean audioEncoderDone = false;
        // The audio decoder output buffer to process, -1 if none.
        int pendingAudioDecoderOutputBufferIndex = -1;

        boolean muxing = false;

        int videoExtractedFrameCount = 0;
        int videoDecodedFrameCount = 0;
        int videoEncodedFrameCount = 0;

        int audioExtractedFrameCount = 0;
        int audioDecodedFrameCount = 0;
        int audioEncodedFrameCount = 0;

//        while ((mCopyVideo && !videoEncoderDone) || (mCopyAudio && !audioEncoderDone)) {
        while (mCopyVideo && !videoDecoderDone) {
            if (VERBOSE) {
                Log.d(TAG, String.format(
                        "loop: "

                                + "V(%b){"
                                + "extracted:%d(done:%b) "
                                + "decoded:%d(done:%b) "
                                + "encoded:%d(done:%b)} "

                                + "A(%b){"
                                + "extracted:%d(done:%b) "
                                + "decoded:%d(done:%b) "
                                + "encoded:%d(done:%b) "
                                + "pending:%d} "

                                + "muxing:%b(V:%d,A:%d)",

                        mCopyVideo,
                        videoExtractedFrameCount, videoExtractorDone,
                        videoDecodedFrameCount, videoDecoderDone,
                        videoEncodedFrameCount, videoEncoderDone,

                        mCopyAudio,
                        audioExtractedFrameCount, audioExtractorDone,
                        audioDecodedFrameCount, audioDecoderDone,
                        audioEncodedFrameCount, audioEncoderDone,
                        pendingAudioDecoderOutputBufferIndex,

                        muxing, outputVideoTrack, outputAudioTrack));
            }

            // Extract video from file and feed to decoder.
            // Do not extract video if we have determined the output format but we are not yet
            // ready to mux the frames.
            while (mCopyVideo && !videoExtractorDone
                    && (encoderOutputVideoFormat == null || muxing)) {
                int decoderInputBufferIndex = videoDecoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (decoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (VERBOSE) Log.d(TAG, "no video decoder input buffer");
                    break;
                }
                if (VERBOSE) {
                    Log.d(TAG, "video decoder: returned input buffer: " + decoderInputBufferIndex);
                }
                ByteBuffer decoderInputBuffer = videoDecoderInputBuffers[decoderInputBufferIndex];
                int size = videoExtractor.readSampleData(decoderInputBuffer, 0);
                long presentationTime = videoExtractor.getSampleTime();
                if (VERBOSE) {
                    Log.d(TAG, "video extractor: returned buffer of size " + size);
                    Log.d(TAG, "video extractor: returned buffer for time " + presentationTime);
                }
                if (size >= 0) {
                    videoDecoder.queueInputBuffer(
                            decoderInputBufferIndex,
                            0,
                            size,
                            presentationTime,
                            videoExtractor.getSampleFlags());
                }
                videoExtractorDone = !videoExtractor.advance();
                if (videoExtractorDone) {
                    if (VERBOSE) Log.d(TAG, "video extractor: EOS");
                    videoDecoder.queueInputBuffer(
                            decoderInputBufferIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
                videoExtractedFrameCount++;
                // We extracted a frame, let's try something else next.
                break;
            }

            // Extract audio from file and feed to decoder.
            // Do not extract audio if we have determined the output format but we are not yet
            // ready to mux the frames.
            while (mCopyAudio && !audioExtractorDone
                    && (encoderOutputAudioFormat == null || muxing)) {
                int decoderInputBufferIndex = audioDecoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (decoderInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (VERBOSE) Log.d(TAG, "no audio decoder input buffer");
                    break;
                }
                if (VERBOSE) {
                    Log.d(TAG, "audio decoder: returned input buffer: " + decoderInputBufferIndex);
                }
                ByteBuffer decoderInputBuffer = audioDecoderInputBuffers[decoderInputBufferIndex];
                int size = audioExtractor.readSampleData(decoderInputBuffer, 0);
                long presentationTime = audioExtractor.getSampleTime();
                if (VERBOSE) {
                    Log.d(TAG, "audio extractor: returned buffer of size " + size);
                    Log.d(TAG, "audio extractor: returned buffer for time " + presentationTime);
                }
                if (size >= 0) {
                    audioDecoder.queueInputBuffer(
                            decoderInputBufferIndex,
                            0,
                            size,
                            presentationTime,
                            audioExtractor.getSampleFlags());
                }
                audioExtractorDone = !audioExtractor.advance();
                if (audioExtractorDone) {
                    if (VERBOSE) Log.d(TAG, "audio extractor: EOS");
                    audioDecoder.queueInputBuffer(
                            decoderInputBufferIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
                audioExtractedFrameCount++;
                // We extracted a frame, let's try something else next.
                break;
            }

            // Poll output frames from the video decoder and feed the encoder.
            while (mCopyVideo && !videoDecoderDone
                    && (encoderOutputVideoFormat == null || muxing)) {
                int decoderOutputBufferIndex =
                        videoDecoder.dequeueOutputBuffer(
                                videoDecoderOutputBufferInfo, TIMEOUT_USEC);
                if (decoderOutputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (VERBOSE) Log.d(TAG, "no video decoder output buffer");
                    break;
                }
                if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    if (VERBOSE) Log.d(TAG, "video decoder: output buffers changed");
                    videoDecoderOutputBuffers = videoDecoder.getOutputBuffers();
                    break;
                }
                if (decoderOutputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    decoderOutputVideoFormat = videoDecoder.getOutputFormat();
                    Log.i(TAG, "video decoder: output format changed: "
                            + decoderOutputVideoFormat);
                    break;
                }
                if (VERBOSE) {
                    Log.d(TAG, "video decoder: returned output buffer: "
                            + decoderOutputBufferIndex);
                    Log.d(TAG, "video decoder: returned buffer of size "
                            + videoDecoderOutputBufferInfo.size);
                }
                ByteBuffer decoderOutputBuffer =
                        videoDecoderOutputBuffers[decoderOutputBufferIndex];
                if ((videoDecoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        != 0) {
                    if (VERBOSE) Log.d(TAG, "video decoder: codec config buffer");
                    videoDecoder.releaseOutputBuffer(decoderOutputBufferIndex, false);
                    break;
                }
                Log.i(TAG, "video decoder: returned buffer for time "
                        + videoDecoderOutputBufferInfo.presentationTimeUs + "\t" + videoDecoderOutputBufferInfo.size + "\t" + (videoDecoderOutputBufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME));
                boolean render = videoDecoderOutputBufferInfo.size != 0;

                //GL 交换,送到编码器
                if (render) {
//                    if (VERBOSE) Log.d(TAG, "output surface: await new image");
//                    outputSurface.awaitNewImage();
//                    // Edit the frame and send it to the encoder.
//                    if (VERBOSE) Log.d(TAG, "output surface: draw image");
//                    outputSurface.drawImage();
//                    inputSurface.setPresentationTime(
//                            videoDecoderOutputBufferInfo.presentationTimeUs * 1000);
//
//
//                    if (VERBOSE) Log.d(TAG, "input surface: swap buffers");
//                    inputSurface.swapBuffers();
//                    if (VERBOSE) Log.d(TAG, "video encoder: notified of new frame");

                    //压缩 存储

                    try {
                        long startTime = System.nanoTime();

                        decoderOutputBuffer.position(videoDecoderOutputBufferInfo.offset);
                        decoderOutputBuffer.limit(videoDecoderOutputBufferInfo.offset + videoDecoderOutputBufferInfo.size);
                        int length = videoDecoderOutputBufferInfo.size;

                        byte[] bytesArray = new byte[length];
                        decoderOutputBuffer.get(bytesArray, videoDecoderOutputBufferInfo.offset, bytesArray.length);

                        byte[] zipedBuffer = null;
                        boolean zip = false;
                        if (zip) {
                            decoderOutputBuffer.get(bytesArray, 0, bytesArray.length);
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            GZIPOutputStream gzip = new GZIPOutputStream(baos);
                            gzip.write(bytesArray);
                            gzip.close();
                            zipedBuffer = baos.toByteArray();
                            baos.flush();
                            baos.close();
                        }

                        boolean writeFile = true;
                        if (writeFile) {
                            long time2 = System.nanoTime();
                            fileOutputStream.write(zip ? zipedBuffer : bytesArray);
                            elapseTimeWrite += (System.nanoTime() - time2);
                        }


                        long zipSize = zipedBuffer != null ? zipedBuffer.length : 0;
                        size1 += length;
                        size2 += zipSize;
                        long t = (System.nanoTime() - startTime);
                        elapseTimeHandlerBuffer += t;
                        Log.i(TAG, "unzipSize:" + length/1024.0 + "K, zipSize:" + zipSize/1024.0 + "K,time:" + t/1000.0/1000.0+"ms");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                }
                videoDecoder.releaseOutputBuffer(decoderOutputBufferIndex, render);
                if ((videoDecoderOutputBufferInfo.flags
                        & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (VERBOSE) Log.d(TAG, "video decoder: EOS");
                    videoDecoderDone = true;
                }
                videoDecodedFrameCount++;
                // We extracted a pending frame, let's try something else next.
                break;
            }

            // Poll output frames from the audio decoder.
            // Do not poll if we already have a pending buffer to feed to the encoder.


        }

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
