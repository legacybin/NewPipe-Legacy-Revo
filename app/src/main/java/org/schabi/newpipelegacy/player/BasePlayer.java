/*
 * Copyright 2017 Mauricio Colli <mauriciocolli@outlook.com>
 * BasePlayer.java is part of NewPipe
 *
 * License: GPL-3.0+
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.schabi.newpipelegacy.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.PlaybackParameters;
import com.nostra13.universalimageloader.core.ImageLoader;
import com.nostra13.universalimageloader.core.assist.FailReason;
import com.nostra13.universalimageloader.core.listener.ImageLoadingListener;

import org.schabi.newpipelegacy.BuildConfig;
import org.schabi.newpipelegacy.DownloaderImpl;
import org.schabi.newpipelegacy.R;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipelegacy.local.history.HistoryRecordManager;
import org.schabi.newpipelegacy.player.helper.PlayerHelper;
import org.schabi.newpipelegacy.player.playqueue.PlayQueue;
import org.schabi.newpipelegacy.player.playqueue.PlayQueueAdapter;
import org.schabi.newpipelegacy.player.playqueue.PlayQueueItem;
import org.schabi.newpipelegacy.player.resolver.MediaSourceTag;
import org.schabi.newpipelegacy.util.ImageDisplayConstants;
import org.schabi.newpipelegacy.util.ListHelper;
import org.schabi.newpipelegacy.util.SerializedCache;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.disposables.SerialDisposable;

/**
 * Base for the players, joining the common properties
 *
 * @author mauriciocolli
 */
