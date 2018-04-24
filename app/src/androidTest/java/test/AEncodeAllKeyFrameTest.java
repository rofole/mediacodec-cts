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

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.GLES20;
import android.os.Bundle;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import com.android.cts.Utils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import javax.microedition.khronos.opengles.GL10;

import static org.junit.Assert.*;


/**
 * Generates a series of video frames, encodes them, decodes them, and tests for significant
 * divergence from the original.
 * <p>
 * We copy the data from the encoder's output buffers to the decoder's input buffers, running
 * them in parallel.  The first buffer output for video/avc contains codec configuration data,
 * which we must carefully forward to the decoder.
 * <p>
 * An alternative approach would be to save the output of the decoder as an mpeg4 video
 * file, and read it back in from disk.  The data we're generating is just an elementary
 * stream, so we'd need to perform additional steps to make that happen.
 */
@RunWith(AndroidJUnit4.class)
public class AEncodeAllKeyFrameTest  {
    private static final String TAG = "EncodeDecodeTest";
    private static final boolean VERBOSE = false;           // lots of logging
    private static final boolean DEBUG_SAVE_FILE = false;   // save copy of encoded movie
    private static final String DEBUG_FILE_NAME_BASE = "/sdcard/test.";

    // parameters for the encoder
    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 15;               // 15fps
    private static final int IFRAME_INTERVAL = 1;          // 10 seconds between I-frames

    // movie length, in frames
    private static final int NUM_FRAMES = 300;               // two seconds of video

    private static final int TEST_Y = 120;                  // YUV values for colored rect
    private static final int TEST_U = 160;
    private static final int TEST_V = 200;
    private static final int TEST_R0 = 0;                   // RGB equivalent of {0,0,0}
    private static final int TEST_G0 = 136;
    private static final int TEST_B0 = 0;
    private static final int TEST_R1 = 236;                 // RGB equivalent of {120,160,200}
    private static final int TEST_G1 = 50;
    private static final int TEST_B1 = 186;

    // size of a frame, in pixels
    private int mWidth = -1;
    private int mHeight = -1;
    // bit rate, in bits per second
    private int mBitRate = -1;

    // largest color component delta seen (i.e. actual vs. expected)
    private int mLargestColorDelta;

    private MediaMuxer mMuxer;
    private int mTrackIndex;
    private boolean mMuxerStarted;

    /**
     * Tests streaming of AVC video through the encoder and decoder.  Data is encoded from
     * a series of byte[] buffers and decoded into ByteBuffers.  The output is checked for
     * validity.
     */
    public void testEncodeDecodeVideoFromBufferToBufferQCIF() throws Exception {
        setParameters(176, 144, 1000000);
        encodeDecodeVideoFromBuffer(false);
    }

    public void testEncodeDecodeVideoFromBufferToBufferQVGA() throws Exception {
        setParameters(320, 240, 2000000);
        encodeDecodeVideoFromBuffer(false);
    }
    @Test
    public void testEncodeDecodeVideoFromBufferToBuffer720p() throws Exception {
        setParameters(1280, 720, 6000000);
        encodeDecodeVideoFromBuffer(false);
    }


    /**
     * Sets the desired frame size and bit rate.
     */
    private void setParameters(int width, int height, int bitRate) {
        if ((width % 16) != 0 || (height % 16) != 0) {
            Log.w(TAG, "WARNING: width or height not multiple of 16");
        }
        mWidth = width;
        mHeight = height;
        mBitRate = bitRate;
    }

