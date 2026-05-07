package org.jellyfin.mobile.player.ui

import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
import android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.view.postDelayed
import androidx.appcompat.widget.AppCompatTextView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.databinding.FragmentPlayerBinding
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.brightness
import org.jellyfin.mobile.utils.dip
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.math.abs

class PlayerGestureHelper(
    private val fragment: PlayerFragment,
    private val playerBinding: FragmentPlayerBinding,
    private val playerLockScreenHelper: PlayerLockScreenHelper,
) : KoinComponent {
    private val appPreferences: AppPreferences by inject()
    private val audioManager: AudioManager by lazy { fragment.requireActivity().getSystemService()!! }
    private val playerView: PlayerView by playerBinding::playerView
    private val gestureIndicatorOverlayLayout: LinearLayout by playerBinding::gestureOverlayLayout
    private val gestureIndicatorOverlayImage: ImageView by playerBinding::gestureOverlayImage
    private val gestureIndicatorOverlayProgress: ProgressBar by playerBinding::gestureOverlayProgress
    private val gestureIndicatorOverlayText: AppCompatTextView by playerBinding::gestureOverlayText
    private val gestureIndicatorOverlayTime: AppCompatTextView by playerBinding::gestureOverlayTime
    private var isOnPressingSpeedUp = false
    private var speedTierIndex = 4
    private var lastSpeedTierIndex = 4
    private var speedModeDistanceX = 0f
    private var speedModePreviousX = 0f
    private var defaultOverlayGravity = 0
    private var defaultOverlayPadding = 0
    private var defaultImageSize = 0

    init {
        if (appPreferences.exoPlayerRememberBrightness) {
            fragment.requireActivity().window.brightness = appPreferences.exoPlayerBrightness
        }
        defaultOverlayGravity = (gestureIndicatorOverlayLayout.layoutParams as? FrameLayout.LayoutParams)?.gravity ?: Gravity.CENTER
        defaultOverlayPadding = gestureIndicatorOverlayLayout.paddingLeft
        defaultImageSize = gestureIndicatorOverlayImage.layoutParams.width
    }

    /**
     * Tracks whether video content should fill the screen, cutting off unwanted content on the sides.
     * Useful on wide-screen phones to remove black bars from some movies.
     */
    private var isZoomEnabled = false

    /**
     * Tracks a value during a swipe gesture (between multiple onScroll calls).
     * When the gesture starts it's reset to an initial value and gets increased or decreased
     * (depending on the direction) as the gesture progresses.
     */
    private var swipeGestureValueTracker = -1f
    private var seekGestureValueTracker = 0L
    private var seekInitialPosition = 0L
    private var accumulatedDistanceX = 0f
    private var accumulatedDistanceY = 0f

    /**
     * Runnable that hides [playerView] controller
     */
    private val hidePlayerViewControllerAction = Runnable {
        playerView.hideController()
    }

    /**
     * Runnable that hides [gestureIndicatorOverlayLayout]
     */
    private val hideGestureIndicatorOverlayAction = Runnable {
        gestureIndicatorOverlayLayout.isVisible = false
    }

    /**
     * Handles taps when controls are locked
     */
    private val unlockDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                playerLockScreenHelper.peekUnlockButton()
                return true
            }
        },
    )

    /**
     * Handles double tap to seek and brightness/volume gestures
     */
    private val gestureDetector = GestureDetector(
        playerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Toggle play/pause
                fragment.onPlayPause()

                // Show ripple effect centered
                val viewWidth = playerView.measuredWidth
                val viewHeight = playerView.measuredHeight
                playerView.foreground?.apply {
                    setBounds(0, 0, viewWidth, viewHeight)
                    setHotspot(e.x, e.y)
                    state = intArrayOf(android.R.attr.state_enabled, android.R.attr.state_pressed)
                    playerView.postDelayed(Constants.DOUBLE_TAP_RIPPLE_DURATION_MS) {
                        state = IntArray(0)
                    }
                }

                // Show controller briefly
                playerView.removeCallbacks(hidePlayerViewControllerAction)
                playerView.postDelayed(hidePlayerViewControllerAction, Constants.DEFAULT_CONTROLS_TIMEOUT_MS.toLong())
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                playerView.apply {
                    if (!isControllerFullyVisible) showController() else hideController()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!appPreferences.exoPlayerAllowPressSpeedUp) {
                    return
                }

                isOnPressingSpeedUp = true
                speedTierIndex = lastSpeedTierIndex
                speedModeDistanceX = 0f
                speedModePreviousX = e.x
                fragment.onSpeedSelected(SPEED_TIERS[speedTierIndex])
                showSpeedOverlay()
            }

            override fun onScroll(
                firstEvent: MotionEvent?,
                currentEvent: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (!appPreferences.exoPlayerAllowSwipeGestures) {
                    return false
                }

                // Check whether swipe was started in excluded region
                val exclusionSize = playerView.resources.dip(Constants.SWIPE_GESTURE_EXCLUSION_SIZE_VERTICAL)
                if (
                    firstEvent == null ||
                    firstEvent.y < exclusionSize ||
                    firstEvent.y > playerView.height - exclusionSize
                ) {
                    return false
                }

                // Accumulate distances for stable orientation detection
                accumulatedDistanceX += distanceX
                accumulatedDistanceY += distanceY
                val absTotalX = abs(accumulatedDistanceX)
                val absTotalY = abs(accumulatedDistanceY)

                if (absTotalX > absTotalY) {
                    // Horizontal swipe — seek
                    // Initialize starting position on first horizontal frame
                    if (seekGestureValueTracker == 0L) {
                        seekInitialPosition = fragment.getPlayerPosition()
                    }

                    // distanceX is incremental per-frame, compute incremental offset
                    val screenWidth = playerView.measuredWidth
                    val incrementalOffset = (-distanceX / screenWidth * Constants.HORIZONTAL_SWIPE_SEEK_MAX_MS).toLong()
                    seekGestureValueTracker += incrementalOffset
                    seekGestureValueTracker = seekGestureValueTracker.coerceIn(
                        -Constants.HORIZONTAL_SWIPE_SEEK_MAX_MS,
                        Constants.HORIZONTAL_SWIPE_SEEK_MAX_MS,
                    )

                    if (incrementalOffset != 0L) {
                        fragment.onSeekBy(incrementalOffset)
                    }

                    // Show seek direction and time using accumulated tracker
                    val offsetSeconds = (seekGestureValueTracker / 1000).toInt()
                    val isForward = offsetSeconds >= 0
                    val targetPosition = seekInitialPosition + seekGestureValueTracker
                    val duration = fragment.getPlayerDuration()

                    gestureIndicatorOverlayImage.setImageResource(
                        if (isForward) R.drawable.ic_fast_forward_black_32dp
                        else R.drawable.ic_rewind_black_32dp
                    )
                    gestureIndicatorOverlayProgress.isVisible = true
                    if (duration > 0) {
                        val progressPercent = (targetPosition * 100 / duration).toInt().coerceIn(0, 100)
                        gestureIndicatorOverlayProgress.max = 100
                        gestureIndicatorOverlayProgress.progress = progressPercent
                    } else {
                        gestureIndicatorOverlayProgress.max = 100
                        gestureIndicatorOverlayProgress.progress = 0
                    }
                    gestureIndicatorOverlayText.text = if (isForward) "+${offsetSeconds}s" else "${offsetSeconds}s"
                    gestureIndicatorOverlayText.isVisible = true
                    gestureIndicatorOverlayTime.text = "${formatTime(targetPosition)} / ${formatTime(duration)}"
                    gestureIndicatorOverlayTime.isVisible = true
                } else if (absTotalY >= absTotalX * 2) {
                    // Vertical swipe — brightness/volume
                    val viewCenterX = playerView.measuredWidth / 2

                    // Distance to swipe to go from min to max
                    val distanceFull = playerView.measuredHeight * Constants.FULL_SWIPE_RANGE_SCREEN_RATIO
                    // distanceY is incremental per-frame, ratioChange is incremental
                    val ratioChange = distanceY / distanceFull

                    // Hide text views used for horizontal seek
                    gestureIndicatorOverlayText.isVisible = false
                    gestureIndicatorOverlayTime.isVisible = false
                    gestureIndicatorOverlayProgress.isVisible = true

                    if (firstEvent.x.toInt() > viewCenterX) {
                        // Swiping on the right, change volume

                        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        if (swipeGestureValueTracker == -1f) swipeGestureValueTracker = currentVolume.toFloat()

                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val change = ratioChange * maxVolume
                        swipeGestureValueTracker += change

                        val toSet = swipeGestureValueTracker.toInt().coerceIn(0, maxVolume)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, toSet, 0)

                        gestureIndicatorOverlayImage.setImageResource(R.drawable.ic_volume_white_24dp)
                        gestureIndicatorOverlayProgress.max = maxVolume
                        gestureIndicatorOverlayProgress.progress = toSet
                    } else {
                        // Swiping on the left, change brightness

                        val window = fragment.requireActivity().window
                        val brightnessRange = BRIGHTNESS_OVERRIDE_OFF..BRIGHTNESS_OVERRIDE_FULL

                        // Initialize on first swipe
                        if (swipeGestureValueTracker == -1f) {
                            val brightness = window.brightness
                            swipeGestureValueTracker = when (brightness) {
                                in brightnessRange -> brightness
                                else -> {
                                    Settings.System.getFloat(
                                        fragment.requireActivity().contentResolver,
                                        Settings.System.SCREEN_BRIGHTNESS,
                                    ) / Constants.SCREEN_BRIGHTNESS_MAX
                                }
                            }
                        }

                        swipeGestureValueTracker = (swipeGestureValueTracker + ratioChange).coerceIn(brightnessRange)
                        window.brightness = swipeGestureValueTracker
                        if (appPreferences.exoPlayerRememberBrightness) {
                            appPreferences.exoPlayerBrightness = swipeGestureValueTracker
                        }

                        gestureIndicatorOverlayImage.setImageResource(R.drawable.ic_brightness_white_24dp)
                        gestureIndicatorOverlayProgress.max = Constants.PERCENT_MAX
                        gestureIndicatorOverlayProgress.progress = (swipeGestureValueTracker * Constants.PERCENT_MAX).toInt()
                    }
                } else {
                    return false
                }

                resetOverlayStyle()
                gestureIndicatorOverlayLayout.isVisible = true
                return true
            }
        },
    )

    /**
     * Handles scale/zoom gesture
     */
    private val zoomGestureDetector = ScaleGestureDetector(
        playerView.context,
        object : ScaleGestureDetector.OnScaleGestureListener {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = fragment.isLandscape()

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                if (abs(scaleFactor - Constants.ZOOM_SCALE_BASE) > Constants.ZOOM_SCALE_THRESHOLD) {
                    isZoomEnabled = scaleFactor > 1
                    updateZoomMode(isZoomEnabled)
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) = Unit
        },
    ).apply { isQuickScaleEnabled = false }

    init {
        @Suppress("ClickableViewAccessibility")
        playerView.setOnTouchListener { _, event ->
            if (playerView.useController) {
                when (event.pointerCount) {
                    1 -> {
                        // Handle speed mode directly: GestureDetector may not
                        // deliver reliable onScroll events after onLongPress
                        if (isOnPressingSpeedUp && event.action == MotionEvent.ACTION_MOVE) {
                            val deltaX = event.x - speedModePreviousX
                            speedModeDistanceX += deltaX
                            speedModePreviousX = event.x
                            val tierStep = (speedModeDistanceX / SPEED_TIER_PIXEL_STEP).toInt()
                            val newIndex = (4 + tierStep).coerceIn(0, SPEED_TIERS.size - 1)
                            if (newIndex != speedTierIndex) {
                                speedTierIndex = newIndex
                                fragment.onSpeedSelected(SPEED_TIERS[speedTierIndex])
                            }
                            showSpeedOverlay()
                        }
                        gestureDetector.onTouchEvent(event)
                    }
                    2 -> zoomGestureDetector.onTouchEvent(event)
                }
            } else {
                unlockDetector.onTouchEvent(event)
            }
            if (event.action == MotionEvent.ACTION_UP) {
                if (isOnPressingSpeedUp) {
                    isOnPressingSpeedUp = false
                    lastSpeedTierIndex = speedTierIndex
                    fragment.onSpeedSelected(1f)
                    resetOverlayStyle()
                }
                // Hide gesture indicator after timeout, if shown
                gestureIndicatorOverlayLayout.apply {
                    if (isVisible) {
                        removeCallbacks(hideGestureIndicatorOverlayAction)
                        postDelayed(
                            hideGestureIndicatorOverlayAction,
                            Constants.DEFAULT_CENTER_OVERLAY_TIMEOUT_MS.toLong(),
                        )
                    }
                }
                swipeGestureValueTracker = -1f
                seekGestureValueTracker = 0L
                seekInitialPosition = 0L
                accumulatedDistanceX = 0f
                accumulatedDistanceY = 0f
                speedModeDistanceX = 0f
            }
            true
        }
    }

    fun handleConfiguration(newConfig: Configuration) {
        updateZoomMode(fragment.isLandscape(newConfig) && isZoomEnabled)
    }

    private fun updateZoomMode(enabled: Boolean) {
        playerView.resizeMode = if (enabled) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    private fun applySpeedOverlayStyle() {
        (gestureIndicatorOverlayLayout.layoutParams as? FrameLayout.LayoutParams)?.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        gestureIndicatorOverlayLayout.setPadding(
            defaultOverlayPadding,
            gestureIndicatorOverlayLayout.resources.dip(48),
            defaultOverlayPadding,
            defaultOverlayPadding / 2,
        )
        val smallSize = defaultImageSize * 2 / 3
        gestureIndicatorOverlayImage.layoutParams.width = smallSize
        gestureIndicatorOverlayImage.layoutParams.height = smallSize
        gestureIndicatorOverlayText.textSize = 12f
    }

    private fun resetOverlayStyle() {
        (gestureIndicatorOverlayLayout.layoutParams as? FrameLayout.LayoutParams)?.gravity = defaultOverlayGravity
        gestureIndicatorOverlayLayout.setPadding(
            defaultOverlayPadding,
            defaultOverlayPadding,
            defaultOverlayPadding,
            defaultOverlayPadding,
        )
        gestureIndicatorOverlayImage.layoutParams.width = defaultImageSize
        gestureIndicatorOverlayImage.layoutParams.height = defaultImageSize
        gestureIndicatorOverlayText.textSize = 14f
    }

    private fun showSpeedOverlay() {
        val speed = SPEED_TIERS[speedTierIndex]
        applySpeedOverlayStyle()
        gestureIndicatorOverlayProgress.isVisible = false
        gestureIndicatorOverlayImage.setImageResource(R.drawable.ic_slow_motion_video_white_24dp)
        gestureIndicatorOverlayText.text = "${speed}x"
        gestureIndicatorOverlayText.isVisible = true
        gestureIndicatorOverlayTime.isVisible = false
        gestureIndicatorOverlayLayout.isVisible = true
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes}:${seconds.toString().padStart(2, '0')}"
    }

    companion object {
        private val SPEED_TIERS = floatArrayOf(0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f)
        private const val SPEED_TIER_PIXEL_STEP = 60f
    }
}
