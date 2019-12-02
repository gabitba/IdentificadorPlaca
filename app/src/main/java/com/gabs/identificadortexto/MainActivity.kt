package com.gabs.identificadortexto

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.media.Image
import android.os.Bundle
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.firebase.ml.vision.FirebaseVision
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import com.google.firebase.ml.vision.text.FirebaseVisionText
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern


private const val REQUEST_CODE_PERMISSIONS = 1
private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

class MainActivity : AppCompatActivity(), LifecycleOwner {

    private lateinit var viewFinder: TextureView
    private lateinit var plate: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private var lastAnalyzedTimestamp = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.view_finder)
        plate = findViewById(R.id.plate)

        // Request camera permissions
        if (allPermissionsGranted()) {
            viewFinder.post { startCamera() }
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Every time the provided texture view changes, recompute layout
        viewFinder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTransform()
        }
    }

    private fun startCamera() {

        val imageAnalysisConfig = ImageAnalysisConfig.Builder()
            .setTargetResolution(Size(640, 480))
            .setImageReaderMode(ImageAnalysis.ImageReaderMode.ACQUIRE_LATEST_IMAGE)
            .build()

        val imageAnalysis = ImageAnalysis(imageAnalysisConfig)
        imageAnalysis.setAnalyzer(
            executor,
            ImageAnalysis.Analyzer { image: ImageProxy, rotationDegrees: Int ->
                val currentTimestamp = System.currentTimeMillis()
//                if (currentTimestamp - lastAnalyzedTimestamp >= TimeUnit.MILLISECONDS.toMillis(1)) {
                    identificarTexto(image.image, rotationDegrees)
//                    lastAnalyzedTimestamp = currentTimestamp
//                }
            })

        // Create configuration object for the viewfinder use case
        val previewConfig = PreviewConfig.Builder().apply {
            //setTargetAspectRatio(Rational(1, 1))
            setTargetResolution(Size(640, 480))
        }.build()

        // Build the viewfinder use case
        val preview = Preview(previewConfig)

        // Every time the viewfinder is updated, recompute layout
        preview.setOnPreviewOutputUpdateListener {
            val parent = viewFinder.parent as ViewGroup

            // To update the SurfaceTexture, we have to remove it and re-add it
            parent.removeView(viewFinder)
            parent.addView(viewFinder, 0)

            viewFinder.surfaceTexture = it.surfaceTexture
            updateTransform()
        }

        // Bind use cases to lifecycle
        // If Android Studio complains about "this" being not a LifecycleOwner
        // try rebuilding the project or updating the appcompat dependency to
        // version 1.1.0 or higher.
        CameraX.bindToLifecycle(this, preview, imageAnalysis)
    }

    private fun updateTransform() {
        val matrix = Matrix()

        // Compute the center of the view finder
        val centerX = viewFinder.width / 2f
        val centerY = viewFinder.height / 2f

        // Correct preview output to account for display rotation
        val rotationDegrees = when (viewFinder.display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> return
        }
        matrix.postRotate(-rotationDegrees.toFloat(), centerX, centerY)

        // Finally, apply transformations to our TextureView
        viewFinder.setTransform(matrix)
    }

    /**
     * Process result from permission request dialog box, has the request
     * been granted? If yes, start Camera. Otherwise display a toast
     */
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                viewFinder.post { startCamera() }
            } else {
                Toast.makeText(
                    this,
                    "Permissões negadas pelo usuário.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    /**
     * Check if all permission specified in the manifest have been granted
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun identificarTexto(image: Image?, rotationCompensation: Int) {
        if (image == null)
            return
            val imagem = FirebaseVisionImage.fromMediaImage(image, getRotation(rotationCompensation))
            val detector = FirebaseVision.getInstance().onDeviceTextRecognizer
            detector.processImage(imagem)
                .addOnSuccessListener { firebaseVisionText -> exibirTexto(firebaseVisionText) }
    }

    private fun exibirTexto(firebaseVisionText: FirebaseVisionText) {
        var textIdentified = ""
        val blocks = firebaseVisionText.textBlocks
        for (i in blocks.indices) {
            val lines = blocks[i].lines
            for (j in lines.indices) {
                val elements = lines[j].elements
                for (k in elements.indices) {
                    textIdentified += elements[k].text
                }
            }
        }

        if(textIdentified.isNotEmpty()){
            val plateIdentified = extractPlateNumber(textIdentified)
            if(plateIdentified != null)
                plate.text = plateIdentified
        }
    }

    private fun extractPlateNumber(text: String): String? {

        val regexRemoveSpecialCharacter = Regex("[^a-zA-Z0-9]")
        val textFormatted = text.replace(regexRemoveSpecialCharacter, "")

        val platePattern: Pattern = Pattern.compile("\\D{3}+\\d{4}+")
        val plateMatcher: Matcher = platePattern.matcher(textFormatted)
        if (plateMatcher.matches())
            return plateMatcher.group()

        val mercosulPlatePattern: Pattern = Pattern.compile("\\D{3}+\\d{1}+\\D{1}+\\d{2}+")
        val mercosulPlateMatcher: Matcher = mercosulPlatePattern.matcher(textFormatted)
        if (mercosulPlateMatcher.matches())
            return mercosulPlateMatcher.group()

        return null
    }

    private fun getRotation(rotationCompensation: Int) : Int{
        val result: Int
        when (rotationCompensation) {
            0 -> result = FirebaseVisionImageMetadata.ROTATION_0
            90 -> result = FirebaseVisionImageMetadata.ROTATION_90
            180 -> result = FirebaseVisionImageMetadata.ROTATION_180
            270 -> result = FirebaseVisionImageMetadata.ROTATION_270
            else -> {
                result = FirebaseVisionImageMetadata.ROTATION_0
            }
        }
        return result
    }
}