    /**
     * Tests encoding and subsequently decoding video from frames generated into a buffer.
     * <p>
     * We encode several frames of a video test pattern using MediaCodec, then decode the
     * output with MediaCodec and do some simple checks.
     * <p>
     * See http://b.android.com/37769 for a discussion of input format pitfalls.
     */
    private void encodeDecodeVideoFromBuffer(boolean toSurface) throws Exception {
        MediaCodec encoder = null;
        MediaCodec decoder = null;

        mLargestColorDelta = -1;

        try {
            MediaCodecInfo codecInfo = selectCodec(MIME_TYPE);
            if (codecInfo == null) {
                // Don't fail CTS if they don't have an AVC codec (not here, anyway).
                Log.e(TAG, "Unable to find an appropriate codec for " + MIME_TYPE);
                return;
            }
            if (VERBOSE) Log.d(TAG, "found codec: " + codecInfo.getName());

            int colorFormat = selectColorFormat(codecInfo, MIME_TYPE);
            if (VERBOSE) Log.d(TAG, "found colorFormat: " + colorFormat);

            // We avoid the device-specific limitations on width and height by using values that
            // are multiples of 16, which all tested devices seem to be able to handle.
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);

            // Set some properties.  Failing to specify some of these can cause the MediaCodec
            // configure() call to throw an unhelpful exception.
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
            if (VERBOSE) Log.d(TAG, "format: " + format);

            // Create a MediaCodec for the desired codec, then configure it as an encoder with
            // our desired properties.
            encoder = MediaCodec.createByCodecName(codecInfo.getName());
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            // Create a MediaCodec for the decoder, just based on the MIME type.  The various
            // format details will be passed through the csd-0 meta-data later on.
            decoder = MediaCodec.createDecoderByType(MIME_TYPE);

            try {
                mMuxer = new MediaMuxer(Utils.generateNextMp4FileName(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (IOException ioe) {
                throw new RuntimeException("MediaMuxer creation failed", ioe);
            }

            mTrackIndex = -1;
            mMuxerStarted = false;

            doEncodeDecodeVideoFromBuffer(encoder, colorFormat, decoder, toSurface);
        } finally {
            if (VERBOSE) Log.d(TAG, "releasing codecs");
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
            if (decoder != null) {
                decoder.stop();
                decoder.release();
            }
            if (mMuxer != null) {
                mMuxer.stop();
                mMuxer.release();
                mMuxer = null;
            }
            Log.i(TAG, "Largest color delta: " + mLargestColorDelta);
        }
    }


    /**
     * Returns the first codec capable of encoding the specified MIME type, or null if no
     * match was found.
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

    /**
     * Returns a color format that is supported by the codec and by this test code.  If no
     * match is found, this throws a test failure -- the set of formats known to the test
     * should be expanded for new platforms.
     */
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

    /**
     * Returns true if this is a color format that this test code understands (i.e. we know how
     * to read and generate frames in this format).
     */
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
     * Returns true if the specified color format is semi-planar YUV.  Throws an exception
     * if the color format is not recognized (e.g. not YUV).
     */
    private static boolean isSemiPlanarYUV(int colorFormat) {
        switch (colorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
                return false;
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                return true;
            default:
                throw new RuntimeException("unknown format " + colorFormat);
        }
    }

    /**
     * Does the actual work for encoding frames from buffers of byte[].
     */
    private void doEncodeDecodeVideoFromBuffer(MediaCodec encoder, int encoderColorFormat,
                                               MediaCodec decoder, boolean toSurface) {
        final int TIMEOUT_USEC = 10000;
        ByteBuffer[] encoderInputBuffers = encoder.getInputBuffers();
        ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();
        ByteBuffer[] decoderInputBuffers = null;
        ByteBuffer[] decoderOutputBuffers = null;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        MediaFormat decoderOutputFormat = null;
        int generateIndex = 0;
        int checkIndex = 0;
        int badFrames = 0;
        boolean decoderConfigured = false;
        OutputSurface outputSurface = null;

        // The size of a frame of video data, in the formats we handle, is stride*sliceHeight
        // for Y, and (stride/2)*(sliceHeight/2) for each of the Cb and Cr channels.  Application
        // of algebra and assuming that stride==width and sliceHeight==height yields:
        byte[] frameData = new byte[mWidth * mHeight * 3 / 2];

        // Just out of curiosity.
        long rawSize = 0;
        long encodedSize = 0;

        // Save a copy to disk.  Useful for debugging the test.  Note this is a raw elementary
        // stream, not a .mp4 file, so not all players will know what to do with it.
        FileOutputStream outputStream = null;
        if (DEBUG_SAVE_FILE) {
            String fileName = DEBUG_FILE_NAME_BASE + mWidth + "x" + mHeight + ".mp4";
            try {
                outputStream = new FileOutputStream(fileName);
                Log.d(TAG, "encoded output will be saved as " + fileName);
            } catch (IOException ioe) {
                Log.w(TAG, "Unable to create debug output file " + fileName);
                throw new RuntimeException(ioe);
            }
        }

        if (toSurface) {
            outputSurface = new OutputSurface(mWidth, mHeight);
        }

        // Loop until the output side is done.
        boolean inputDone = false;
        boolean encoderDone = false;
        boolean outputDone = false;
        while (!outputDone) {
            if (VERBOSE) Log.d(TAG, "loop");

            // If we're not done submitting frames, generate a new one and submit it.  By
            // doing this on every loop we're working to ensure that the encoder always has
            // work to do.
            //
            // We don't really want a timeout here, but sometimes there's a delay opening
            // the encoder device, so a short timeout can keep us from spinning hard.
            if (!inputDone) {
                int inputBufIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC);
                if (VERBOSE) Log.d(TAG, "inputBufIndex=" + inputBufIndex);
                if (inputBufIndex >= 0) {
                    long ptsUsec = computePresentationTime(generateIndex);
                    if (generateIndex == NUM_FRAMES) {
                        // Send an empty frame with the end-of-stream flag set.  If we set EOS
                        // on a frame with data, that frame data will be ignored, and the
                        // output will be short one frame.
                        encoder.queueInputBuffer(inputBufIndex, 0, 0, ptsUsec,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                        if (VERBOSE) Log.d(TAG, "sent input EOS (with zero-length frame)");
//                        encoder.signalEndOfInputStream();
                    } else {
                        generateFrame(generateIndex, encoderColorFormat, frameData);

                        ByteBuffer inputBuf = encoderInputBuffers[inputBufIndex];
                        // the buffer should be sized to hold one full frame
                        assertTrue(inputBuf.capacity() >= frameData.length);
                        inputBuf.clear();
                        inputBuf.put(frameData);
                        Bundle b = new Bundle();
                        b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                        encoder.setParameters(b);
                        encoder.queueInputBuffer(inputBufIndex, 0, frameData.length, ptsUsec, MediaCodec.BUFFER_FLAG_KEY_FRAME);
                        if (VERBOSE) Log.d(TAG, "submitted frame " + generateIndex + " to enc");
                    }
                    generateIndex++;
                } else {
                    // either all in use, or we timed out during initial setup
                    if (VERBOSE) Log.d(TAG, "input buffer not available");
                }
            }

            // Check for output from the encoder.  If there's no output yet, we either need to
            // provide more input, or we need to wait for the encoder to work its magic.  We
            // can't actually tell which is the case, so if we can't get an output buffer right
            // away we loop around and see if it wants more input.
            //
            // Once we get EOS from the encoder, we don't need to do this anymore.
            if (!encoderDone) {
                int encoderStatus = encoder.dequeueOutputBuffer(info, TIMEOUT_USEC);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    if (VERBOSE) Log.d(TAG, "no output from encoder available");
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // not expected for an encoder
                    encoderOutputBuffers = encoder.getOutputBuffers();
                    if (VERBOSE) Log.d(TAG, "encoder output buffers changed");
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (mMuxerStarted) {
                        throw new RuntimeException("format changed twice");
                    }
                    MediaFormat newFormat = encoder.getOutputFormat();
                    Log.d(TAG, "encoder output format changed: " + newFormat);

                    // now that we have the Magic Goodies, start the muxer
                    mTrackIndex = mMuxer.addTrack(newFormat);
                    mMuxer.start();
                    mMuxerStarted = true;

                } else if (encoderStatus < 0) {
                    fail("unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
                } else { // encoderStatus >= 0

                    {
                        ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                        if (encodedData == null) {
                            throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                                    " was null");
                        }

                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            // The codec config data was pulled out and fed to the muxer when we got
                            // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                            if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                            info.size = 0;
                        }

                        if (info.size != 0) {
                            if (!mMuxerStarted) {
                                throw new RuntimeException("muxer hasn't started");
                            }

                            // adjust the ByteBuffer values to match BufferInfo (not needed?)
                            encodedData.position(info.offset);
                            encodedData.limit(info.offset + info.size);

                            mMuxer.writeSampleData(mTrackIndex, encodedData, info);
                            if (VERBOSE) Log.d(TAG, "sent " + info.size + " bytes to muxer");
                        }

                        encoder.releaseOutputBuffer(encoderStatus, false);

                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true;
                            if (VERBOSE) Log.d(TAG, "end of stream reached");
                            break;
                        }
                        // out of while
                    }
                }
            }


        }

