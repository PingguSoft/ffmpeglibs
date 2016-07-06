/*
 * FFmpegPlayer.java
 * Copyright (c) 2012 Jacek Marchwicki
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.appunite.ffmpeg;

import java.io.FileDescriptor;
import java.util.Map;

import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.AsyncTask;
import android.util.Log;
import android.view.Surface;

public class FFmpegPlayer {
    public static int AV_LOG_QUIET   = -8;
    public static int AV_LOG_PANIC   = 0;
    public static int AV_LOG_FATAL   = 8;
    public static int AV_LOG_ERROR   = 16;
    public static int AV_LOG_WARNING = 24;
    public static int AV_LOG_INFO    = 32;
    public static int AV_LOG_VERBOSE = 40;
    public static int AV_LOG_DEBUG   = 48;

    private static class StopTask extends AsyncTask<Void, Void, Void> {

        private final FFmpegPlayer player;

        public StopTask(FFmpegPlayer player) {
            this.player = player;
        }

        @Override
        protected Void doInBackground(Void... params) {
            player.stopNative();
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if (player.mpegListener != null)
                player.mpegListener.onFFStop();
        }

    }

    private static class SetDataSourceTaskResult {
        FFmpegError error;
        FFmpegStreamInfo[] streams;
    }

    private static class SetDataSourceTask extends
            AsyncTask<Object, Void, SetDataSourceTaskResult> {

        private final FFmpegPlayer player;

        public SetDataSourceTask(FFmpegPlayer player) {
            this.player = player;
        }

        @Override
        protected SetDataSourceTaskResult doInBackground(Object... params) {
            Boolean isFD = (Boolean) params[0];
            //String url = (String) params[1];
            @SuppressWarnings("unchecked")
            Map<String, String> map = (Map<String, String>) params[2];
            Integer videoStream = (Integer) params[3];
            Integer audioStream = (Integer) params[4];
            Integer subtitleStream = (Integer) params[5];

            int videoStreamNo = videoStream == null ? -1 : videoStream.intValue();
            int audioStreamNo = audioStream == null ? -1 : audioStream.intValue();
            int subtitleStreamNo = subtitleStream == null ? -1 : subtitleStream.intValue();

            int err;
            if (isFD) {
                FileDescriptor fd = (FileDescriptor)params[1];
                err = player.setDataSourceFDNative(fd, map, videoStreamNo, audioStreamNo, subtitleStreamNo);
            } else {
                String url = (String) params[1];
                err = player.setDataSourceNative(url, map, videoStreamNo, audioStreamNo, subtitleStreamNo);
            }
            SetDataSourceTaskResult result = new SetDataSourceTaskResult();
            if (err < 0) {
                result.error = new FFmpegError(err);
                result.streams = null;
            } else {
                result.error = null;
                result.streams = player.getStreamsInfo();
            }
            return result;
        }

        @Override
        protected void onPostExecute(SetDataSourceTaskResult result) {
            if (player.mpegListener != null)
                player.mpegListener.onFFDataSourceLoaded(result.error,
                        result.streams);
        }

    }

    private static class SeekTask extends
            AsyncTask<Long, Void, NotPlayingException> {

        private final FFmpegPlayer player;

        public SeekTask(FFmpegPlayer player) {
            this.player = player;
        }

        @Override
        protected NotPlayingException doInBackground(Long... params) {
            try {
                player.seekNative(params[0].longValue());
            } catch (NotPlayingException e) {
                return e;
            }
            return null;
        }

        @Override
        protected void onPostExecute(NotPlayingException result) {
            if (player.mpegListener != null)
                player.mpegListener.onFFSeeked(result);
        }

    }

    private static class PauseTask extends
            AsyncTask<Void, Void, NotPlayingException> {

        private final FFmpegPlayer player;

        public PauseTask(FFmpegPlayer player) {
            this.player = player;
        }

        @Override
        protected NotPlayingException doInBackground(Void... params) {
            try {
                player.pauseNative();
                return null;
            } catch (NotPlayingException e) {
                return e;
            }
        }

        @Override
        protected void onPostExecute(NotPlayingException result) {
            if (player.mpegListener != null)
                player.mpegListener.onFFPause(result);
        }

    }

    private static class ResumeTask extends
            AsyncTask<Void, Void, NotPlayingException> {

        private final FFmpegPlayer player;

        public ResumeTask(FFmpegPlayer player) {
            this.player = player;
        }

        @Override
        protected NotPlayingException doInBackground(Void... params) {
            try {
                player.resumeNative();
                return null;
            } catch (NotPlayingException e) {
                return e;
            }
        }

        @Override
        protected void onPostExecute(NotPlayingException result) {
            if (player.mpegListener != null)
                player.mpegListener.onFFResume(result);
        }

    }


    static {
        NativeTester nativeTester = new NativeTester();
        if (nativeTester.isNeon()) {
            Log.i("ffmpeg", "NEON LIB LOADED!!!");
            System.loadLibrary("ffmpeg-neon");
            System.loadLibrary("ffmpeg-jni-neon");
        } else {
            System.loadLibrary("ffmpeg");
            System.loadLibrary("ffmpeg-jni");
        }
    }

    public static final int UNKNOWN_STREAM = -1;
    public static final int NO_STREAM = -2;
    private FFmpegListener mpegListener = null;
    private final RenderedFrame mRenderedFrame = new RenderedFrame();

    private int mNativePlayer;
    //private final Activity activity;

    private Runnable updateTimeRunnable = new Runnable() {

        @Override
        public void run() {
            if (mpegListener != null) {
                mpegListener.onFFUpdateTime(mCurrentTimeUs,
                    mVideoDurationUs, mIsFinished);
            }
        }

    };

    private long mCurrentTimeUs;
    private long mVideoDurationUs;
    private FFmpegStreamInfo[] mStreamsInfos = null;
    private boolean mIsFinished = false;

    static class RenderedFrame {
        public Bitmap bitmap;
        public int height;
        public int width;
    }

    public FFmpegPlayer(FFmpegDisplay videoView, int loglevel) { //, Activity activity) {
        //this.activity = activity;
        int error = initNative(loglevel);
        if (error != 0)
            throw new RuntimeException(String.format(
                    "Could not initialize player: %d", error));
        videoView.setMpegPlayer(this);
    }

    @Override
    protected void finalize() throws Throwable {
        deallocNative();
        super.finalize();
    }

    private native int initNative(int loglevel);

    private native void deallocNative();

    private native int setDataSourceNative(String url,
            Map<String, String> dictionary, int videoStreamNo,
            int audioStreamNo, int subtitleStreamNo);

   private native int setDataSourceFDNative(FileDescriptor fd,
            Map<String, String> dictionary, int videoStreamNo,
            int audioStreamNo, int subtitleStreamNo);

    private native void stopNative();

    native void renderFrameStart();

    native void renderFrameStop();

    private native void seekNative(long positionUs) throws NotPlayingException;

    private native long getVideoDurationNative();

    public native void render(Surface surface);

    public native Bitmap getVideoBitmapNative();

    public native int createFiFoNative(String filename);

    public native void enableFilterNative(String filename);

    /**
     *
     * @param streamsInfos
     *            - could be null
     */
    private void setStreamsInfo(FFmpegStreamInfo[] streamsInfos) {
        mStreamsInfos = streamsInfos;
    }

    /**
     * Return streamsInfo
     *
     * @return return streams info after successful setDataSource or null
     */
    protected FFmpegStreamInfo[] getStreamsInfo() {
        return mStreamsInfos;
    }

    public void stop() {
        new StopTask(this).execute();
    }

    private native void pauseNative() throws NotPlayingException;

    private native void resumeNative() throws NotPlayingException;

    public void pause() {
        new PauseTask(this).execute();
    }

    public void seek(long positionUs) {
        new SeekTask(this).execute(Long.valueOf(positionUs));
    }

    public void resume() {
        new ResumeTask(this).execute();
    }

    private Bitmap prepareFrame(int width, int height) {
        // Bitmap bitmap =
        // Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Bitmap bitmap = Bitmap.createBitmap(width, height,
                Bitmap.Config.ARGB_8888);
        mRenderedFrame.height = height;
        mRenderedFrame.width = width;
        Log.i("ffmpeg", "frame:" + width + "x" + height);

        return bitmap;
    }

    private void onUpdateTime(long currentUs, long maxUs, boolean isFinished) {

        mCurrentTimeUs = currentUs;
        mVideoDurationUs = maxUs;
        mIsFinished  = isFinished;
        //activity.runOnUiThread(updateTimeRunnable);

        if (mpegListener != null) {
            mpegListener.onFFUpdateTime(mCurrentTimeUs,
                mVideoDurationUs, mIsFinished);
        }
    }

    private int mVidWidth = 0;
    private int mVidHeight = 0;

    private void onUpdateVideo(int width, int height, long currentUs) {
        //Log.i("ffmpeg", "frame:" + width + "x" + height + ", ts:" + currentUs);
        if (mpegListener != null && (width != mVidWidth || height != mVidHeight)) {
            mpegListener.onVideoSizeChanged(width, height);
            mVidWidth  = width;
            mVidHeight = height;
        }
    }

    private AudioTrack prepareAudioTrack(int sampleRateInHz,
            int numberOfChannels) {

        for (;;) {
            int channelConfig;
            if (numberOfChannels == 1) {
                channelConfig = AudioFormat.CHANNEL_OUT_MONO;
            } else if (numberOfChannels == 2) {
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
            } else if (numberOfChannels == 3) {
                channelConfig = AudioFormat.CHANNEL_OUT_FRONT_CENTER
                        | AudioFormat.CHANNEL_OUT_FRONT_RIGHT
                        | AudioFormat.CHANNEL_OUT_FRONT_LEFT;
            } else if (numberOfChannels == 4) {
                channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
            } else if (numberOfChannels == 5) {
                channelConfig = AudioFormat.CHANNEL_OUT_QUAD
                        | AudioFormat.CHANNEL_OUT_LOW_FREQUENCY;
            } else if (numberOfChannels == 6) {
                channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
            } else if (numberOfChannels == 8) {
                channelConfig = AudioFormat.CHANNEL_OUT_7POINT1;
            } else {
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
            }
            try {
                int minBufferSize = AudioTrack.getMinBufferSize(sampleRateInHz,
                        channelConfig, AudioFormat.ENCODING_PCM_16BIT);
                AudioTrack audioTrack = new AudioTrack(
                        AudioManager.STREAM_MUSIC, sampleRateInHz,
                        channelConfig, AudioFormat.ENCODING_PCM_16BIT,
                        minBufferSize, AudioTrack.MODE_STREAM);
                return audioTrack;
            } catch (IllegalArgumentException e) {
                if (numberOfChannels > 2) {
                    numberOfChannels = 2;
                } else if (numberOfChannels > 1) {
                    numberOfChannels = 1;
                } else {
                    throw e;
                }
            }
        }
    }

    private void setVideoListener(FFmpegListener mpegListener) {
        setMpegListener(mpegListener);
    }

    public void setDataSource(String url) {
        setDataSource(url, null, UNKNOWN_STREAM, UNKNOWN_STREAM, NO_STREAM);
    }

    public void setDataSource(String url, Map<String, String> dictionary,
            int videoStream, int audioStream, int subtitlesStream) {
        new SetDataSourceTask(this).execute(false, url, dictionary,
                Integer.valueOf(videoStream), Integer.valueOf(audioStream),
                Integer.valueOf(subtitlesStream));
    }

    public void setDataSource(FileDescriptor fd, Map<String, String> dictionary,
            int videoStream, int audioStream, int subtitlesStream) {
        new SetDataSourceTask(this).execute(true, fd, dictionary,
                Integer.valueOf(videoStream), Integer.valueOf(audioStream),
                Integer.valueOf(subtitlesStream));
    }

    public FFmpegListener getMpegListener() {
        return mpegListener;
    }

    public void setMpegListener(FFmpegListener mpegListener) {
        this.mpegListener = mpegListener;
    }

    public Bitmap captureVideoFrame() {
        return getVideoBitmapNative();
    }

    public int createFiFo(String filename) {
        return createFiFoNative(filename);
    }

    public void enableFilter(String filters) {
        enableFilterNative(filters);
    }
}
