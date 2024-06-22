package com.cadesuel.visionstrong.presentation

import com.google.mlkit.vision.face.Face

class EyeDetectionPresenter(private val view: EyeDetectionView) {
    companion object {
        private const val SMILE_THRESHOLD = 0.8 // Limiar para detecção de sorriso
        private const val BLINK_THRESHOLD = 0.5f // Exemplo de threshold para olho fechado
    }

    fun handleFaces(faces: List<Face>) {
        var isSmilingNow = false // Variável para detectar se está sorrindo agora

        for (face in faces) {
            val leftEyeOpenProb = face.leftEyeOpenProbability ?: 1f
            val rightEyeOpenProb = face.rightEyeOpenProbability ?: 1f
            val smilingProbability = face.smilingProbability ?: 0f

            view.updateRightEyeClosed(rightEyeOpenProb)
            view.updateLeftEyeClosed(leftEyeOpenProb)

            // Verifica sorriso
            if (smilingProbability >= SMILE_THRESHOLD) {
                isSmilingNow = true
            }
        }

        // Se o sorriso for detectado agora e não foi detectado antes, chama onSmileDetected()
        if (isSmilingNow) {
            view.onSmileDetected()
        }
    }

    fun handleSmileDetected() {
        view.onSmileDetected()
    }

    fun handleRightEyeClosed(probability: Float) {
        if (probability <= BLINK_THRESHOLD) {
            view.updateRightEyeClosed(probability)
        }
    }

    fun handleLeftEyeClosed(probability: Float) {
        if (probability <= BLINK_THRESHOLD) {
            view.updateLeftEyeClosed(probability)
        }
    }
}

interface EyeDetectionView {
    fun updateRightEyeClosed(probability: Float)
    fun updateLeftEyeClosed(probability: Float)
    fun onSmileDetected()
}