        if (VERBOSE) Log.d(TAG, "decoded " + checkIndex + " frames at "
                + mWidth + "x" + mHeight + ": raw=" + rawSize + ", enc=" + encodedSize);
        if (outputStream != null)

        {
            try {
                outputStream.close();
            } catch (IOException ioe) {
                Log.w(TAG, "failed closing debug file");
                throw new RuntimeException(ioe);
            }
        }

        if (outputSurface != null)

        {
            outputSurface.release();
        }



    }

    /**
     * Does the actual work for encoding and decoding from Surface to Surface.
     */


    /**
     * Generates data for frame N into the supplied buffer.  We have an 8-frame animation
     * sequence that wraps around.  It looks like this:
     * <pre>
     *   0 1 2 3
     *   7 6 5 4
     * </pre>
     * We draw one of the eight rectangles and leave the rest set to the zero-fill color.
     */
    private void generateFrame(int frameIndex, int colorFormat, byte[] frameData) {
        final int HALF_WIDTH = mWidth / 2;
        boolean semiPlanar = isSemiPlanarYUV(colorFormat);

        // Set to zero.  In YUV this is a dull green.
        Arrays.fill(frameData, (byte) 0);

        int startX, startY, countX, countY;

        frameIndex %= 8;
        //frameIndex = (frameIndex / 8) % 8;    // use this instead for debug -- easier to see
        if (frameIndex < 4) {
            startX = frameIndex * (mWidth / 4);
            startY = 0;
        } else {
            startX = (7 - frameIndex) * (mWidth / 4);
            startY = mHeight / 2;
        }

        for (int y = startY + (mHeight / 2) - 1; y >= startY; --y) {
            for (int x = startX + (mWidth / 4) - 1; x >= startX; --x) {
                if (semiPlanar) {
                    // full-size Y, followed by UV pairs at half resolution
                    // e.g. Nexus 4 OMX.qcom.video.encoder.avc COLOR_FormatYUV420SemiPlanar
                    // e.g. Galaxy Nexus OMX.TI.DUCATI1.VIDEO.H264E
                    //        OMX_TI_COLOR_FormatYUV420PackedSemiPlanar
                    frameData[y * mWidth + x] = (byte) TEST_Y;
                    if ((x & 0x01) == 0 && (y & 0x01) == 0) {
                        frameData[mWidth * mHeight + y * HALF_WIDTH + x] = (byte) TEST_U;
                        frameData[mWidth * mHeight + y * HALF_WIDTH + x + 1] = (byte) TEST_V;
                    }
                } else {
                    // full-size Y, followed by quarter-size U and quarter-size V
                    // e.g. Nexus 10 OMX.Exynos.AVC.Encoder COLOR_FormatYUV420Planar
                    // e.g. Nexus 7 OMX.Nvidia.h264.encoder COLOR_FormatYUV420Planar
                    frameData[y * mWidth + x] = (byte) TEST_Y;
                    if ((x & 0x01) == 0 && (y & 0x01) == 0) {
                        frameData[mWidth * mHeight + (y / 2) * HALF_WIDTH + (x / 2)] = (byte) TEST_U;
                        frameData[mWidth * mHeight + HALF_WIDTH * (mHeight / 2) +
                                (y / 2) * HALF_WIDTH + (x / 2)] = (byte) TEST_V;
                    }
                }
            }
        }
    }

    /**
     * Performs a simple check to see if the frame is more or less right.
     * <p>
     * See {@link #generateFrame} for a description of the layout.  The idea is to sample
     * one pixel from the middle of the 8 regions, and verify that the correct one has
     * the non-background color.  We can't know exactly what the video encoder has done
     * with our frames, so we just check to see if it looks like more or less the right thing.
     *
     * @return true if the frame looks good
     */
    private boolean checkFrame(int frameIndex, MediaFormat format, ByteBuffer frameData) {
        // Check for color formats we don't understand.  There is no requirement for video
        // decoders to use a "mundane" format, so we just give a pass on proprietary formats.
        // e.g. Nexus 4 0x7FA30C03 OMX_QCOM_COLOR_FormatYUV420PackedSemiPlanar64x32Tile2m8ka
        int colorFormat = format.getInteger(MediaFormat.KEY_COLOR_FORMAT);
        if (!isRecognizedFormat(colorFormat)) {
            Log.d(TAG, "unable to check frame contents for colorFormat=" +
                    Integer.toHexString(colorFormat));
            return true;
        }

        boolean frameFailed = false;
        boolean semiPlanar = isSemiPlanarYUV(colorFormat);
        int width = format.getInteger(MediaFormat.KEY_WIDTH);
        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
        int halfWidth = width / 2;
        int cropLeft = format.getInteger("crop-left");
        int cropRight = format.getInteger("crop-right");
        int cropTop = format.getInteger("crop-top");
        int cropBottom = format.getInteger("crop-bottom");
        int cropWidth = cropRight - cropLeft + 1;
        int cropHeight = cropBottom - cropTop + 1;

        assertEquals(mWidth, cropWidth);
        assertEquals(mHeight, cropHeight);

        for (int i = 0; i < 8; i++) {
            int x, y;
            if (i < 4) {
                x = i * (mWidth / 4) + (mWidth / 8);
                y = mHeight / 4;
            } else {
                x = (7 - i) * (mWidth / 4) + (mWidth / 8);
                y = (mHeight * 3) / 4;
            }

            y += cropTop;
            x += cropLeft;

            int testY, testU, testV;
            if (semiPlanar) {
                // Galaxy Nexus uses OMX_TI_COLOR_FormatYUV420PackedSemiPlanar
                testY = frameData.get(y * width + x) & 0xff;
                testU = frameData.get(width * height + 2 * (y / 2) * halfWidth + 2 * (x / 2)) & 0xff;
                testV = frameData.get(width * height + 2 * (y / 2) * halfWidth + 2 * (x / 2) + 1) & 0xff;
            } else {
                // Nexus 10, Nexus 7 use COLOR_FormatYUV420Planar
                testY = frameData.get(y * width + x) & 0xff;
                testU = frameData.get(width * height + (y / 2) * halfWidth + (x / 2)) & 0xff;
                testV = frameData.get(width * height + halfWidth * (height / 2) +
                        (y / 2) * halfWidth + (x / 2)) & 0xff;
            }

            int expY, expU, expV;
            if (i == frameIndex % 8) {
                // colored rect
                expY = TEST_Y;
                expU = TEST_U;
                expV = TEST_V;
            } else {
                // should be our zeroed-out buffer
                expY = expU = expV = 0;
            }
            if (!isColorClose(testY, expY) ||
                    !isColorClose(testU, expU) ||
                    !isColorClose(testV, expV)) {
                Log.w(TAG, "Bad frame " + frameIndex + " (rect=" + i + ": yuv=" + testY +
                        "," + testU + "," + testV + " vs. expected " + expY + "," + expU +
                        "," + expV + ")");
                frameFailed = true;
            }
        }

        return !frameFailed;
    }

    /**
     * Generates a frame of data using GL commands.
     */
    private void generateSurfaceFrame(int frameIndex) {
        frameIndex %= 8;

        int startX, startY;
        if (frameIndex < 4) {
            // (0,0) is bottom-left in GL
            startX = frameIndex * (mWidth / 4);
            startY = mHeight / 2;
        } else {
            startX = (7 - frameIndex) * (mWidth / 4);
            startY = 0;
        }

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glClearColor(TEST_R0 / 255.0f, TEST_G0 / 255.0f, TEST_B0 / 255.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(startX, startY, mWidth / 4, mHeight / 2);
        GLES20.glClearColor(TEST_R1 / 255.0f, TEST_G1 / 255.0f, TEST_B1 / 255.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    }

    /**
     * Checks the frame for correctness.  Similar to {@link #checkFrame}, but uses GL to
     * read pixels from the current surface.
     *
     * @return true if the frame looks good
     */
    private boolean checkSurfaceFrame(int frameIndex) {
        ByteBuffer pixelBuf = ByteBuffer.allocateDirect(4); // TODO - reuse this
        boolean frameFailed = false;

        for (int i = 0; i < 8; i++) {
            // Note the coordinates are inverted on the Y-axis in GL.
            int x, y;
            if (i < 4) {
                x = i * (mWidth / 4) + (mWidth / 8);
                y = (mHeight * 3) / 4;
            } else {
                x = (7 - i) * (mWidth / 4) + (mWidth / 8);
                y = mHeight / 4;
            }

            GLES20.glReadPixels(x, y, 1, 1, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, pixelBuf);
            int r = pixelBuf.get(0) & 0xff;
            int g = pixelBuf.get(1) & 0xff;
            int b = pixelBuf.get(2) & 0xff;
            //Log.d(TAG, "GOT(" + frameIndex + "/" + i + "): r=" + r + " g=" + g + " b=" + b);

            int expR, expG, expB;
            if (i == frameIndex % 8) {
                // colored rect
                expR = TEST_R1;
                expG = TEST_G1;
                expB = TEST_B1;
            } else {
                // zero background color
                expR = TEST_R0;
                expG = TEST_G0;
                expB = TEST_B0;
            }
            if (!isColorClose(r, expR) ||
                    !isColorClose(g, expG) ||
                    !isColorClose(b, expB)) {
                Log.w(TAG, "Bad frame " + frameIndex + " (rect=" + i + ": rgb=" + r +
                        "," + g + "," + b + " vs. expected " + expR + "," + expG +
                        "," + expB + ")");
                frameFailed = true;
            }
        }

        return !frameFailed;
    }

    /**
     * Returns true if the actual color value is close to the expected color value.  Updates
     * mLargestColorDelta.
     */
    boolean isColorClose(int actual, int expected) {
        final int MAX_DELTA = 8;
        int delta = Math.abs(actual - expected);
        if (delta > mLargestColorDelta) {
            mLargestColorDelta = delta;
        }
        return (delta <= MAX_DELTA);
    }

    /**
     * Generates the presentation time for frame N, in microseconds.
     */
    private static long computePresentationTime(int frameIndex) {
        return 132 + frameIndex * 1000000 / FRAME_RATE;
    }
}
