package com.cadesuel.visionstrong

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.view.PreviewView

class MainActivity : AppCompatActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private var lastRightEyeClosedTime: Long = 0
    private var lastLeftEyeClosedTime: Long = 0
    private var isRightEyeClosed = false
    private var isLeftEyeClosed = false

    private var isSmiling = false
    private var isSmilingDetected = false // Flag para controlar a detecção de sorriso

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val BLINK_THRESHOLD = 0.5 // Limiar para detecção de olho fechado
        private const val EYE_CLOSED_DURATION = 1000 // Tempo em milissegundos para considerar o olho fechado (1 segundo)
        private const val SMILE_THRESHOLD = 0.8 // Limiar para detecção de sorriso
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (checkCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissão da câmera foi negada", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    val previewView = findViewById<PreviewView>(R.id.viewFinder)
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, { imageProxy ->
                        processImageProxy(imageProxy)
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                // Log any errors
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build()

            val detector = FaceDetection.getClient(options)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    handleFaces(faces)
                }
                .addOnFailureListener { e ->
                    // Handle any errors
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    private fun handleFaces(faces: List<Face>) {
        var isSmilingNow = false // Variável para detectar se está sorrindo agora

        for (face in faces) {
            val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1f
            val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1f
            val smilingProbability = face.smilingProbability ?: 0f

            // Verifica olho direito
            if (rightEyeOpenProb <= BLINK_THRESHOLD) {
                isRightEyeClosed = true
                lastRightEyeClosedTime = System.currentTimeMillis()
            } else {
                isRightEyeClosed = false
            }

            // Verifica olho esquerdo
            if (leftEyeOpenProb <= BLINK_THRESHOLD) {
                isLeftEyeClosed = true
                lastLeftEyeClosedTime = System.currentTimeMillis()
            } else {
                isLeftEyeClosed = false
            }

            // Verifica sorriso
            if (smilingProbability >= SMILE_THRESHOLD) {
                isSmilingNow = true
            }
        }

        // Se o sorriso for detectado agora e não foi detectado antes, chama onSmileDetected()
        if (isSmilingNow && !isSmilingDetected) {
            onSmileDetected()
            isSmilingDetected = true
        }

        // Se o sorriso não for mais detectado, redefine a flag isSmilingDetected
        if (!isSmilingNow) {
            isSmilingDetected = false
        }
    }

    private fun onRightEyeClosed() {
        // Implementar ação para olho direito fechado
        println("Right Eye Closed Detected - Empower Your Sight!")
    }

    private fun onLeftEyeClosed() {
        // Implementar ação para olho esquerdo fechado
        println("Left Eye Closed Detected - Empower Your Sight!")
    }

    private fun onSmileDetected() {
        // Implementar ação para sorriso detectado
        println("Smile Detected - Empower Your Sight!")
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
