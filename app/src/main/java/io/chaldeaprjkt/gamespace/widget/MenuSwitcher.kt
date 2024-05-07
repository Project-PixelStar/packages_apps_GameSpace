package io.chaldeaprjkt.gamespace.widget

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.Display
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Choreographer
import io.chaldeaprjkt.gamespace.R
import io.chaldeaprjkt.gamespace.utils.di.ServiceViewEntryPoint
import io.chaldeaprjkt.gamespace.utils.dp
import io.chaldeaprjkt.gamespace.utils.entryPointOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.math.RoundingMode
import java.text.DecimalFormat

class MenuSwitcher @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private var fpsInfoNode: RandomAccessFile? = null
    private var fpsReadJob: Job? = null
    private var fpsReadInterval = 1000L

    private val appSettings by lazy { context.entryPointOf<ServiceViewEntryPoint>().appSettings() }
    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    private val choreographer = Choreographer.getInstance()
    private var lastFrameTimeNanos: Long = 0
    private var frameCount = 0
    private var lastFPSTimeMillis = System.currentTimeMillis()
    private var fps = 0f
    private var maxRefreshRate: Float = 60f
    private var previousFrameNS: Long = 0
    private var currentFrameNS: Long = 0
    private var droppedFrames: Int = 0

    init {
        LayoutInflater.from(context).inflate(R.layout.bar_menu_switcher, this, true)
        initFPSInfoNode()
    }

    private fun initFPSInfoNode() {
        val nodePath = resources.getString(R.string.config_fpsInfoSysNode)
        val file = File(nodePath)
        if (file.exists() && file.canRead()) {
            try {
                fpsInfoNode = RandomAccessFile(nodePath, "r")
            } catch (e: IOException) {
                Log.e(TAG, "Error while opening file: $nodePath", e)
            }
        } else {
            Log.e(TAG, "$nodePath does not exist or is not readable")
        }
        fpsReadInterval = resources.getInteger(R.integer.config_fpsReadInterval).toLong()
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameTimeNanos > 0) {
                val droppedFrames = calculateDroppedFrames(lastFrameTimeNanos, frameTimeNanos, maxRefreshRate)
                frameCount += 1 + droppedFrames
                val currentTimeMillis = System.currentTimeMillis()
                val timeDiffMillis = currentTimeMillis - lastFPSTimeMillis
                if (timeDiffMillis >= 1000) {
                    fps = (frameCount * 1000f) / timeDiffMillis
                    onFrameUpdated()
                    frameCount = 0
                    lastFPSTimeMillis = currentTimeMillis
                }
            }
            lastFrameTimeNanos = frameTimeNanos
            choreographer?.postFrameCallback(this)
        }
    }

    private fun calculateDroppedFrames(previousFrameNS: Long, currentFrameNS: Long, refreshRate: Float): Int {
        val frameTimeNS = 1_000_000_000 / refreshRate
        val frameDurationNS = currentFrameNS - previousFrameNS
        if (frameDurationNS < frameTimeNS) {
            return 0
        } else {
            return (frameDurationNS.toFloat() / frameTimeNS).toInt() - 1
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private fun startReadingFpsFromNode() {
        if (fpsReadJob != null) return
        fpsReadJob = scope.launch {
            do {
                fps = measureFps()
                onFrameUpdated()
                delay(fpsReadInterval)
            } while (isActive)
        }
    }

    private fun stopReadingFromNode() {
        if (fpsReadJob == null) return
        fpsReadJob?.cancel()
        fpsReadJob = null
    }

    private fun measureFps(): Float {
        fpsInfoNode!!.seek(0L)
        val measuredFps: String
        try {
            measuredFps = fpsInfoNode!!.readLine()
        } catch (e: IOException) {
            Log.e(TAG, "IOException while reading from FPS node, ${e.message}")
            return -1.0f
        }
        try {
            val fps: Float = measuredFps.trim().let {
                if (it.contains(": ")) it.split("\\s+".toRegex())[1] else it
            }.toFloat()
            return fps
        } catch (e: NumberFormatException) {
            Log.e(TAG, "NumberFormatException occurred while parsing FPS info, ${e.message}")
        }
        return -1.0f
    }

    private val content: TextView?
        get() = findViewById(R.id.menu_content)

    var showFps = false
        set(value) {
            setMenuIcon(null)
            field = value
        }

    var isDragged = false
        set(value) {
            if (value && !showFps) setMenuIcon(R.drawable.ic_drag)
            field = value
        }

    fun updateIconState(isExpanded: Boolean, location: Int) {
        showFps = if (isExpanded) false else appSettings.showFps
        when {
            isExpanded -> R.drawable.ic_close
            location > 0 -> R.drawable.ic_arrow_right
            else -> R.drawable.ic_arrow_left
        }.let { setMenuIcon(it) }
        updateFrameRateBinding()
    }

    private fun onFrameUpdated() = scope.launch {
        handler.post {
            val maxFPS = fps.coerceAtMost(maxRefreshRate)
            DecimalFormat("#").apply {
                roundingMode = RoundingMode.HALF_EVEN
                content?.text = format(maxFPS)
            }
        }
    }
    
    private fun canShowFpsFromNode(): Boolean {
        return fpsInfoNode != null
    }

    private fun updateFrameRateBinding() {
        if (showFps) {
            if (canShowFpsFromNode()) {
                startReadingFpsFromNode()
            } else { 
                registerFpsCallback()
            }
        } else {
            if (canShowFpsFromNode()) {
                stopReadingFromNode()
            } else { 
                choreographer.removeFrameCallback(frameCallback)
            }
        }
    }

    private fun setMenuIcon(icon: Int?) {
        when (icon) {
            R.drawable.ic_close, R.drawable.ic_drag -> layoutParams.width = 36.dp
            else -> layoutParams.width = LayoutParams.WRAP_CONTENT
        }
        val ic = icon?.takeIf { !showFps }?.let { resources.getDrawable(it, context.theme) }
        content?.textScaleX = if (showFps) 1f else 0f
        content?.setCompoundDrawablesRelativeWithIntrinsicBounds(null, ic, null, null)
    }

    private fun getMaxRefreshRate(context: Context): Float {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val displayMode = display?.mode
        return displayMode?.refreshRate ?: 60f
    }

    private fun registerFpsCallback() {
        maxRefreshRate = getMaxRefreshRate(context)
        choreographer.postFrameCallback(frameCallback)
        lastFPSTimeMillis = System.currentTimeMillis()
        handler.postDelayed({
            choreographer.removeFrameCallback(frameCallback)
            choreographer.postFrameCallback(frameCallback)
        }, 100L)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        choreographer.removeFrameCallback(frameCallback)
    }
    
    companion object {
        private const val TAG = "MenuSwitcher"
    }
}
