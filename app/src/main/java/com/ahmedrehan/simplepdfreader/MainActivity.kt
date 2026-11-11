package com.ahmedrehan.simplepdfreader

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException

class MainActivity : Activity() {
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var currentBitmap: Bitmap? = null
    private var currentPage = 0

    private lateinit var pageLabel: TextView
    private lateinit var pageImage: ZoomableImageView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var openButton: Button
    private lateinit var controls: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 4, 0, 0)
            setBackgroundColor(Color.rgb(245, 245, 245))
        }

        openButton = Button(this).apply {
            text = "Open PDF"
            setOnClickListener { choosePdf() }
        }
        root.addView(openButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        previousButton = Button(this).apply {
            text = "Previous"
            isEnabled = false
            setOnClickListener { showPage(currentPage - 1) }
        }
        nextButton = Button(this).apply {
            text = "Next"
            isEnabled = false
            setOnClickListener { showPage(currentPage + 1) }
        }
        pageLabel = TextView(this).apply {
            text = "No PDF opened"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(8, 0, 8, 0)
        }
        controls.addView(previousButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(pageLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(nextButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controls)

        pageImage = ZoomableImageView(this) { next ->
            showPage(currentPage + if (next) 1 else -1)
        }.apply {
            adjustViewBounds = true
            setOnClickListener { toggleReaderControls() }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(pageImage, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (renderer != null) showPage(currentPage)
    }

    private fun choosePdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_OPEN_PDF)
    }

    @Deprecated("Deprecated in Android, retained for compatibility with the minimum supported version")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_PDF || resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        try {
            data.flags.let { flags ->
                contentResolver.takePersistableUriPermission(
                    uri, flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            closePdf()
            fileDescriptor = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Could not open this PDF file")
            renderer = PdfRenderer(fileDescriptor!!)
            currentPage = 0
            showPage(currentPage)
        } catch (error: Exception) {
            closePdf()
            Toast.makeText(this, "Could not open PDF: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showPage(index: Int) {
        val pdf = renderer ?: return
        if (index !in 0 until pdf.pageCount) return

        try {
            val page = pdf.openPage(index)
            try {
                val screenWidth = resources.displayMetrics.widthPixels.coerceAtLeast(320)
                val maxDimension = 1200
                val scale = minOf(
                    screenWidth.toFloat() / page.width,
                    maxDimension.toFloat() / page.width,
                    maxDimension.toFloat() / page.height
                )
                val bitmapWidth = (page.width * scale).toInt().coerceAtLeast(1)
                val bitmapHeight = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                currentBitmap?.recycle()
                currentBitmap = bitmap
                pageImage.resetZoom()
                pageImage.setImageBitmap(bitmap)
                currentPage = index
                openButton.visibility = ViewGroup.GONE
                controls.visibility = ViewGroup.GONE
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
                pageLabel.text = "${index + 1} / ${pdf.pageCount}"
                previousButton.isEnabled = index > 0
                nextButton.isEnabled = index < pdf.pageCount - 1
            } finally {
                page.close()
            }
        } catch (error: Exception) {
            Toast.makeText(this, "Could not display page: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleReaderControls() {
        if (renderer == null) return
        val visible = controls.visibility != ViewGroup.VISIBLE
        controls.visibility = if (visible) ViewGroup.VISIBLE else ViewGroup.GONE
        openButton.visibility = if (visible) ViewGroup.VISIBLE else ViewGroup.GONE
    }

    private fun closePdf() {
        if (::openButton.isInitialized) openButton.visibility = ViewGroup.VISIBLE
        if (::controls.isInitialized) controls.visibility = ViewGroup.VISIBLE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE

        pageImageOrNull()?.setImageDrawable(null)
        currentBitmap?.recycle()
        currentBitmap = null
        renderer?.close()
        renderer = null
        fileDescriptor?.close()
        fileDescriptor = null
        currentPage = 0
    }

    private fun pageImageOrNull(): ImageView? = if (::pageImage.isInitialized) pageImage else null

    override fun onDestroy() {
        closePdf()
        super.onDestroy()
    }

    private class ZoomableImageView(
        context: android.content.Context,
        private val onSwipe: (Boolean) -> Unit
    ) : ImageView(context) {
        private var scaleFactor = 1f
        private var downX = 0f
        private var downY = 0f
        private val scaleDetector = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(1f, 4f)
                    scaleX = scaleFactor
                    scaleY = scaleFactor
                    return true
                }
            })

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            // Record a one-finger gesture for page navigation.
            if (event.actionMasked == MotionEvent.ACTION_DOWN && event.pointerCount == 1) {
                downX = event.x
                downY = event.y
            }

            // Prevent the parent ScrollView from intercepting a two-finger pinch.
            if (event.pointerCount > 1 || scaleDetector.isInProgress) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            if (event.actionMasked == MotionEvent.ACTION_UP && scaleFactor <= 1.05f) {
                val dx = event.x - downX
                val dy = event.y - downY
                val threshold = 72f * resources.displayMetrics.density
                if (kotlin.math.abs(dx) > threshold &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.3f) {
                    onSwipe(dx < 0)
                }
            }

            val handled = super.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            return handled
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            return true
        }

        fun resetZoom() {
            scaleFactor = 1f
            scaleX = 1f
            scaleY = 1f
        }
    }

    companion object {
        private const val REQUEST_OPEN_PDF = 1001
    }
}
