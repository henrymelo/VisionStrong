package com.cadesuel.visionstrong

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import com.github.chrisbanes.photoview.PhotoView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var pdfImageView: PhotoView
    private lateinit var pdfRenderer: PdfRenderer
    private lateinit var currentPage: PdfRenderer.Page
    private lateinit var pageIndicator: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var gestureDetector: GestureDetectorCompat
    private var currentPageIndex = 0
    private var selectedPdfUri: Uri? = null

    private var isRightEyeClosed = false
    private var isLeftEyeClosed = false
    private var isSmilingDetected = false // Flag para controlar a detecção de sorriso

    // Variáveis para rastrear o tempo de fechamento dos olhos
    private var rightEyeClosedStartTime: Long = 0
    private var leftEyeClosedStartTime: Long = 0

    // Handler e Runnable para controlar a detecção de olhos fechados por 1 segundo
    private val handler = Handler(Looper.getMainLooper())
    private val checkEyesRunnable = Runnable {
        if (isRightEyeClosed) {
            onRightEyeClosed()
        }
        if (isLeftEyeClosed) {
            onLeftEyeClosed()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val CAMERA_PERMISSION_CODE = 100
        private const val READ_REQUEST_CODE = 101
        private const val BLINK_THRESHOLD = 0.5 // Limiar para detecção de olho fechado
        private const val EYE_CLOSED_DURATION = 1000 // Tempo em milissegundos para considerar o olho fechado (1 segundo)
        private const val SMILE_THRESHOLD = 0.8 // Limiar para detecção de sorriso
        private const val KEY_PDF_URI = "KEY_PDF_URI"
        private const val KEY_CURRENT_PAGE_INDEX = "KEY_CURRENT_PAGE_INDEX"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pdfImageView = findViewById(R.id.pdfImageView)
        pageIndicator = findViewById(R.id.pageIndicator)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        gestureDetector = GestureDetectorCompat(this, GestureListener())

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (checkCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }

        if (savedInstanceState != null) {
            selectedPdfUri = savedInstanceState.getParcelable(KEY_PDF_URI)
            currentPageIndex = savedInstanceState.getInt(KEY_CURRENT_PAGE_INDEX, 0)
            selectedPdfUri?.let {
                openRenderer(this, it)
                showPage(currentPageIndex)
            }
        } else {
            // Solicitar a seleção do PDF
            selectPdf()
        }

        pdfImageView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(KEY_PDF_URI, selectedPdfUri)
        outState.putInt(KEY_CURRENT_PAGE_INDEX, currentPageIndex)
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

    private fun selectPdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        startActivityForResult(intent, READ_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.also { uri ->
                selectedPdfUri = uri
                loadingIndicator.visibility = ProgressBar.VISIBLE
                try {
                    openRenderer(this, uri)
                    showPage(currentPageIndex)
                } catch (e: IOException) {
                    e.printStackTrace()
                } finally {
                    loadingIndicator.visibility = ProgressBar.GONE
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

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
                    this, cameraSelector, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Erro ao iniciar a câmera: ${exc.message}")
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
                    Log.e(TAG, "Erro ao processar imagem: ${e.message}")
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }
    }

    private fun handleFaces(faces: List<Face>) {
        var isSmilingNow = false // Variável para detectar se está sorrindo agora
        var isRightEyeClosedNow = false // Variável para detectar se o olho direito está fechado agora
        var isLeftEyeClosedNow = false // Variável para detectar se o olho esquerdo está fechado agora

        for (face in faces) {
            val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1f
            val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1f
            val smilingProbability = face.smilingProbability ?: 0f

            // Verifica olho direito
            if (rightEyeOpenProb <= BLINK_THRESHOLD) {
                if (rightEyeClosedStartTime == 0L) {
                    rightEyeClosedStartTime = System.currentTimeMillis()
                }
                isRightEyeClosedNow = true
            } else {
                rightEyeClosedStartTime = 0L
                isRightEyeClosedNow = false
            }

            // Verifica olho esquerdo
            if (leftEyeOpenProb <= BLINK_THRESHOLD) {
                if (leftEyeClosedStartTime == 0L) {
                    leftEyeClosedStartTime = System.currentTimeMillis()
                }
                isLeftEyeClosedNow = true
            } else {
                leftEyeClosedStartTime = 0L
                isLeftEyeClosedNow = false
            }

            // Verifica sorriso
            if (smilingProbability >= SMILE_THRESHOLD) {
                isSmilingNow = true
            }
        }

        // Verifica se o olho direito foi fechado por pelo menos 1 segundo
        if (isRightEyeClosedNow && rightEyeClosedStartTime != 0L) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - rightEyeClosedStartTime >= EYE_CLOSED_DURATION && !isRightEyeClosed) {
                isRightEyeClosed = true
                onRightEyeClosed()
            }
        } else {
            isRightEyeClosed = false
        }

        // Verifica se o olho esquerdo foi fechado por pelo menos 1 segundo
        if (isLeftEyeClosedNow && leftEyeClosedStartTime != 0L) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - leftEyeClosedStartTime >= EYE_CLOSED_DURATION && !isLeftEyeClosed) {
                isLeftEyeClosed = true
                onLeftEyeClosed()
            }
        } else {
            isLeftEyeClosed = false
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
        Log.i(TAG, "Right Eye Closed Detected - Empower Your Sight!")
        runOnUiThread {
            if (currentPageIndex > 0) {
                currentPage.close()
                currentPageIndex -= 1
                showPage(currentPageIndex)
            }
        }
    }

    private fun onLeftEyeClosed() {
        // Implementar ação para olho esquerdo fechado
        Log.i(TAG, "Left Eye Closed Detected - Empower Your Sight!")
        runOnUiThread {
            if (currentPageIndex < pdfRenderer.pageCount - 1) {
                currentPage.close()
                currentPageIndex += 1
                showPage(currentPageIndex)
            }
        }
    }

    private fun onSmileDetected() {
        // Implementar ação para sorriso detectado
        Log.i(TAG, "Smile Detected - Empower Your Sight!")
        runOnUiThread {
            if (currentPageIndex < pdfRenderer.pageCount - 1) {
                currentPage.close()
                currentPageIndex += 1
                showPage(currentPageIndex)
            }
        }
    }

    private fun openRenderer(context: Context, uri: Uri) {
        val fileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
        pdfRenderer = PdfRenderer(fileDescriptor!!)
    }

    private fun showPage(index: Int) {
        if (pdfRenderer.pageCount <= index) return
        currentPage = pdfRenderer.openPage(index)
        val bitmap = Bitmap.createBitmap(currentPage.width, currentPage.height, Bitmap.Config.ARGB_8888)
        currentPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        pdfImageView.setImageBitmap(bitmap)
        pdfImageView.setScaleLevels(1.0f, 2.0f, 4.0f)
        pdfImageView.setScale(1.0f, true)
        updatePageIndicator()
    }

    private fun updatePageIndicator() {
        pageIndicator.text = "pág ${currentPageIndex + 1} de ${pdfRenderer.pageCount}"
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        private val SWIPE_THRESHOLD = 100
        private val SWIPE_VELOCITY_THRESHOLD = 100

        override fun onDown(p0: MotionEvent): Boolean {
            return true
        }

        override fun onFling(p0: MotionEvent?, p1: MotionEvent, p2: Float, p3: Float): Boolean {
            if (p0 == null || p1 == null) return false
            val diffX = p1.x - p0.x
            val diffY = p1.y - p0.y
            return if (Math.abs(diffX) > Math.abs(diffY)) {
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(p2) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        onRightSwipe()
                    } else {
                        onLeftSwipe()
                    }
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }

        private fun onLeftSwipe() {
            Log.i(TAG, "Left Swipe Detected - Empower Your Sight!")
            runOnUiThread {
                if (currentPageIndex < pdfRenderer.pageCount - 1) {
                    currentPage.close()
                    currentPageIndex += 1
                    showPage(currentPageIndex)
                }
            }
        }

        private fun onRightSwipe() {
            Log.i(TAG, "Right Swipe Detected - Empower Your Sight!")
            runOnUiThread {
                if (currentPageIndex > 0) {
                    currentPage.close()
                    currentPageIndex -= 1
                    showPage(currentPageIndex)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        handler.removeCallbacks(checkEyesRunnable) // Remove callbacks ao destruir a atividade
        currentPage.close()
        pdfRenderer.close()
    }
}
