/*
 * Copyright 2017 Mauricio Colli <mauriciocolli@outlook.com>
 * VideoPlayer.java is part of NewPipe
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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.os.Build;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.text.CaptionStyleCompat;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;

import org.schabi.newpipelegacy.R;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipelegacy.player.helper.PlayerHelper;
import org.schabi.newpipelegacy.player.resolver.MediaSourceTag;
import org.schabi.newpipelegacy.util.AnimationUtils;
import org.videolan.libvlc.IVLCVout;
import org.videolan.libvlc.IVLCVout.OnNewVideoLayoutListener;
import org.videolan.libvlc.Media;

import java.util.ArrayList;
import java.util.List;

import static org.schabi.newpipelegacy.player.helper.PlayerHelper.formatSpeed;
import static org.schabi.newpipelegacy.player.helper.PlayerHelper.getTimeString;
import static org.schabi.newpipelegacy.util.AnimationUtils.animateView;

/**
 * Base for <b>video</b> players
 *
 * @author mauriciocolli
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public abstract class VideoPlayer extends BasePlayer
        implements
        SeekBar.OnSeekBarChangeListener,
        View.OnClickListener,
        PopupMenu.OnMenuItemClickListener,
        PopupMenu.OnDismissListener, OnNewVideoLayoutListener {
    public static final boolean DEBUG = BasePlayer.DEBUG;
    public final String TAG;

    /*//////////////////////////////////////////////////////////////////////////
    // Player
    //////////////////////////////////////////////////////////////////////////*/

    protected static final int RENDERER_UNAVAILABLE = -1;
    public static final int DEFAULT_CONTROLS_DURATION = 300; // 300 millis
    public static final int DEFAULT_CONTROLS_HIDE_TIME = 2000;  // 2 Seconds

    private List<VideoStream> availableStreams;
    private int selectedStreamIndex;

    protected boolean wasPlaying = false;

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    //////////////////////////////////////////////////////////////////////////*/

    private View rootView;

    private AspectRatioFrameLayout aspectRatioFrameLayout;
    private SurfaceView surfaceView;
    private View surfaceForeground;

    private View loadingPanel;
    private ImageView endScreen;
    private ImageView controlAnimationView;

    private View controlsRoot;
    private TextView currentDisplaySeek;

    private View bottomControlsRoot;
    private SeekBar playbackSeekBar;
    private TextView playbackCurrentTime;
    private TextView playbackEndTime;
    private TextView playbackLiveSync;
    private TextView playbackSpeedTextView;

    private View topControlsRoot;
    private TextView qualityTextView;

    private SurfaceView subtitleView;

    private TextView resizeView;
    private TextView captionTextView;

    private ValueAnimator controlViewAnimator;
    private final Handler controlsVisibilityHandler = new Handler();

    boolean isSomePopupMenuVisible = false;
    private final int qualityPopupMenuGroupId = 69;
    private PopupMenu qualityPopupMenu;

    private final int playbackSpeedPopupMenuGroupId = 79;
    private PopupMenu playbackSpeedPopupMenu;

    private final int captionPopupMenuGroupId = 89;
    private PopupMenu captionPopupMenu;

    private final Handler mHandler = new Handler();
    private View.OnLayoutChangeListener mOnLayoutChangeListener = null;

    private static final int SURFACE_BEST_FIT = 0;
    private static final int SURFACE_FIT_SCREEN = 1;
    private static final int SURFACE_FILL = 2;
    private static final int SURFACE_16_9 = 3;
    private static final int SURFACE_4_3 = 4;
    private static final int SURFACE_ORIGINAL = 5;
    private static int CURRENT_SIZE = SURFACE_FILL;

    private int mVideoHeight = 0;
    private int mVideoWidth = 0;
    private int mVideoVisibleHeight = 0;
    private int mVideoVisibleWidth = 0;
    private int mVideoSarNum = 0;
    private int mVideoSarDen = 0;
    String customViewMode = "Fit";

    private int repeatMode;
    private boolean isMuted;

    ///////////////////////////////////////////////////////////////////////////

    public VideoPlayer(String debugTag, Context context) {
        super(context);
        this.TAG = debugTag;
    }

    public void setup(View rootView) {
        initViews(rootView);
        setup();
    }

    public void initViews(View rootView) {
        this.rootView = rootView;
        this.aspectRatioFrameLayout = rootView.findViewById(R.id.aspectRatioLayout);

        this.surfaceView = rootView.findViewById(R.id.surfaceView);

        this.surfaceForeground = rootView.findViewById(R.id.surfaceForeground);
        this.loadingPanel = rootView.findViewById(R.id.loading_panel);
        this.endScreen = rootView.findViewById(R.id.endScreen);
        this.controlAnimationView = rootView.findViewById(R.id.controlAnimationView);
        this.controlsRoot = rootView.findViewById(R.id.playbackControlRoot);
        this.currentDisplaySeek = rootView.findViewById(R.id.currentDisplaySeek);
        this.playbackSeekBar = rootView.findViewById(R.id.playbackSeekBar);
        this.playbackCurrentTime = rootView.findViewById(R.id.playbackCurrentTime);
        this.playbackEndTime = rootView.findViewById(R.id.playbackEndTime);
        this.playbackLiveSync = rootView.findViewById(R.id.playbackLiveSync);
        this.playbackSpeedTextView = rootView.findViewById(R.id.playbackSpeed);
        this.bottomControlsRoot = rootView.findViewById(R.id.bottomControls);
        this.topControlsRoot = rootView.findViewById(R.id.topControls);
        this.qualityTextView = rootView.findViewById(R.id.qualityTextView);

        this.subtitleView = rootView.findViewById(R.id.subtitleView);
        this.subtitleView.setZOrderMediaOverlay(true);
        this.subtitleView.getHolder().setFormat(PixelFormat.TRANSLUCENT);

        final float captionScale = PlayerHelper.getCaptionScale(context);
        final CaptionStyleCompat captionStyle = PlayerHelper.getCaptionStyle(context);
        setupSubtitleView(subtitleView, captionScale, captionStyle);

        this.resizeView =  rootView.findViewById(R.id.resizeTextView);
        resizeView.setText(PlayerHelper.resizeTypeOf(context, aspectRatioFrameLayout.getResizeMode()));

        this.captionTextView = rootView.findViewById(R.id.captionTextView);

        this.aspectRatioFrameLayout.setAspectRatio(16.0f / 9.0f);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
            playbackSeekBar.getThumb().setColorFilter(Color.RED, PorterDuff.Mode.SRC_IN);
        this.playbackSeekBar.getProgressDrawable().setColorFilter(Color.RED, PorterDuff.Mode.MULTIPLY);

        this.qualityPopupMenu = new PopupMenu(context, qualityTextView);
        this.playbackSpeedPopupMenu = new PopupMenu(context, playbackSpeedTextView);
        this.captionPopupMenu = new PopupMenu(context, captionTextView);

        ((ProgressBar) this.loadingPanel.findViewById(R.id.progressBarLoadingPanel))
                .getIndeterminateDrawable().setColorFilter(Color.WHITE, PorterDuff.Mode.MULTIPLY);

        rootView.findViewById(R.id.aspectRatioLayout).post(new Runnable() {
            public void run() {
                qualityPopupMenu.show();
            }
        });
    }

    protected abstract void setupSubtitleView(@NonNull SurfaceView view,
                                              final float captionScale,
                                              @NonNull final CaptionStyleCompat captionStyle);

    @Override
    public void initListeners() {
        playbackSeekBar.setOnSeekBarChangeListener(this);
        playbackSpeedTextView.setOnClickListener(this);
        qualityTextView.setOnClickListener(this);
        captionTextView.setOnClickListener(this);
        resizeView.setOnClickListener(this);
        playbackLiveSync.setOnClickListener(this);
    }

    @Override
    public void initPlayer(final boolean playOnReady) {
        super.initPlayer(playOnReady);

        mMediaPlayer.getVLCVout().setVideoView(surfaceView);
        mMediaPlayer.getVLCVout().setSubtitlesView(subtitleView);
        mMediaPlayer.getVLCVout().attachViews(this);

        onTextTrackUpdate();

        updateVideoSurfaces();

        if (mOnLayoutChangeListener == null) {
            mOnLayoutChangeListener = new View.OnLayoutChangeListener() {
                private final Runnable mRunnable = new Runnable() {
                    @Override
                    public void run() {
                        updateVideoSurfaces();
                    }
                };

                @Override
                public void onLayoutChange(View v, int left, int top, int right,
                                           int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                        mHandler.removeCallbacks(mRunnable);
                        mHandler.post(mRunnable);
                    }
                }
            };
        }
        rootView.addOnLayoutChangeListener(mOnLayoutChangeListener);

        registerBroadcastReceiver();
    }

    @Override
    public void handleIntent(final Intent intent) {
        if (DEBUG) Log.d(TAG, "handleIntent() called with: intent = [" + intent + "]");

        if (intent == null) {
            return;
        }

        if (intent.hasExtra(PLAYBACK_QUALITY)) {
            setPlaybackQuality(intent.getStringExtra(PLAYBACK_QUALITY));
        }

        super.handleIntent(intent);

    }

    @Override
    public void setPlaybackParameters(float speed, float pitch, boolean skipSilence) {
        super.setPlaybackParameters(speed, pitch, skipSilence);

        playbackSpeedTextView.setText(formatSpeed(getPlaybackSpeed()));
    }

    /*//////////////////////////////////////////////////////////////////////////
    // VLC view output
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onNewVideoLayout(IVLCVout vlcVout, int width, int height, int visibleWidth, int visibleHeight, int sarNum, int sarDen) {
        mVideoWidth = width;
        mVideoHeight = height;
        mVideoVisibleWidth = visibleWidth;
        mVideoVisibleHeight = visibleHeight;
        mVideoSarNum = sarNum;
        mVideoSarDen = sarDen;
        updateVideoSurfaces();
    }

    private void changeMediaPlayerLayout(int displayW, int displayH) {
        /* Change the video placement using the MediaPlayer API */
        switch (CURRENT_SIZE) {
            case SURFACE_BEST_FIT:
                mMediaPlayer.setAspectRatio(null);
                mMediaPlayer.setScale(0);
                break;
            case SURFACE_FIT_SCREEN:
            case SURFACE_FILL: {
                Media.VideoTrack vtrack = mMediaPlayer.getCurrentVideoTrack();
                if (vtrack == null)
                    return;
                final boolean videoSwapped = vtrack.orientation == Media.VideoTrack.Orientation.LeftBottom
                        || vtrack.orientation == Media.VideoTrack.Orientation.RightTop;
                if (CURRENT_SIZE == SURFACE_FIT_SCREEN) {
                    int videoW = vtrack.width;
                    int videoH = vtrack.height;

                    if (videoSwapped) {
                        int swap = videoW;
                        videoW = videoH;
                        videoH = swap;
                    }
                    if (vtrack.sarNum != vtrack.sarDen)
                        videoW = videoW * vtrack.sarNum / vtrack.sarDen;

                    float ar = videoW / (float) videoH;
                    float dar = displayW / (float) displayH;

                    float scale;
                    if (dar >= ar)
                        scale = displayW / (float) videoW; /* horizontal */
                    else
                        scale = displayH / (float) videoH; /* vertical */
                    mMediaPlayer.setScale(scale);
                    mMediaPlayer.setAspectRatio(null);
                } else {
                    mMediaPlayer.setScale(0);
                    mMediaPlayer.setAspectRatio(!videoSwapped ? "" + displayW + ":" + displayH
                            : "" + displayH + ":" + displayW);
                }
                break;
            }
            case SURFACE_16_9:
                mMediaPlayer.setAspectRatio("16:9");
                mMediaPlayer.setScale(0);
                break;
            case SURFACE_4_3:
                mMediaPlayer.setAspectRatio("4:3");
                mMediaPlayer.setScale(0);
                break;
            case SURFACE_ORIGINAL:
                mMediaPlayer.setAspectRatio(null);
                mMediaPlayer.setScale(1);
                break;
        }
    }

    public void updateVideoSurfaces() {

        int sw = aspectRatioFrameLayout.getWidth();
        int sh = aspectRatioFrameLayout.getHeight();

        // sanity check
        if (sw * sh == 0) {
            Log.e(TAG, "Invalid surface size");
            return;
        }

        mMediaPlayer.getVLCVout().setWindowSize(sw, sh);

        ViewGroup.LayoutParams lp = surfaceView.getLayoutParams();
        if (mVideoWidth * mVideoHeight == 0) {
            /* Case of OpenGL vouts: handles the placement of the video using MediaPlayer API */
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            surfaceView.setLayoutParams(lp);
            lp = aspectRatioFrameLayout.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            aspectRatioFrameLayout.setLayoutParams(lp);
            changeMediaPlayerLayout(sw, sh);
            return;
        }

        if (lp.width == lp.height && lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
            /* We handle the placement of the video using Android View LayoutParams */
            mMediaPlayer.setAspectRatio(null);
            mMediaPlayer.setScale(0);
        }

        double dw = sw, dh = sh;
        // Fix no need to move to portrait mode (should be add check for tablet)
        /*final boolean isPortrait = context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        Log.d("isPortrait", "isPortrait: " + isPortrait);

        if (sw > sh && isPortrait || sw < sh && !isPortrait) {
            dw = sh;
            dh = sw;
        }*/

        // compute the aspect ratio
        double ar, vw;
        if (mVideoSarDen == mVideoSarNum) {
            /* No indication about the density, assuming 1:1 */
            vw = mVideoVisibleWidth;
            ar = (double) mVideoVisibleWidth / (double) mVideoVisibleHeight;
        } else {
            /* Use the specified aspect ratio */
            vw = mVideoVisibleWidth * (double) mVideoSarNum / mVideoSarDen;
            ar = vw / mVideoVisibleHeight;
        }

        // compute the display aspect ratio
        double dar = dw / dh;

        switch (CURRENT_SIZE) {
            case SURFACE_BEST_FIT:
                if (dar < ar)
                    dh = dw / ar;
                else
                    dw = dh * ar;
                break;
            case SURFACE_FIT_SCREEN:
                if (dar >= ar)
                    dh = dw / ar; /* horizontal */
                else
                    dw = dh * ar; /* vertical */
                break;
            case SURFACE_FILL:
                break;
            case SURFACE_16_9:
                ar = 16.0 / 9.0;
                if (dar < ar)
                    dh = dw / ar;
                else
                    dw = dh * ar;
                break;
            case SURFACE_4_3:
                ar = 4.0 / 3.0;
                if (dar < ar)
                    dh = dw / ar;
                else
                    dw = dh * ar;
                break;
            case SURFACE_ORIGINAL:
                dh = mVideoVisibleHeight;
                dw = vw;
                break;
        }

        // set display size
        lp.width = (int) Math.ceil(dw * mVideoWidth / mVideoVisibleWidth);
        lp.height = (int) Math.ceil(dh * mVideoHeight / mVideoVisibleHeight);
        surfaceView.setLayoutParams(lp);
        if (subtitleView != null)
            surfaceView.setLayoutParams(lp);

        // set frame size (crop if necessary)
        lp = aspectRatioFrameLayout.getLayoutParams();
        lp.width = (int) Math.floor(dw);
        lp.height = (int) Math.floor(dh);
        aspectRatioFrameLayout.setLayoutParams(lp);

        surfaceView.invalidate();
        if (subtitleView != null)
            subtitleView.invalidate();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // UI Builders
    //////////////////////////////////////////////////////////////////////////*/

    public void buildQualityMenu() {
        if (qualityPopupMenu == null) return;

        qualityPopupMenu.getMenu().removeGroup(qualityPopupMenuGroupId);

        Log.d(TAG, "availableStreams: " + availableStreams.size());
        for (int i = 0; i < availableStreams.size(); i++) {
            VideoStream videoStream = availableStreams.get(i);
            qualityPopupMenu.getMenu().add(qualityPopupMenuGroupId, i, Menu.NONE,
                    MediaFormat.getNameById(videoStream.getFormatId()) + " " + videoStream.resolution);
        }

        if (getSelectedVideoStream() != null) {
            qualityTextView.setText(getSelectedVideoStream().resolution);
            setPlaybackQuality(getSelectedVideoStream().resolution);
        }

        qualityPopupMenu.setOnMenuItemClickListener(this);
        qualityPopupMenu.setOnDismissListener(this);
    }

    private void buildPlaybackSpeedMenu() {
        if (playbackSpeedPopupMenu == null) return;

        playbackSpeedPopupMenu.getMenu().removeGroup(playbackSpeedPopupMenuGroupId);
        for (int i = 0; i < PLAYBACK_SPEEDS.length; i++) {
            playbackSpeedPopupMenu.getMenu().add(playbackSpeedPopupMenuGroupId, i, Menu.NONE, formatSpeed(PLAYBACK_SPEEDS[i]));
        }

        playbackSpeedTextView.setText(formatSpeed(getPlaybackSpeed()));
        playbackSpeedPopupMenu.setOnMenuItemClickListener(this);
        playbackSpeedPopupMenu.setOnDismissListener(this);
    }

    private void buildCaptionMenu(final List<String> availableLanguages) {
        if (captionPopupMenu == null) return;
        captionPopupMenu.getMenu().removeGroup(captionPopupMenuGroupId);

        String userPreferredLanguage = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(R.string.caption_user_set_key), null);
        /*
         * only search for autogenerated cc as fallback
         * if "(auto-generated)" was not already selected
         * we are only looking for "(" instead of "(auto-generated)" to hopefully get all
         * internationalized variants such as "(automatisch-erzeugt)" and so on
         */
        boolean searchForAutogenerated = userPreferredLanguage != null &&
                !userPreferredLanguage.contains("(");

        // Add option for turning off caption
        MenuItem captionOffItem = captionPopupMenu.getMenu().add(captionPopupMenuGroupId,
                0, Menu.NONE, R.string.caption_none);
        captionOffItem.setOnMenuItemClickListener(menuItem -> {
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            prefs.edit().remove(context.getString(R.string.caption_user_set_key)).commit();
            initPlayer(true);
            captionTextView.setText("No Captions");
            return true;
        });

        // Add all available captions
        for (int i = 0; i < availableLanguages.size(); i++) {
            final String captionLanguage = availableLanguages.get(i);
            MenuItem captionItem = captionPopupMenu.getMenu().add(captionPopupMenuGroupId,
                    i + 1, Menu.NONE, captionLanguage);
            captionItem.setOnMenuItemClickListener(menuItem -> {
                final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                prefs.edit().putString(context.getString(R.string.caption_user_set_key),
                        captionLanguage).commit();
                initPlayer(true);
                captionTextView.setText(captionLanguage);

                return true;
            });
        }
        captionPopupMenu.setOnDismissListener(this);
    }

    private void updateStreamRelatedViews() {
        if (getCurrentMetadata() == null) return;

        final MediaSourceTag tag = getCurrentMetadata();
        final StreamInfo metadata = tag.getMetadata();

        qualityTextView.setVisibility(View.GONE);
        playbackSpeedTextView.setVisibility(View.GONE);

        playbackEndTime.setVisibility(View.GONE);
        playbackLiveSync.setVisibility(View.GONE);

        switch (metadata.getStreamType()) {
            case AUDIO_STREAM:
                surfaceView.setVisibility(View.GONE);
                endScreen.setVisibility(View.VISIBLE);
                playbackEndTime.setVisibility(View.VISIBLE);
                break;

            case AUDIO_LIVE_STREAM:
                surfaceView.setVisibility(View.GONE);
                endScreen.setVisibility(View.VISIBLE);
                playbackLiveSync.setVisibility(View.VISIBLE);
                break;

            case LIVE_STREAM:
                surfaceView.setVisibility(View.VISIBLE);
                endScreen.setVisibility(View.GONE);
                playbackLiveSync.setVisibility(View.VISIBLE);
                break;

            case VIDEO_STREAM:
                if (metadata.getVideoStreams().size() + metadata.getVideoOnlyStreams().size() == 0)
                    break;

                availableStreams = tag.getSortedAvailableVideoStreams();
                selectedStreamIndex = tag.getSelectedVideoStreamIndex();
                buildQualityMenu();

                qualityTextView.setVisibility(View.VISIBLE);
                surfaceView.setVisibility(View.VISIBLE);
            default:
                endScreen.setVisibility(View.GONE);
                playbackEndTime.setVisibility(View.VISIBLE);
                break;
        }

        buildPlaybackSpeedMenu();
        playbackSpeedTextView.setVisibility(View.VISIBLE);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // States Implementation
    //////////////////////////////////////////////////////////////////////////*/


    @Override
    public void onPlaying() {
        super.onPlaying();

        updateStreamRelatedViews();

        showAndAnimateControl(-1, true);

        playbackSeekBar.setEnabled(true);
        // Bug on lower api, disabling and enabling the seekBar resets the thumb color -.-, so sets the color again
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
            playbackSeekBar.getThumb().setColorFilter(Color.RED, PorterDuff.Mode.SRC_IN);

        loadingPanel.setVisibility(View.GONE);

        animateView(currentDisplaySeek, AnimationUtils.Type.SCALE_AND_ALPHA, false, 200);
    }

    @Override
    public void onBuffering() {
        if (DEBUG) Log.d(TAG, "onBuffering() called");
        loadingPanel.setBackgroundColor(Color.TRANSPARENT);
    }

    @Override
    public void onPaused() {
        if (DEBUG) Log.d(TAG, "onPaused() called");
        showControls(400);
        loadingPanel.setVisibility(View.GONE);
    }

    @Override
    public void onPausedSeek() {
        if (DEBUG) Log.d(TAG, "onPausedSeek() called");
        showAndAnimateControl(-1, true);
    }

    @Override
    public void onCompleted() {
        super.onCompleted();

        showControls(500);
        animateView(endScreen, true, 800);
        animateView(currentDisplaySeek, AnimationUtils.Type.SCALE_AND_ALPHA, false, 200);
        loadingPanel.setVisibility(View.GONE);

        animateView(surfaceForeground, true, 100);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // ExoPlayer Video Listener
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
        super.onPlaybackParametersChanged(playbackParameters);
        playbackSpeedTextView.setText(formatSpeed(getPlaybackSpeed()));
        Log.d(TAG, "onPlaybackParametersChanged called");
    }

    /*//////////////////////////////////////////////////////////////////////////
    // ExoPlayer Track Updates
    //////////////////////////////////////////////////////////////////////////*/

    private void onTextTrackUpdate() {
        // hook here to build caption menu
        if (playQueue == null) {
            return;
        }

        List<SubtitlesStream> subtitlesStream = playQueue.getItem().getStream().blockingGet().getSubtitles();
        List<String> availableLanguages = new ArrayList<>(subtitlesStream.size());
        for (int i =0; i < subtitlesStream.size(); i++) {
            availableLanguages.add(subtitlesStream.get(i).getDisplayLanguageName());
        }

        buildCaptionMenu(availableLanguages);

        ;

        String userPreferredLanguage = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(R.string.caption_user_set_key), null);

        if (userPreferredLanguage != null) {
            if ((!availableLanguages.contains(userPreferredLanguage)
                    && !containsCaseInsensitive(availableLanguages, userPreferredLanguage))) {
                captionTextView.setText(R.string.caption_none);
            } else {
                captionTextView.setText(userPreferredLanguage);
            }
        } else {
            captionTextView.setText(R.string.caption_none);
        }
        captionTextView.setVisibility(availableLanguages.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // workaround to match normalized captions like english to English or deutsch to Deutsch
    private static boolean containsCaseInsensitive(List<String> list, String toFind) {
        for(String i : list){
            if(i.equalsIgnoreCase(toFind))
                return true;
        }
        return false;
    }

    /*//////////////////////////////////////////////////////////////////////////
    // General Player
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void destroy() {
        super.destroy();
        if (endScreen != null) endScreen.setImageBitmap(null);
    }

    @Override
    public void onUpdateProgress(int currentProgress, int duration, int bufferPercent) {

        if (duration != playbackSeekBar.getMax()) {
            playbackEndTime.setText(getTimeString(duration));
            playbackSeekBar.setMax(duration);
        }
        if (currentState != STATE_PAUSED) {
            if (currentState != STATE_PAUSED_SEEK) playbackSeekBar.setProgress(currentProgress);
            playbackCurrentTime.setText(getTimeString(currentProgress));
        }
        if (bufferPercent > 90) {
            playbackSeekBar.setSecondaryProgress((int) (playbackSeekBar.getMax() * ((float) bufferPercent / 100)));
        }
        if (DEBUG && bufferPercent % 20 == 0) { //Limit log
            Log.d(TAG, "updateProgress() called with: isVisible = " + isControlsVisible() + ", currentProgress = [" + currentProgress + "], duration = [" + duration + "], bufferPercent = [" + bufferPercent + "]");
        }
        playbackLiveSync.setClickable(!isLiveEdge());
    }

    @Override
    public void onLoadingComplete(String imageUri, View view, Bitmap loadedImage) {
        super.onLoadingComplete(imageUri, view, loadedImage);
        if (loadedImage != null) endScreen.setImageBitmap(loadedImage);
    }

    protected void onFullScreenButtonClicked() {
        changeState(STATE_BLOCKED);
    }

    @Override
    public void onFastRewind() {
        super.onFastRewind();
        showAndAnimateControl(R.drawable.ic_action_av_fast_rewind, true);
    }

    @Override
    public void onFastForward() {
        super.onFastForward();
        showAndAnimateControl(R.drawable.ic_action_av_fast_forward, true);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // OnClick related
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onClick(View v) {
        if (DEBUG) Log.d(TAG, "onClick() called with: v = [" + v + "]");
        if (v.getId() == qualityTextView.getId()) {
            onQualitySelectorClicked();
        } else if (v.getId() == playbackSpeedTextView.getId()) {
            onPlaybackSpeedClicked();
        } else if (v.getId() == resizeView.getId()) {
            onResizeClicked();
        } else if (v.getId() == captionTextView.getId()) {
            onCaptionClicked();
        } else if (v.getId() == playbackLiveSync.getId()) {
            //seekToDefault();
        }
    }

    /**
     * Called when an item of the quality selector or the playback speed selector is selected
     */
    @Override
    public boolean onMenuItemClick(MenuItem menuItem) {
        if (DEBUG)
            Log.d(TAG, "onMenuItemClick() called with: menuItem = [" + menuItem + "], menuItem.getItemId = [" + menuItem.getItemId() + "]");

        if (qualityPopupMenuGroupId == menuItem.getGroupId()) {
            final int menuItemIndex = menuItem.getItemId();
            if (selectedStreamIndex == menuItemIndex ||
                    availableStreams == null || availableStreams.size() <= menuItemIndex) return true;

            final String newResolution = availableStreams.get(menuItemIndex).resolution;
            setPlaybackQuality(newResolution);

            setRecovery();

            initPlayer(true);

            qualityTextView.setText(getPlaybackQuality());

            return true;
        } else if (playbackSpeedPopupMenuGroupId == menuItem.getGroupId()) {
            int speedIndex = menuItem.getItemId();

            float speed = PLAYBACK_SPEEDS[speedIndex];

            mMediaPlayer.setRate(speed);
            setPlaybackSpeed(speed);
            playbackSpeedTextView.setText(formatSpeed(getPlaybackSpeed()));
        }

        return false;
    }

    /**
     * Called when some popup menu is dismissed
     */
    @Override
    public void onDismiss(PopupMenu menu) {
        if (DEBUG) Log.d(TAG, "onDismiss() called with: menu = [" + menu + "]");
        isSomePopupMenuVisible = false;

        if (getSelectedVideoStream() != null) {
            qualityTextView.setText(getPlaybackQuality());
        }
    }

    public void onQualitySelectorClicked() {
        if (DEBUG) Log.d(TAG, "onQualitySelectorClicked() called");
        qualityPopupMenu.show();
        isSomePopupMenuVisible = true;
        showControls(DEFAULT_CONTROLS_DURATION);

        final VideoStream videoStream = getSelectedVideoStream();
        if (videoStream != null) {
            final String qualityText = MediaFormat.getNameById(videoStream.getFormatId()) + " "
                    + videoStream.resolution;
            qualityTextView.setText(qualityText);

        }
    }

    public void onPlaybackSpeedClicked() {
        if (DEBUG) Log.d(TAG, "onPlaybackSpeedClicked() called");
        playbackSpeedPopupMenu.show();
        isSomePopupMenuVisible = true;
        showControls(DEFAULT_CONTROLS_DURATION);
    }

    private void onCaptionClicked() {
        if (DEBUG) Log.d(TAG, "onCaptionClicked() called");
        captionPopupMenu.show();
        isSomePopupMenuVisible = true;
        showControls(DEFAULT_CONTROLS_DURATION);
    }

    private void onResizeClicked() {
        /*if (CURRENT_SIZE != 0) {
            CURRENT_SIZE = 0;
            customViewMode = "Fit";
        } else {
            CURRENT_SIZE = 2;
            customViewMode = "Fill";
        }

        resizeView.setText(customViewMode);

        //initPlayback(getPlaybackQuality(), playbackSeekBar.getProgress());
        updateVideoSurfaces();

        Log.d(TAG, "onResizeClicked called");*/

    }

    protected void setResizeMode(@AspectRatioFrameLayout.ResizeMode final int resizeMode) {

    }

    protected abstract int nextResizeMode(@AspectRatioFrameLayout.ResizeMode final int resizeMode);

    /*//////////////////////////////////////////////////////////////////////////
    // SeekBar Listener
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (DEBUG && fromUser) Log.d(TAG, "onProgressChanged() called with: seekBar = [" + seekBar + "], progress = [" + progress + "]");
        //if (fromUser) playbackCurrentTime.setText(getTimeString(progress));
        //if (fromUser) currentDisplaySeek.setText(getTimeString(progress));
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
       /* if (DEBUG) Log.d(TAG, "onStartTrackingTouch() called with: seekBar = [" + seekBar + "]");
        if (getCurrentState() != STATE_PAUSED_SEEK) changeState(STATE_PAUSED_SEEK);

        // TODO rewrite the checking code here
        wasPlaying = simpleExoPlayer.getPlayWhenReady();
        if (isPlaying()) simpleExoPlayer.setPlayWhenReady(false);

        showControls(0);
        animateView(currentDisplaySeek, AnimationUtils.Type.SCALE_AND_ALPHA, true,
                DEFAULT_CONTROLS_DURATION);*/
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        if (DEBUG) Log.d(TAG, "onStopTrackingTouch() called with: seekBar = [" + seekBar + "]");

        // TODO rewrite the play code here
        /*seekTo(seekBar.getProgress());
        if (wasPlaying || simpleExoPlayer.getDuration() == seekBar.getProgress()) simpleExoPlayer.setPlayWhenReady(true);*/

        playbackCurrentTime.setText(getTimeString(seekBar.getProgress()));
        animateView(currentDisplaySeek, AnimationUtils.Type.SCALE_AND_ALPHA, false, 200);

        if (mMediaPlayer != null)
            mMediaPlayer.setTime(seekBar.getProgress());

        if (getCurrentState() == STATE_COMPLETED) {
           initPlayer(true);
        }
        if (!isProgressLoopRunning()) startProgressLoop();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    public boolean isControlsVisible() {
        return controlsRoot != null && controlsRoot.getVisibility() == View.VISIBLE;
    }

    /**
     * Show a animation, and depending on goneOnEnd, will stay on the screen or be gone
     *
     * @param drawableId the drawable that will be used to animate, pass -1 to clear any animation that is visible
     * @param goneOnEnd  will set the animation view to GONE on the end of the animation
     */
    public void showAndAnimateControl(final int drawableId, final boolean goneOnEnd) {
        if (DEBUG) Log.d(TAG, "showAndAnimateControl() called with: drawableId = [" + drawableId + "], goneOnEnd = [" + goneOnEnd + "]");
        if (controlViewAnimator != null && controlViewAnimator.isRunning()) {
            if (DEBUG) Log.d(TAG, "showAndAnimateControl: controlViewAnimator.isRunning");
            controlViewAnimator.end();
        }

        if (drawableId == -1) {
            if (controlAnimationView.getVisibility() == View.VISIBLE) {
                controlViewAnimator = ObjectAnimator.ofPropertyValuesHolder(controlAnimationView,
                        PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f),
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1.4f, 1f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.4f, 1f)
                ).setDuration(DEFAULT_CONTROLS_DURATION);
                controlViewAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        controlAnimationView.setVisibility(View.GONE);
                    }
                });
                controlViewAnimator.start();
            }
            return;
        }

        float scaleFrom = goneOnEnd ? 1f : 1f, scaleTo = goneOnEnd ? 1.8f : 1.4f;
        float alphaFrom = goneOnEnd ? 1f : 0f, alphaTo = goneOnEnd ? 0f : 1f;


        controlViewAnimator = ObjectAnimator.ofPropertyValuesHolder(controlAnimationView,
                PropertyValuesHolder.ofFloat(View.ALPHA, alphaFrom, alphaTo),
                PropertyValuesHolder.ofFloat(View.SCALE_X, scaleFrom, scaleTo),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, scaleFrom, scaleTo)
        );
        controlViewAnimator.setDuration(goneOnEnd ? 1000 : 500);
        controlViewAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (goneOnEnd) controlAnimationView.setVisibility(View.GONE);
                else controlAnimationView.setVisibility(View.VISIBLE);
            }
        });


        controlAnimationView.setVisibility(View.VISIBLE);
        controlAnimationView.setImageDrawable(ContextCompat.getDrawable(context, drawableId));
        controlViewAnimator.start();
    }

    public boolean isSomePopupMenuVisible() {
        return isSomePopupMenuVisible;
    }

    public void showControlsThenHide() {
        if (DEBUG) Log.d(TAG, "showControlsThenHide() called");
        animateView(controlsRoot, true, DEFAULT_CONTROLS_DURATION, 0,
                () -> hideControls(DEFAULT_CONTROLS_DURATION, DEFAULT_CONTROLS_HIDE_TIME));
    }

    public void showControls(long duration) {
        if (DEBUG) Log.d(TAG, "showControls() called");
        controlsVisibilityHandler.removeCallbacksAndMessages(null);
        animateView(controlsRoot, true, duration);
    }

    public void hideControls(final long duration, long delay) {
        if (DEBUG) Log.d(TAG, "hideControls() called with: delay = [" + delay + "]");
        controlsVisibilityHandler.removeCallbacksAndMessages(null);
        controlsVisibilityHandler.postDelayed(
                () -> animateView(controlsRoot, false, duration), delay);
    }

    public void hideControlsAndButton(final long duration, long delay, View button) {
        if (DEBUG) Log.d(TAG, "hideControls() called with: delay = [" + delay + "]");
        controlsVisibilityHandler.removeCallbacksAndMessages(null);
        controlsVisibilityHandler.postDelayed(hideControlsAndButtonHandler(duration, button), delay);
    }

    private Runnable hideControlsAndButtonHandler(long duration, View videoPlayPause)
    {
        return () -> {
            videoPlayPause.setVisibility(View.INVISIBLE);
            animateView(controlsRoot, false,duration);
        };
    }
    /*//////////////////////////////////////////////////////////////////////////
    // Getters and Setters
    //////////////////////////////////////////////////////////////////////////*/

    @Nullable
    public String getPlaybackQuality() {
        return playbackQuality;
    }

    public void setPlaybackQuality(final String quality) {
        playbackQuality = quality;
    }

    public AspectRatioFrameLayout getAspectRatioFrameLayout() {
        return aspectRatioFrameLayout;
    }

    public SurfaceView getSurfaceView() {
        return surfaceView;
    }

    public boolean wasPlaying() {
        return wasPlaying;
    }

    @Nullable
    public VideoStream getSelectedVideoStream() {
        return (selectedStreamIndex >= 0 && availableStreams != null &&
                availableStreams.size() > selectedStreamIndex) ?
                availableStreams.get(selectedStreamIndex) : null;
    }

    public Handler getControlsVisibilityHandler() {
        return controlsVisibilityHandler;
    }

    public View getRootView() {
        return rootView;
    }

    public void setRootView(View rootView) {
        this.rootView = rootView;
    }

    public View getLoadingPanel() {
        return loadingPanel;
    }

    public ImageView getEndScreen() {
        return endScreen;
    }

    public ImageView getControlAnimationView() {
        return controlAnimationView;
    }

    public View getControlsRoot() {
        return controlsRoot;
    }

    public View getBottomControlsRoot() {
        return bottomControlsRoot;
    }

    public SeekBar getPlaybackSeekBar() {
        return playbackSeekBar;
    }

    public TextView getPlaybackCurrentTime() {
        return playbackCurrentTime;
    }

    public TextView getPlaybackEndTime() {
        return playbackEndTime;
    }

    public View getTopControlsRoot() {
        return topControlsRoot;
    }

    public TextView getQualityTextView() {
        return qualityTextView;
    }

    public PopupMenu getQualityPopupMenu() {
        return qualityPopupMenu;
    }

    public PopupMenu getPlaybackSpeedPopupMenu() {
        return playbackSpeedPopupMenu;
    }

    public View getSurfaceForeground() {
        return surfaceForeground;
    }

    public TextView getCurrentDisplaySeek() {
        return currentDisplaySeek;
    }

    public SurfaceView getSubtitleView() {
        return subtitleView;
    }

    public TextView getResizeView() {
        return resizeView;
    }

    public TextView getCaptionTextView() {
        return captionTextView;
    }
}