@SuppressWarnings({"WeakerAccess"})
public abstract class BasePlayer implements
        ImageLoadingListener {

    public static final boolean DEBUG = !BuildConfig.BUILD_TYPE.equals("release");
    @NonNull
    public static final String TAG = "BasePlayer";

    @NonNull
    final protected Context context;

    @NonNull
    final protected BroadcastReceiver broadcastReceiver;
    @NonNull
    final protected IntentFilter intentFilter;

    @NonNull
    final protected HistoryRecordManager recordManager;

    @NonNull
    final private SerialDisposable progressUpdateReactor;
    @NonNull
    final private CompositeDisposable databaseUpdateReactor;
    /*//////////////////////////////////////////////////////////////////////////
    // Intent
    //////////////////////////////////////////////////////////////////////////*/

    @NonNull
    public static final String REPEAT_MODE = "repeat_mode";
    @NonNull
    public static final String PLAYBACK_PITCH = "playback_pitch";
    @NonNull
    public static final String PLAYBACK_SPEED = "playback_speed";
    @NonNull
    public static final String PLAYBACK_SKIP_SILENCE = "playback_skip_silence";
    @NonNull
    public static final String PLAYBACK_QUALITY = "playback_quality";
    @NonNull
    public static final String PLAY_QUEUE_KEY = "play_queue_key";
    @NonNull
    public static final String APPEND_ONLY = "append_only";
    @NonNull
    public static final String RESUME_PLAYBACK = "resume_playback";
    @NonNull
    public static final String START_PAUSED = "start_paused";
    @NonNull
    public static final String SELECT_ON_APPEND = "select_on_append";
    @NonNull
    public static final String IS_MUTED = "is_muted";

    /*//////////////////////////////////////////////////////////////////////////
    // Playback
    //////////////////////////////////////////////////////////////////////////*/

    protected static final float[] PLAYBACK_SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};

    protected static final int REPEAT_MODE_OFF = 0;
    /**
     * "Repeat One" mode to repeat the currently playing window infinitely.
     */
    protected static final int REPEAT_MODE_ONE = 1;
    /**
     * "Repeat All" mode to repeat the entire timeline infinitely.
     */
    protected static final int REPEAT_MODE_ALL = 2;

    protected PlayQueue playQueue;
    protected PlayQueueAdapter playQueueAdapter;

    @Nullable
    private MediaSourceTag currentMetadata;
    @Nullable
    private Bitmap currentThumbnail;

    @Nullable
    protected Toast errorToast;

    protected String playbackQuality = "";

    /*//////////////////////////////////////////////////////////////////////////
    // Player
    //////////////////////////////////////////////////////////////////////////*/

    protected final static int PROGRESS_LOOP_INTERVAL_MILLIS = 500;

    protected MediaPlayer mMediaPlayer;
    protected LibVLC mLibVLC;

    private Disposable stateLoader;

    private int repeatMode;
    private PlaybackParameters playBackParameters;
    private int lastVolume;

    //////////////////////////////////////////////////////////////////////////*/

    public BasePlayer(@NonNull final Context context) {
        this.context = context;

        this.broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onBroadcastReceived(intent);
            }
        };
        this.intentFilter = new IntentFilter();
        setupBroadcastReceiver(intentFilter);

        this.recordManager = new HistoryRecordManager(context);

        this.progressUpdateReactor = new SerialDisposable();
        this.databaseUpdateReactor = new CompositeDisposable();

        final String userAgent = DownloaderImpl.USER_AGENT;
    }

    public void setup() {
        if (mMediaPlayer == null) {
            initPlayer(/*playOnInit=*/true);
        }
        initListeners();
    }

    public void initPlayer(final boolean playOnReady) {

        if (mMediaPlayer != null && !mMediaPlayer.isReleased()) {
            mMediaPlayer.stop();
        }

        final ArrayList<String> args = new ArrayList<>();
        args.add("-vvv");
        args.add("--audio-time-stretch");
        args.add("--avcodec-skiploopfilter");
        args.add("--avcodec-skip-frame");
        args.add("2" );
        args.add("--avcodec-skip-idct");
        args.add("2");
        args.add("--stats");

        // repeat mode arg
        String mode = "";
        switch (getRepeatMode()) {
            case  BasePlayer.REPEAT_MODE_ONE:
                mode = "--input-repeat=1";
                break;
            case BasePlayer.REPEAT_MODE_ALL:
                mode = "--input-repeat=65535";
                break;
            default:
                mode = "--input-repeat=0";
                break;
        }

        Log.d(TAG, "mode: " + mode);

        args.add(mode);

        // pitch setting
        args.add("--audio-filter=scaletempo_pitch");
        args.add("--pitch-shift=" + getPlaybackPitch());
        args.add("--sub-track=0");


        mLibVLC = new LibVLC(context, args);
        mMediaPlayer = new MediaPlayer(mLibVLC);

        mMediaPlayer.setEventListener(mPlayerListener);

        if (playOnReady) {

            if (playQueue == null) {
                Log.d(TAG, "playOnReady with null playQueue ");
                return;
            }

            StreamInfo stream = playQueue.getItem().getStream().blockingGet();

            initThumbnail(stream.getThumbnailUrl());


            int streamIndex;
            String streamUrl;

            if (stream.getStreamType() == StreamType.LIVE_STREAM || stream.getStreamType() == StreamType.AUDIO_LIVE_STREAM) {
                streamUrl = stream.getHlsUrl().isEmpty() ? stream.getDashMpdUrl() : stream.getHlsUrl();
            } else if (stream.getVideoStreams().isEmpty()
                    && stream.getVideoOnlyStreams().isEmpty()) {
                streamUrl = stream.getAudioStreams().get(0).getUrl();
            } else {
                if (playbackQuality.isEmpty()) {
                    streamIndex = ListHelper.getDefaultResolutionIndex(context, stream.getVideoStreams());
                    streamUrl = stream.getVideoStreams().get(streamIndex).getUrl();
                    playbackQuality = stream.getVideoStreams().get(streamIndex).getResolution();
                } else {
                    streamIndex = ListHelper.getResolutionIndex(context, stream.getVideoStreams(), playbackQuality);
                    streamUrl = stream.getVideoStreams().get(streamIndex).getUrl();
                }
            }

            Log.d(TAG, "playbackQuality: " + playbackQuality);

            Media media = new Media(mLibVLC, Uri.parse(streamUrl));
            mMediaPlayer.setMedia(media);

            String userPreferredLanguage = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(context.getString(R.string.caption_user_set_key), null);

            if (userPreferredLanguage != null) {

                List<SubtitlesStream> subtitlesStream = playQueue.getItem().getStream().blockingGet().getSubtitles();
                for (int i = 0; i < subtitlesStream.size(); i++) {
                    if (subtitlesStream.get(i).getDisplayLanguageName().contentEquals(userPreferredLanguage)) {
                        Media.Slave slave = new Media.Slave(Media.Slave.Type.Subtitle, 1, subtitlesStream.get(i).getURL());
                        media.addSlave(slave);
                    }
                }
            }

            // playback speed
            mMediaPlayer.setRate(getPlaybackSpeed());

            maybeUpdateCurrentMetadata();

            mMediaPlayer.play();

            mMediaPlayer.setTime(playQueue.getItem().getRecoveryPosition());
        }

        registerBroadcastReceiver();
    }

    public void initListeners() {
    }

    public void handleIntent(Intent intent) {
        if (DEBUG) Log.d(TAG, "handleIntent() called with: intent = [" + intent + "]");
        if (intent == null) return;

        // Resolve play queue
        if (!intent.hasExtra(PLAY_QUEUE_KEY)) return;
        final String intentCacheKey = intent.getStringExtra(PLAY_QUEUE_KEY);
        final PlayQueue queue = SerializedCache.getInstance().take(intentCacheKey, PlayQueue.class);

        if (queue == null) return;

        // Resolve append intents
        if (intent.getBooleanExtra(APPEND_ONLY, false) && playQueue != null) {
            int sizeBeforeAppend = playQueue.size();
            playQueue.append(queue.getStreams());

            if ((intent.getBooleanExtra(SELECT_ON_APPEND, false)
                    || getCurrentState() == STATE_COMPLETED) && queue.getStreams().size() > 0) {
                playQueue.setIndex(sizeBeforeAppend);
            }

            return;
        }

        if (mMediaPlayer != null)
            mMediaPlayer.stop();

        final int repeatMode = intent.getIntExtra(REPEAT_MODE, getRepeatMode());
        final float playbackSpeed = intent.getFloatExtra(PLAYBACK_SPEED, getPlaybackSpeed());
        final float playbackPitch = intent.getFloatExtra(PLAYBACK_PITCH, getPlaybackPitch());
        final boolean playbackSkipSilence = intent.getBooleanExtra(PLAYBACK_SKIP_SILENCE,
                getPlaybackSkipSilence());
        final boolean isMuted = intent.getBooleanExtra(IS_MUTED, mMediaPlayer == null ? false : isMuted());

        // seek to timestamp if stream is already playing
        if (mMediaPlayer != null
                && queue.size() == 1
                && playQueue != null
                && playQueue.getItem() != null
                && queue.getItem().getUrl().equals(playQueue.getItem().getUrl())
                && queue.getItem().getRecoveryPosition() != PlayQueueItem.RECOVERY_UNSET
        ) {
            initPlayer(true);
            return;

        } else if (intent.getBooleanExtra(RESUME_PLAYBACK, false) && isPlaybackResumeEnabled()) {
            final PlayQueueItem item = queue.getItem();
            if (item != null && item.getRecoveryPosition() == PlayQueueItem.RECOVERY_UNSET) {
                stateLoader = recordManager.loadStreamState(item)
                        .observeOn(AndroidSchedulers.mainThread())
                        .doFinally(() -> initPlayback(queue, repeatMode, playbackSpeed, playbackPitch, playbackSkipSilence,
                                /*playOnInit=*/true, isMuted))
                        .subscribe(
                                state -> queue.setRecovery(queue.getIndex(), state.getProgressTime()),
                                error -> {
                                    if (DEBUG) error.printStackTrace();
                                }
                        );
                databaseUpdateReactor.add(stateLoader);
                return;
            }
        }
        // Good to go...
        initPlayback(queue, repeatMode, playbackSpeed, playbackPitch, playbackSkipSilence,
                /*playOnInit=*/!intent.getBooleanExtra(START_PAUSED, false), isMuted);
    }

    protected void initPlayback(@NonNull final PlayQueue queue,
                                final int repeatMode,
                                final float playbackSpeed,
                                final float playbackPitch,
                                final boolean playbackSkipSilence,
                                final boolean playOnReady,
                                final boolean isMuted) {
        setRepeatMode(repeatMode);
        setPlaybackParameters(playbackSpeed, playbackPitch, playbackSkipSilence);

        destroyPlayer();

        playQueue = queue;
        playQueue.init();

        initPlayer(playOnReady);

        if (playQueueAdapter != null) {
            playQueueAdapter.dispose();
        }

        playQueueAdapter = new PlayQueueAdapter(context, playQueue);

        mMediaPlayer.setVolume(isMuted ? 0 : 100);

    }

    public void destroyPlayer() {
        if (DEBUG) Log.d(TAG, "destroyPlayer() called");

        try {
            if (mMediaPlayer != null && !mMediaPlayer.isReleased()) {
                mMediaPlayer.stop();
                mLibVLC.release();
                mMediaPlayer.release();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        if (isProgressLoopRunning()) stopProgressLoop();
        if (playQueue != null) playQueue.dispose();

        //if (mediaSessionManager != null) mediaSessionManager.dispose();
        if (stateLoader != null) stateLoader.dispose();

        if (playQueueAdapter != null) {
            playQueueAdapter.unsetSelectedListener();
            playQueueAdapter.dispose();
        }
    }

    public void destroy() {
        if (DEBUG) Log.d(TAG, "destroy() called");
        destroyPlayer();
        unregisterBroadcastReceiver();

        databaseUpdateReactor.clear();
        progressUpdateReactor.set(null);

    }

    /*//////////////////////////////////////////////////////////////////////////
    // Thumbnail Loading
    //////////////////////////////////////////////////////////////////////////*/

    private void initThumbnail(final String url) {
        if (DEBUG) Log.d(TAG, "Thumbnail - initThumbnail() called");
        if (url == null || url.isEmpty()) return;
        ImageLoader.getInstance().resume();
        ImageLoader.getInstance().loadImage(url, ImageDisplayConstants.DISPLAY_THUMBNAIL_OPTIONS,
                this);
    }

    @Override
    public void onLoadingStarted(String imageUri, View view) {
        if (DEBUG) Log.d(TAG, "Thumbnail - onLoadingStarted() called on: " +
                "imageUri = [" + imageUri + "], view = [" + view + "]");
    }

    @Override
    public void onLoadingFailed(String imageUri, View view, FailReason failReason) {
        Log.e(TAG, "Thumbnail - onLoadingFailed() called on imageUri = [" + imageUri + "]",
                failReason.getCause());
        currentThumbnail = null;
    }

    @Override
    public void onLoadingComplete(String imageUri, View view, Bitmap loadedImage) {
        if (DEBUG) Log.d(TAG, "Thumbnail - onLoadingComplete() called with: " +
                "imageUri = [" + imageUri + "], view = [" + view + "], " +
                "loadedImage = [" + loadedImage + "]");
        currentThumbnail = loadedImage;
    }

    @Override
    public void onLoadingCancelled(String imageUri, View view) {
        if (DEBUG) Log.d(TAG, "Thumbnail - onLoadingCancelled() called with: " +
                "imageUri = [" + imageUri + "], view = [" + view + "]");
        currentThumbnail = null;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Broadcast Receiver
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * Add your action in the intentFilter
     *
     * @param intentFilter intent filter that will be used for register the receiver
     */
    protected void setupBroadcastReceiver(IntentFilter intentFilter) {
        intentFilter.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
    }

    public void onBroadcastReceived(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        switch (intent.getAction()) {
            case AudioManager.ACTION_AUDIO_BECOMING_NOISY:
                onPause();
                break;
        }
    }

    protected void registerBroadcastReceiver() {
        // Try to unregister current first
        unregisterBroadcastReceiver();
        context.registerReceiver(broadcastReceiver, intentFilter);
    }

    protected void unregisterBroadcastReceiver() {
        try {
            context.unregisterReceiver(broadcastReceiver);
        } catch (final IllegalArgumentException unregisteredException) {
            Log.w(TAG, "Broadcast receiver already unregistered (" + unregisteredException.getMessage() + ")");
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // States Implementation
    //////////////////////////////////////////////////////////////////////////*/

    public static final int STATE_PREFLIGHT = -1;
    public static final int STATE_BLOCKED = 123;
    public static final int STATE_PLAYING = 124;
    public static final int STATE_BUFFERING = 125;
    public static final int STATE_PAUSED = 126;
    public static final int STATE_PAUSED_SEEK = 127;
    public static final int STATE_COMPLETED = 128;

    protected int currentState = STATE_PREFLIGHT;

    public void changeState(int state) {
        if (DEBUG) Log.d(TAG, "changeState() called with: state = [" + state + "]");
        currentState = state;
        switch (state) {
            case STATE_BLOCKED:
                onBlocked();
                break;
            case STATE_PLAYING:
                onPlaying();
                break;
            case STATE_BUFFERING:
                onBuffering();
                break;
            case STATE_PAUSED:
                onPaused();
                break;
            case STATE_PAUSED_SEEK:
                onPausedSeek();
                break;
            case STATE_COMPLETED:
                onCompleted();
                break;
        }
    }

    public void onBlocked() {
        if (DEBUG) Log.d(TAG, "onBlocked() called");
        if (!isProgressLoopRunning()) startProgressLoop();
    }

    public void onPlaying() {
        if (DEBUG) Log.d(TAG, "onPlaying() called");
        if (!isProgressLoopRunning()) startProgressLoop();
    }

    public void onBuffering() {
    }

    public void onPaused() {
        if (isProgressLoopRunning()) {
            stopProgressLoop();
        }
            mMediaPlayer.pause();
    }

    public void onPausedSeek() {
    }

    public void onCompleted() {
        if (DEBUG) Log.d(TAG, "onCompleted() called");
        if (playQueue.getIndex() < playQueue.size() - 1) playQueue.offsetIndex(+1);
        if (isProgressLoopRunning()) stopProgressLoop();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Repeat and shuffle
    //////////////////////////////////////////////////////////////////////////*/

    public void onRepeatClicked() {
        if (DEBUG) Log.d(TAG, "onRepeatClicked() called");

        final int mode;

        switch (getRepeatMode()) {
            case BasePlayer.REPEAT_MODE_OFF:
                mode = BasePlayer.REPEAT_MODE_ONE;
                break;
            case BasePlayer.REPEAT_MODE_ONE:
                mode = BasePlayer.REPEAT_MODE_ALL;
                break;
            case BasePlayer.REPEAT_MODE_ALL:
            default:
                mode = BasePlayer.REPEAT_MODE_OFF;
                break;
        }

        setRepeatMode(mode);
        if (DEBUG) Log.d(TAG, "onRepeatClicked() currentRepeatMode = " + getRepeatMode());
    }

    public void onShuffleClicked() {
        if (DEBUG) Log.d(TAG, "onShuffleClicked() called");

        if (playQueue.isShuffled())
            playQueue.unshuffle();
        else
            playQueue.shuffle();
    }
    /*//////////////////////////////////////////////////////////////////////////
    // Mute / Unmute
    //////////////////////////////////////////////////////////////////////////*/

    public void onMuteUnmuteButtonClicked() {
        if (mMediaPlayer == null) return;

        if (DEBUG) Log.d(TAG, "onMuteUnmuteButtonClicled() called");
        mMediaPlayer.setVolume(isMuted() ? 100 : 0);

    }

    public boolean isMuted() {
        return mMediaPlayer.getVolume() == 0;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Progress Updates
    //////////////////////////////////////////////////////////////////////////*/

    public abstract void onUpdateProgress(int currentProgress, int duration, int bufferPercent);

    protected void startProgressLoop() {
        progressUpdateReactor.set(getProgressReactor());
    }

    protected void stopProgressLoop() {
        progressUpdateReactor.set(null);
    }

    public void triggerProgressUpdate() {
        if (mMediaPlayer == null) return;

        onUpdateProgress(
                Math.max((int) mMediaPlayer.getTime(), 0),
                (int) mMediaPlayer.getLength(),
                0
        );
    }

    private Disposable getProgressReactor() {
        return Observable.interval(PROGRESS_LOOP_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> triggerProgressUpdate(),
                        error -> Log.e(TAG, "Progress update failure: ", error));
    }

    /*//////////////////////////////////////////////////////////////////////////
    // ExoPlayer Listener
    //////////////////////////////////////////////////////////////////////////*/
    public void onPlaybackParametersChanged(final PlaybackParameters playbackParameters) {

    }

    public void onLoadingChanged(final boolean isLoading) {
        // Disable default behavior
    }

    public void onRepeatModeChanged(final int i) {

    }

    /*//////////////////////////////////////////////////////////////////////////
    // Playback Listener
    //////////////////////////////////////////////////////////////////////////*/

    protected void onMetadataChanged(@NonNull final MediaSourceTag tag) {
        final StreamInfo info = tag.getMetadata();
        if (DEBUG) {
            Log.d(TAG, "Playback - onMetadataChanged() called, playing: " + info.getName());
        }

        initThumbnail(info.getThumbnailUrl());
        registerView();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // General Player
    //////////////////////////////////////////////////////////////////////////*/

    public void onPlaybackShutdown() {
        if (DEBUG) {
            Log.d(TAG, "Shutting down...");
        }
        destroy();
    }

    public void showStreamError(Exception exception) {
        exception.printStackTrace();

        if (errorToast == null) {
            errorToast = Toast.makeText(context, R.string.player_stream_failure, Toast.LENGTH_SHORT);
            errorToast.show();
        }
    }

    public void showRecoverableError(Exception exception) {
        exception.printStackTrace();

        if (errorToast == null) {
            errorToast = Toast.makeText(context, R.string.player_recoverable_failure, Toast.LENGTH_SHORT);
            errorToast.show();
        }
    }

    public void showUnrecoverableError(Exception exception) {
        exception.printStackTrace();

        if (errorToast != null) {
            errorToast.cancel();
        }
        errorToast = Toast.makeText(context, R.string.player_unrecoverable_failure, Toast.LENGTH_SHORT);
        errorToast.show();
    }

    public void onPlay() {
        if (DEBUG) Log.d(TAG, "onPlay() called");

        if (playQueue == null || mMediaPlayer == null) return;

        if (getCurrentState() == STATE_COMPLETED) {
            if (playQueue.getIndex() != 0) {
                playQueue.setIndex(0);
            }

            setRecovery(playQueue.getIndex(), 0);
            initPlayer(true);
            return;
        }

        initPlayback(getPlayQueue(), getRepeatMode(),
                getPlaybackSpeed(), getPlaybackPitch(),
                false, true, isMuted());

    }

    public void onPause() {
        if (DEBUG) Log.d(TAG, "onPause() called");

        if (mMediaPlayer == null) return;

        setRecovery(playQueue.getIndex(), mMediaPlayer.getTime());
        mMediaPlayer.pause();
    }

    public void onPlayPause() {
        if (DEBUG) Log.d(TAG, "onPlayPause() called");

        if (isPlaying()) {
            onPause();
        } else {
            onPlay();
        }
    }

    public void onFastRewind() {
        if (DEBUG) Log.d(TAG, "onFastRewind() called");
        seekBy(-getSeekDuration());
    }

    public void onFastForward() {
        if (DEBUG) Log.d(TAG, "onFastForward() called");
        seekBy(getSeekDuration());
    }

    private int getSeekDuration() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String key = context.getString(R.string.seek_duration_key);
        final String value = prefs.getString(key, context.getString(R.string.seek_duration_default_value));
        return Integer.parseInt(value);
    }

    public void onPlayPrevious() {
        if (DEBUG) Log.d(TAG, "onPlayPrevious() called");

        if (playQueue == null) return;

        if (playQueue.getIndex() == 0) {
            setRecovery(playQueue.getIndex(), 0);
            playQueue.offsetIndex(0);
        } else {
            savePlaybackState();
            playQueue.offsetIndex(-1);
        }

        initPlayer(true);
    }

    public void onPlayNext() {
        if (playQueue == null) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "onPlayNext() called");
        }

        savePlaybackState();

        playQueue.offsetIndex(+1);

        initPlayer(true);
    }

    public void onSelected(final PlayQueueItem item) {
        if (playQueue == null || mMediaPlayer == null) return;

        final int index = playQueue.indexOf(item);
        if (index == -1) return;

        if (playQueue.getIndex() == index) {
            seekToDefault();
        } else {
            savePlaybackState();
        }

        playQueue.setIndex(index);

        initPlayer(true);

    }

    public void seekTo(long positionMillis) {
        if (DEBUG) Log.d(TAG, "seekBy() called with: position = [" + positionMillis + "]");
        if (mMediaPlayer != null) mMediaPlayer.setTime(positionMillis);
    }

    public void seekBy(long offsetMillis) {
        if (DEBUG) Log.d(TAG, "seekBy() called with: offsetMillis = [" + offsetMillis + "]");
        seekTo(mMediaPlayer.getTime() + offsetMillis);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    public void seekToDefault() {
        setRecovery(playQueue.getIndex(), 0);
    }

    private void registerView() {
        if (currentMetadata == null) return;
        final StreamInfo currentInfo = currentMetadata.getMetadata();
        final Disposable viewRegister = recordManager.onViewed(currentInfo).onErrorComplete()
                .subscribe(
                        ignored -> {/* successful */},
                        error -> Log.e(TAG, "Player onViewed() failure: ", error)
                );
        databaseUpdateReactor.add(viewRegister);
    }

    protected void reload() {
    }

    private void savePlaybackState(final StreamInfo info, final long progress) {
        if (info == null) return;
        if (DEBUG) Log.d(TAG, "savePlaybackState() called");
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getBoolean(context.getString(R.string.enable_watch_history_key), true)) {
            final Disposable stateSaver = recordManager.saveStreamState(info, progress)
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnError((e) -> {
                        if (DEBUG) e.printStackTrace();
                    })
                    .onErrorComplete()
                    .subscribe();
            databaseUpdateReactor.add(stateSaver);
        }
    }

    private void resetPlaybackState(final PlayQueueItem queueItem) {
        if (queueItem == null) return;
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getBoolean(context.getString(R.string.enable_watch_history_key), true)) {
            final Disposable stateSaver = queueItem.getStream()
                    .flatMapCompletable(info -> recordManager.saveStreamState(info, 0))
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnError((e) -> {
                        if (DEBUG) e.printStackTrace();
                    })
                    .onErrorComplete()
                    .subscribe();
            databaseUpdateReactor.add(stateSaver);
        }
    }

    public void resetPlaybackState(final StreamInfo info) {
        savePlaybackState(info, 0);
    }

    public void savePlaybackState() {
        if (mMediaPlayer == null || currentMetadata == null) return;
        final StreamInfo currentInfo = currentMetadata.getMetadata();

        if (mMediaPlayer != null & !mMediaPlayer.isReleased()) {
            savePlaybackState(currentInfo, mMediaPlayer.getTime());
        }
    }

    private void maybeUpdateCurrentMetadata() {
        if (mMediaPlayer == null) return;

        final MediaSourceTag metadata;
        try {
            StreamInfo stream = playQueue.getItem().getStream().blockingGet();
            List<VideoStream> sortedAvailableVideoStreams = ListHelper.getSortedStreamVideosList(context,
                    stream.getVideoStreams(), stream.getVideoOnlyStreams(), true);

            int videoIndex = ListHelper.getResolutionIndex(context, sortedAvailableVideoStreams, playbackQuality);

            metadata = new MediaSourceTag(stream, sortedAvailableVideoStreams, videoIndex);


        } catch (Exception error) {
            if (DEBUG) Log.d(TAG, "Could not update metadata: " + error.getMessage());
            if (DEBUG) error.printStackTrace();
            return;
        }

        if (metadata == null) return;

        maybeAutoQueueNextStream(metadata);

        if (currentMetadata == metadata) return;
        currentMetadata = metadata;
        onMetadataChanged(metadata);
    }

    private void maybeAutoQueueNextStream(@NonNull final MediaSourceTag currentMetadata) {
        if (playQueue == null || playQueue.getIndex() != playQueue.size() - 1 ||
                getRepeatMode() != BasePlayer.REPEAT_MODE_OFF ||
                !PlayerHelper.isAutoQueueEnabled(context)) return;
        // auto queue when starting playback on the last item when not repeating
        final PlayQueue autoQueue = PlayerHelper.autoQueueOf(currentMetadata.getMetadata(),
                playQueue.getStreams());
        if (autoQueue != null) playQueue.append(autoQueue.getStreams());
    }
    /*//////////////////////////////////////////////////////////////////////////
    // Getters and Setters
    //////////////////////////////////////////////////////////////////////////*/

    public MediaPlayer getPlayer() {
        return mMediaPlayer;
    }


    public int getCurrentState() {
        return currentState;
    }

    @Nullable
    public MediaSourceTag getCurrentMetadata() {
        return currentMetadata;
    }

    @NonNull
    public String getVideoUrl() {
        return currentMetadata == null ? context.getString(R.string.unknown_content) : currentMetadata.getMetadata().getUrl();
    }

    @NonNull
    public String getVideoTitle() {
        return currentMetadata == null ? context.getString(R.string.unknown_content) : currentMetadata.getMetadata().getName();
    }

    @NonNull
    public String getUploaderName() {
        return currentMetadata == null ? context.getString(R.string.unknown_content) : currentMetadata.getMetadata().getUploaderName();
    }

    @Nullable
    public Bitmap getThumbnail() {
        return currentThumbnail == null ?
                BitmapFactory.decodeResource(context.getResources(), R.drawable.dummy_thumbnail) :
                currentThumbnail;
    }

    /**
     * Checks if the current playback is a livestream AND is playing at or beyond the live edge
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isLiveEdge() {
        return true;
    }

    public boolean isLive() {
        if (mMediaPlayer == null) return false;

        if (playQueue.getItem().getStreamType().equals(StreamType.LIVE_STREAM) || playQueue.getItem().getStreamType().equals(StreamType.AUDIO_LIVE_STREAM))
            return true;

        return false;
    }

    public boolean isPlaying() {
        if (mMediaPlayer != null && !mMediaPlayer.isReleased()) {
            return mMediaPlayer.isPlaying();
        }

        return false;
    }


    public int getRepeatMode() {
        return mMediaPlayer == null
                ? BasePlayer.REPEAT_MODE_OFF
                : repeatMode;
    }

    public void setRepeatMode(final int repeatMode) {
        if (mMediaPlayer != null) {
            this.repeatMode = repeatMode;
        }
        setRecovery();
        initPlayer(true);
    }

    public float getPlaybackSpeed() {
        return  getPlaybackParameters().speed;
    }

    public float getPlaybackPitch() {
        return getPlaybackParameters().pitch;
    }

    public boolean getPlaybackSkipSilence() {
        return getPlaybackParameters().skipSilence;
    }

    public void setPlaybackSpeed(float speed) {
        setPlaybackParameters(speed, getPlaybackPitch(), getPlaybackSkipSilence());
    }

    public PlaybackParameters getPlaybackParameters() {
        if (mMediaPlayer == null) return PlaybackParameters.DEFAULT;

        return playBackParameters == null ? PlaybackParameters.DEFAULT : playBackParameters;
    }

    public void setPlaybackParameters(float speed, float pitch, boolean skipSilence) {
        playBackParameters = new PlaybackParameters(speed, pitch, skipSilence);
        Log.d(TAG, "setPlaybackParameters called");
        mMediaPlayer.setRate(speed);
    }

    public PlayQueue getPlayQueue() {
        return playQueue;
    }

    public PlayQueueAdapter getPlayQueueAdapter() {
        return playQueueAdapter;
    }

    public boolean isProgressLoopRunning() {
        return progressUpdateReactor.get() != null;
    }

    public void setRecovery() {
        if (playQueue == null || mMediaPlayer == null) return;

        final int queuePos = playQueue.getIndex();
        final long windowPos = mMediaPlayer.isReleased() ? 0 : mMediaPlayer.getTime();

        if (windowPos > 0 && windowPos <= mMediaPlayer.getLength()) {
            setRecovery(queuePos, windowPos);
        }
    }

    public void setRecovery(final int queuePos, final long windowPos) {
        if (playQueue.size() <= queuePos) return;

        if (DEBUG) Log.d(TAG, "Setting recovery, queue: " + queuePos + ", pos: " + windowPos);
        playQueue.setRecovery(queuePos, windowPos);
    }

    public boolean gotDestroyed() {
        return mMediaPlayer == null;
    }

    private boolean isPlaybackResumeEnabled() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean(context.getString(R.string.enable_watch_history_key), true)
                && prefs.getBoolean(context.getString(R.string.enable_playback_resume_key), true);
    }

    /**
     * Registering callbacks
     */
    protected org.videolan.libvlc.MediaPlayer.EventListener mPlayerListener = new MyPlayerListener(mMediaPlayer);

    private class MyPlayerListener implements MediaPlayer.EventListener {
        private WeakReference<MediaPlayer> mOwner;

        public MyPlayerListener(MediaPlayer owner) {
            mOwner = new WeakReference<MediaPlayer>(owner);
        }

        @Override
        public void onEvent(MediaPlayer.Event event) {
            MediaPlayer player = mOwner.get();

            switch (event.type) {
                case MediaPlayer.Event.Opening:
                    return;

                case MediaPlayer.Event.Playing:
                    changeState(BasePlayer.STATE_PLAYING);
                    return;

                case MediaPlayer.Event.EndReached:
                    resetPlaybackState(playQueue.getItem());

                    if (PlayerHelper.isAutoQueueEnabled(context)) {
                        if (playQueue.getIndex() + 1 < playQueue.size()) {
                            playQueue.offsetIndex(+1);
                            initPlayer(true);

                            return;
                        }
                    }

                    changeState(BasePlayer.STATE_COMPLETED);

                    return;

                case MediaPlayer.Event.Paused:
                    changeState(BasePlayer.STATE_PAUSED);
                    return;

                case MediaPlayer.Event.EncounteredError:
                    return;

                case MediaPlayer.Event.Buffering:
                    onUpdateProgress(
                            Math.max((int) mMediaPlayer.getTime(), 0),
                            (int) mMediaPlayer.getLength(),
                            (int) event.getBuffering()
                    );
                    return;

                default:
                    break;
            }
        }
    }
}
