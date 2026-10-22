package net.conw.unit.scan

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import java.util.concurrent.Executors
import net.conw.unit.R
import net.conw.unit.browser.BrowserLanguages
import net.conw.unit.browser.BrowserTheme
import net.conw.unit.browser.UrlUtils
import net.conw.unit.ui.Ui

class ScanActivity : Activity() {
    private enum class Source {
        CAMERA,
        IMAGE,
    }

    private lateinit var camera: QrCamera
    private lateinit var preview: TextureView
    private lateinit var hint: TextView
    private lateinit var choices: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var image: ImageButton
    private lateinit var retry: ImageButton
    private lateinit var cameraChoice: Button
    private lateinit var imageChoice: Button
    private var resultDialog: AlertDialog? = null
    private var resultText: String? = null
    private var resumed = false
    private var source = Source.CAMERA
    private var picking = false
    private var selectedImage: Uri? = null
    private var imageResult: Result<String?>? = null
    private var message = R.string.scan_choose_source
    private var cameraFailed = false
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "unit-qr-image") }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(BrowserLanguages.wrap(BrowserTheme.wrap(base)))
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        restoreState(state)
        if (intent.getBooleanExtra("incognito", false))
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(createContent())
        initializeCamera()
        updateScanner()
        selectedImage?.let(::readImage)
    }

    private fun restoreState(state: Bundle?) {
        source =
            if (state?.getString("source") == Source.IMAGE.name) Source.IMAGE else Source.CAMERA
        picking = state?.getBoolean("picking") ?: false
        selectedImage = state?.getString("image")?.let { it.toUri() }
        resultText = state?.getString("result")
        message =
            state?.getInt("message", R.string.scan_choose_source) ?: R.string.scan_choose_source
    }

    private fun createContent(): LinearLayout {
        val column = Ui.column(this).apply { setBackgroundColor(getColor(R.color.background)) }
        ViewCompat.setOnApplyWindowInsetsListener(column) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        column.addView(createHeader())
        preview =
            TextureView(this).apply {
                id = R.id.scan_preview
                contentDescription = getString(R.string.camera_preview)
            }
        choices = createSourceChoices()
        progress =
            ProgressBar(this).apply {
                id = R.id.scan_progress
                indeterminateTintList = ColorStateList.valueOf(getColor(R.color.accent))
            }
        val frame =
            FrameLayout(this).apply {
                background = Ui.background(this@ScanActivity, R.color.surface_variant, 20)
                clipToOutline = true
                addView(preview, FrameLayout.LayoutParams(-1, -1))
                addView(choices, FrameLayout.LayoutParams(-1, -1))
                addView(progress, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            }
        column.addView(
            frame,
            LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(dp(12), 0, dp(12), 0) },
        )
        hint =
            Ui.text(this, R.string.scan_hint, 14, R.color.muted).apply {
                id = R.id.scan_hint
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(20), dp(20), dp(20))
            }
        column.addView(hint)
        return column
    }

    private fun createHeader() =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(8), dp(8), dp(8))
            addView(
                Ui.text(this@ScanActivity, R.string.scan, 20, R.color.text),
                LinearLayout.LayoutParams(0, dp(44), 1f),
            )
            image =
                Ui.iconButton(
                        this@ScanActivity,
                        R.drawable.ic_image,
                        R.string.image,
                        ::chooseImage,
                    )
                    .apply { id = R.id.scan_image }
            retry =
                Ui.iconButton(this@ScanActivity, R.drawable.ic_reload, R.string.retry) {
                    if (source == Source.CAMERA) chooseCamera() else chooseImage()
                }
            addView(image)
            addView(retry)
            addView(
                Ui.iconButton(
                    this@ScanActivity,
                    R.drawable.ic_close,
                    getString(R.string.close_panel, getString(R.string.scan)),
                ) {
                    finish()
                }
            )
        }

    private fun createSourceChoices(): ScrollView {
        val sourceChoices =
            Ui.column(this).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(
                    ImageView(this@ScanActivity).apply {
                        setImageResource(R.drawable.ic_scan)
                        imageTintList = ColorStateList.valueOf(getColor(R.color.accent))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(dp(56), dp(56)),
                )
                addView(
                    Ui.text(this@ScanActivity, R.string.scan_title, 18, R.color.text).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(16), 0, dp(20))
                    }
                )
                addView(
                    LinearLayout(this@ScanActivity).apply {
                        gravity = Gravity.CENTER
                        cameraChoice =
                            sourceButton(R.drawable.ic_camera, R.string.scan_camera, ::chooseCamera)
                                .apply { id = R.id.scan_camera }
                        imageChoice =
                            sourceButton(R.drawable.ic_image, R.string.image, ::chooseImage)
                        addView(
                            cameraChoice,
                            LinearLayout.LayoutParams(-2, dp(48)).apply { rightMargin = dp(12) },
                        )
                        addView(imageChoice, LinearLayout.LayoutParams(-2, dp(48)))
                    }
                )
            }
        return ScrollView(this).apply {
            id = R.id.scan_choices
            isFillViewport = true
            addView(sourceChoices, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun initializeCamera() {
        camera =
            QrCamera(
                preview,
                { text ->
                    if (source == Source.CAMERA && !picking && selectedImage == null)
                        showResult(text)
                },
            ) {
                cameraFailed = true
                updateScanner()
            }
        preview.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    texture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) = updateScanner()

                override fun onSurfaceTextureSizeChanged(
                    texture: SurfaceTexture,
                    width: Int,
                    height: Int,
                ) {
                    camera.stop()
                    updateScanner()
                }

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    camera.stop()
                    return true
                }

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
            }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateScanner()
        consumeImageResult()
        if (!picking && selectedImage == null) resultText?.let(::showResult)
    }

    override fun onPause() {
        resumed = false
        camera.stop()
        super.onPause()
    }

    override fun onDestroy() {
        resumed = false
        resultDialog?.dismiss()
        camera.stop()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putString("source", source.name)
        state.putBoolean("picking", picking)
        state.putString("image", selectedImage?.toString())
        state.putString("result", resultText)
        state.putInt("message", message)
        super.onSaveInstanceState(state)
    }

    override fun onRequestPermissionsResult(
        request: Int,
        permissions: Array<out String>,
        results: IntArray,
    ) {
        super.onRequestPermissionsResult(request, permissions, results)
        if (request == CAMERA_PERMISSION) {
            message =
                if (hasCameraPermission()) R.string.scan_choose_source else R.string.camera_required
            updateScanner()
        }
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != PICK_IMAGE) return
        picking = false
        val uri = data?.data?.takeIf { result == RESULT_OK }
        if (uri != null) {
            source = Source.IMAGE
            resultText = null
            selectedImage = uri
            message = R.string.scan_choose_source
            readImage(uri)
        }
        updateScanner()
        if (uri == null && resumed) resultText?.let(::showResult)
    }

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun chooseCamera() {
        source = Source.CAMERA
        cameraFailed = false
        message = R.string.scan_choose_source
        camera.stop()
        if (!hasCameraPermission())
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        updateScanner()
    }

    private fun chooseImage() {
        if (picking || selectedImage != null) return
        picking = true
        resultDialog?.dismiss()
        resultDialog = null
        camera.stop()
        updateScanner()
        try {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                PICK_IMAGE,
            )
        } catch (_: ActivityNotFoundException) {
            picking = false
            updateScanner()
            resultText?.let(::showResult)
            android.widget.Toast.makeText(
                    this,
                    R.string.no_file_picker,
                    android.widget.Toast.LENGTH_SHORT,
                )
                .show()
        }
    }

    private fun readImage(uri: Uri) {
        val resolver = contentResolver
        worker.execute {
            val result = runCatching { QrImage.decode(resolver, uri) }
            if (!Thread.currentThread().isInterrupted)
                main.post {
                    if (!isFinishing && !isDestroyed) {
                        imageResult = result
                        consumeImageResult()
                    }
                }
        }
    }

    private fun consumeImageResult() {
        if (!resumed) return
        val result = imageResult ?: return
        imageResult = null
        selectedImage = null
        val text = result.getOrNull()
        message =
            if (result.isFailure) R.string.image_unavailable
            else if (text == null) R.string.no_qr_code else R.string.scan_choose_source
        updateScanner()
        if (text != null) showResult(text)
    }

    private fun updateScanner() {
        val reading = selectedImage != null
        val busy = picking || reading || resultText != null
        val cameraReady = source == Source.CAMERA && hasCameraPermission() && !cameraFailed && !busy
        preview.visibility = if (cameraReady) View.VISIBLE else View.INVISIBLE
        choices.visibility = if (!cameraReady && !reading) View.VISIBLE else View.GONE
        progress.visibility = if (reading) View.VISIBLE else View.GONE
        image.isEnabled = !busy
        retry.isEnabled = !busy
        cameraChoice.isEnabled = !busy
        imageChoice.isEnabled = !busy
        hint.setText(
            when {
                reading -> R.string.reading_image
                cameraFailed && source == Source.CAMERA -> R.string.camera_unavailable
                cameraReady -> R.string.scan_hint
                else -> message
            }
        )
        if (resumed && cameraReady) camera.start() else camera.stop()
    }

    private fun sourceButton(icon: Int, label: Int, click: () -> Unit) =
        Button(this).apply {
            setText(label)
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(getColor(R.color.accent))
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(getColor(R.color.accent)))
            compoundDrawablePadding = dp(8)
            background = Ui.ripple(this@ScanActivity, R.color.surface, 12)
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { click() }
        }

    private fun showResult(text: String) {
        if (
            !resumed ||
                isFinishing ||
                isDestroyed ||
                picking ||
                selectedImage != null ||
                resultDialog != null
        )
            return
        val builder =
            Ui.dialog(this)
                .setTitle(R.string.qr_code)
                .setMessage(text)
                .setNegativeButton(
                    if (source == Source.IMAGE) R.string.image else R.string.rescan,
                    null,
                )
                .setNeutralButton(R.string.copy, null)
        if (UrlUtils.isWeb(text.trim()))
            builder.setPositiveButton(R.string.open) { _, _ ->
                setResult(RESULT_OK, Intent().putExtra("url", text.trim()))
                finish()
            }
        val dialog = builder.create()
        resultText = text
        resultDialog = dialog
        updateScanner()
        dialog.setOnDismissListener {
            if (resultDialog !== dialog) return@setOnDismissListener
            if (!picking) resultText = null
            resultDialog = null
            if (!isFinishing && !isDestroyed) updateScanner()
        }
        dialog.show()
        if (intent.getBooleanExtra("incognito", false))
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Ui.styleDialog(dialog)
        if (source == Source.IMAGE)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                chooseImage()
            }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            Ui.copy(this, R.string.qr_code, text)
        }
    }

    private fun dp(value: Int) = Ui.dp(this, value)

    private companion object {
        const val CAMERA_PERMISSION = 1
        const val PICK_IMAGE = 2
    }
}
