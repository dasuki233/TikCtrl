package com.google.mediapipe.examples.handlandmarker

import android.content.Context
import android.util.Log

class GestureActionManager {

    fun performAction(gesture: GestureClassifier.Gesture) {
        when (gesture) {
            // OK   👌
            GestureClassifier.Gesture.OK -> {
                Log.d("GestureAction", "Detected OK")
            }
            // THUMB  👍
            GestureClassifier.Gesture.THUMB -> {
                Log.d("GestureAction", "Detected THUMB")
            }
//          MIDDLE_FINGER  竖中指   🖕
            GestureClassifier.Gesture.MIDDLE_FINGER -> {
                Log.d("GestureAction", "Detected MIDDLE_FINGER")
            }
            // 竖小拇指  小拇指伸直其他弯曲
            GestureClassifier.Gesture.PINKY_FINGER -> {
                Log.d("GestureAction", "Detected PINKY_FINGER")
            }
            // 门外命名是秋的味道啊
            GestureClassifier.Gesture.Aki_FOX_DEVIL -> {
                Log.d("GestureAction", "Detected Aki_FOX_DEVIL")
            }
            // 竖食指    👆
            GestureClassifier.Gesture.INDEX_FINGER -> {
                Log.d("GestureAction", "Detected INDEX_FINGER")
            }
            // 比耶  ✌
            GestureClassifier.Gesture.PEACE_V -> {
                Log.d("GestureAction", "Detected PEACE_V")
            }
            // 手势三
            GestureClassifier.Gesture.INDEX_MIDDLE_RING -> {
                Log.d("GestureAction", "Detected INDEX_MIDDLE_RING")
            }
            // 手势四
            GestureClassifier.Gesture.INDEX_MIDDLE_RING_PINKY-> {
                Log.d("GestureAction", "Detected INDEX_MIDDLE_RING_PINKY")
            }
            // 🤟
            GestureClassifier.Gesture.SPIDER_MAN_SHOOTER -> {
                Log.d("GestureAction", "Detected SPIDER_MAN_SHOOTER")
            }
            // 🤘
            GestureClassifier.Gesture.SPIDER_SHOOTER_NO_THUMB -> {
                Log.d("GestureAction", "Detected SPIDER_SHOOTER_NO_THUMB")
            }
            // 6
            GestureClassifier.Gesture.SIXSIXSIX -> {
                Log.d("GestureAction", "Detected SIXSIXSIX")
            }

            else -> {}
        }
    }
}
