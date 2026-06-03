package com.google.mediapipe.examples.handlandmarker

import android.text.method.Touch
import android.util.Log
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.absoluteValue
import kotlin.math.atan2
import kotlin.math.hypot

class GestureClassifier {


    // 界面下拉框
    enum class Gesture {
        NONE,
        MIDDLE_FINGER, // 竖中指   🖕
        PINKY_FINGER, // 竖小拇指  小拇指伸直其他弯曲
        INDEX_FINGER, // 竖食指    👆
        PEACE_V, // 比耶  ✌
        INDEX_MIDDLE_RING,  // 手势三
        INDEX_MIDDLE_RING_PINKY,    // 手势四
        SPIDER_MAN_SHOOTER,     // 🤟
        SPIDER_SHOOTER_NO_THUMB,    // 🤘
        OK, // OK   👌
        THUMB,  //  👍
        Aki_FOX_DEVIL, // 门外命名是秋的味道啊
        SIXSIXSIX
    }

    private var lastGesture: Gesture = Gesture.NONE
    private var consecutiveCount = 0
    private val requiredStableFrames = 3  // 连续3帧一致才确认


    fun classify(result: HandLandmarkerResult): Gesture {
        // Delegate to landmarks-based classifier using the first hand if available
        val first = result.landmarks().firstOrNull() ?: return Gesture.NONE
        return classifyFromLandmarks(first)
    }

    // Public helper: classify a single hand by its landmarks
    fun classifySingleHand(landmarks: List<NormalizedLandmark>): Gesture {
        return classifyFromLandmarks(landmarks)
    }

    // Core classifier that works on a single hand's landmarks. Extracted so it can be reused by
    // both classify(result) and classifySingleHand(landmarks).
    private fun classifyFromLandmarks(landmarks: List<NormalizedLandmark>): Gesture {
        // 计算三个点形成的角度（0-180度）
        fun calculateAngle(
            pointA: NormalizedLandmark,
            pointB: NormalizedLandmark,
            pointC: NormalizedLandmark
        ): Double {
            val abX = pointA.x() - pointB.x()
            val abY = pointA.y() - pointB.y()
            val cbX = pointC.x() - pointB.x()
            val cbY = pointC.y() - pointB.y()

            val dot = (abX * cbX + abY * cbY)
            val cross = (abX * cbY - abY * cbX)

            val alpha = atan2(cross.toDouble(), dot.toDouble())
            return Math.toDegrees(alpha).absoluteValue
        }

        // 判断手指是否伸直
        fun isFingerStraight(mcpIdx: Int, pipIdx: Int, dipIdx: Int, tipIdx: Int): Boolean {
            val mcp = landmarks[mcpIdx]
            val pip = landmarks[pipIdx]
            val dip = landmarks[dipIdx]
            val tip = landmarks[tipIdx]

            val angle1 = calculateAngle(mcp, pip, dip)
            val angle2 = calculateAngle(pip, dip, tip)

            val isStraight = angle1 > 160.0 && angle2 > 160.0
            Log.d(
                "FingerStraight",
                "手指 - " +
                        "关节[$mcpIdx-$pipIdx-$dipIdx-$tipIdx] " +
                        "角度: ${"%.1f".format(angle1)}°(MCP$mcpIdx-PIP$pipIdx-DIP$dipIdx) / " +
                        "${"%.1f".format(angle2)}°(PIP$pipIdx-DIP$dipIdx-TIP$tipIdx), " +
                        "伸直: $isStraight"
            )
            return isStraight
        }

        // 判断两指是否接触
        fun fingersTouch(tip1Idx: Int, tip2Idx: Int, threshold: Float = 0.06f): Boolean {
            val tip1 = landmarks[tip1Idx]
            val tip2 = landmarks[tip2Idx]
            val dist = hypot((tip1.x() - tip2.x()).toDouble(), (tip1.y() - tip2.y()).toDouble())
            Log.d(
                "TouchDebug",
                "指尖 $tip1Idx 和 $tip2Idx: 距离=$dist, 阈值=$threshold"
            )
            return dist < threshold
        }

        val fingerStraights = mapOf(
            "thumb" to isFingerStraight(1, 2, 3, 4),
            "index" to isFingerStraight(5, 6, 7, 8),
            "middle" to isFingerStraight(9, 10, 11, 12),
            "ring" to isFingerStraight(13, 14, 15, 16),
            "pinky" to isFingerStraight(17, 18, 19, 20)
        )

        // Debug: log a few landmark values to help tune classifier
        try {
            val sample = landmarks.take(5).mapIndexed { i, p -> "#$i:(x=${p.x()},y=${p.y()})" }
                .joinToString(",")
            Log.d("GestureClassifier", "Landmarks sample: $sample")
        } catch (e: Exception) {
            Log.w("GestureClassifier", "Failed to log landmarks: ${e.message}")
        }

        //  🖕🏻 点赞：中指伸直，其他手指弯曲
        if (fingerStraights["middle"]!! &&
            !fingerStraights["index"]!! &&
            !fingerStraights["ring"]!! &&
            !fingerStraights["pinky"]!!
        ) {
            return Gesture.MIDDLE_FINGER
        }

        // 666
        if (
            !fingerStraights["index"]!! &&
            !fingerStraights["middle"]!! &&
            !fingerStraights["ring"]!! &&
            fingerStraights["pinky"]!! &&
            !(fingersTouch(4,6) || fingersTouch(4,7) || fingersTouch(4,8)) &&
            !fingersTouch(4,12) &&
            !fingersTouch(4,16)
        ) {
            val angle1_2_3 = try {
                calculateAngle(landmarks[1], landmarks[2], landmarks[3])
            } catch (e: Exception) {
                Double.MAX_VALUE
            }

            val angle2_3_4 = try {
                calculateAngle(landmarks[2], landmarks[3], landmarks[4])
            } catch (e: Exception) {
                Double.MAX_VALUE
            }

            if (angle1_2_3 > 160.0 && angle2_3_4 > 120.0) {
                return Gesture.SIXSIXSIX
            }
            if (angle1_2_3 > 120.0 && angle2_3_4 > 160.0) {
                return Gesture.SIXSIXSIX
            }
        }

        // 取消点赞 小拇指伸直其他弯曲
        if (
            (!fingerStraights["thumb"]!! &&
                    !fingerStraights["index"]!! &&
                    !fingerStraights["middle"]!! &&
                    !fingerStraights["ring"]!! &&
                    fingerStraights["pinky"]!!) &&
            (fingersTouch(4,6) || fingersTouch(4,7) || fingersTouch(4,8))
        ) {
            return Gesture.PINKY_FINGER
        }

        //  👆🏻 下一个：食指伸直，其他手指弯曲
        if (
            !fingerStraights["thumb"]!! &&
            fingerStraights["index"]!! &&
            !fingerStraights["middle"]!! &&
            !fingerStraights["ring"]!! &&
            !fingerStraights["pinky"]!!
        ) {
            return Gesture.INDEX_FINGER
        }

        //  ✌ 食指、中指竖起：比耶，其他手指弯曲
        if (!fingerStraights["thumb"]!! &&
            fingerStraights["index"]!! &&
            fingerStraights["middle"]!! &&
            !fingerStraights["ring"]!! &&
            !fingerStraights["pinky"]!! &&
            !fingersTouch(4,8)
        ) {
            return Gesture.PEACE_V
        }

        if (!fingerStraights["thumb"]!! &&
            fingerStraights["index"]!! &&
            fingerStraights["middle"]!! &&
            fingerStraights["ring"]!! &&
            (fingersTouch(4,20) || fingersTouch(4,19) || fingersTouch(4,18)
                    || fingersTouch(3,20) || fingersTouch(3,19) || fingersTouch(3,18))
        ) {
            return Gesture.INDEX_MIDDLE_RING
        }

        if (!fingerStraights["thumb"]!! &&
            fingerStraights["index"]!! &&
            fingerStraights["middle"]!! &&
            fingerStraights["ring"]!! &&
            fingerStraights["pinky"]!! &&
            (fingersTouch(16,19) || fingersTouch(16,20) || fingersTouch(16,18)
                    || fingersTouch(15,19) || fingersTouch(15,18)) &&
            !fingersTouch(4,20) &&
            !fingersTouch(4,19) &&
            !fingersTouch(4,18) &&
            !fingersTouch(3,20) &&
            !fingersTouch(3,19) &&
            !fingersTouch(3,18)
        ) {
            return Gesture.INDEX_MIDDLE_RING_PINKY
        }

        if (
            fingerStraights["index"]!! &&
            !fingerStraights["middle"]!! &&
            !fingerStraights["ring"]!! &&
            fingerStraights["pinky"]!! &&
            !fingersTouch(4, 12) &&
            !fingersTouch(4, 16)
        ) {
            val angle1_2_3 = try {
                calculateAngle(landmarks[1], landmarks[2], landmarks[3])
            } catch (e: Exception) {
                Double.MAX_VALUE
            }
            val angle2_3_4 = try {
                calculateAngle(landmarks[2], landmarks[3],landmarks[4])
            } catch (e: Exception) {
                Double.MAX_VALUE
            }
            if (angle1_2_3 > 120.0 && angle2_3_4 > 120.0) {
                return Gesture.SPIDER_MAN_SHOOTER
            }
        }

        if (!fingerStraights["thumb"]!! &&
            fingerStraights["index"]!! &&
            !fingerStraights["middle"]!! &&
            !fingerStraights["ring"]!! &&
            fingerStraights["pinky"]!!  &&
            fingersTouch(4, 12) &&
            fingersTouch(4, 16)
        ) {
            val angle9_10_11 = try {
                calculateAngle(landmarks[9], landmarks[10], landmarks[11])
            } catch (e: Exception) {
                Double.MAX_VALUE
            }
            val angle13_14_15 = try {
                calculateAngle(landmarks[13], landmarks[14], landmarks[15])
            } catch (e: Exception) {
                Double.MAX_VALUE
            }
            val angle10_11_12 = try {
                calculateAngle(landmarks[10], landmarks[11], landmarks[12])
            } catch (e: Exception) {
                Double.MIN_VALUE
            }
            val angle14_15_16 = try {
                calculateAngle(landmarks[14], landmarks[15], landmarks[16])
            } catch (e: Exception) {
                Double.MIN_VALUE
            }

            Log.d(
                "GestureClassifier",
                "angles: 9-10-11=${"%.1f".format(angle9_10_11)}, 13-14-15=${
                    "%.1f".format(angle13_14_15)
                }, 10-11-12=${"%.1f".format(angle10_11_12)}, 14-15-16=${
                    "%.1f".format(
                        angle14_15_16
                    )
                }"
            )

            if (angle9_10_11 < 70.0 && angle13_14_15 < 70.0 && angle10_11_12 > 150.0 && angle14_15_16 > 150.0) {
                return Gesture.SPIDER_SHOOTER_NO_THUMB
            }
        }

        if (fingersTouch(4, 8) &&
            fingerStraights["middle"]!! &&
            fingerStraights["ring"]!! &&
            fingerStraights["pinky"]!!
        ) {
            return Gesture.OK
        }

        if (fingerStraights["thumb"]!! &&
            !fingerStraights["index"]!! &&
            !fingerStraights["middle"]!! &&
            !fingerStraights["ring"]!! &&
            !fingerStraights["pinky"]!!
        ) {
            val angle5_6_7 = try {
                calculateAngle(landmarks[5], landmarks[6], landmarks[7])
            } catch (e: Exception) {
                Double.MAX_VALUE
            }
            val angle9_10_11 = try {
                calculateAngle(landmarks[9], landmarks[10], landmarks[11])
            } catch (e: Exception) {
                Double.MAX_VALUE
            }
            val angle13_14_15 = try {
                calculateAngle(landmarks[13], landmarks[14], landmarks[15])
            } catch (e: Exception) {
                Double.MAX_VALUE
            }
            var angle17_18_19 = try {
                calculateAngle(landmarks[17], landmarks[18], landmarks[19])
            } catch (e: Exception) {
                Double.MAX_VALUE
            }

            val angle6_7_8 = try {
                calculateAngle(landmarks[6], landmarks[7], landmarks[8])
            } catch (e: Exception) {
                Double.MIN_VALUE
            }
            val angle10_11_12 = try {
                calculateAngle(landmarks[10], landmarks[11], landmarks[12])
            } catch (e: Exception) {
                Double.MIN_VALUE
            }
            val angle14_15_16 = try {
                calculateAngle(landmarks[14], landmarks[15], landmarks[16])
            } catch (e: Exception) {
                Double.MIN_VALUE
            }
            val angle18_19_20 = try {
                calculateAngle(landmarks[18], landmarks[19], landmarks[20])
            } catch (e: Exception) {
                Double.MIN_VALUE
            }
            if (angle5_6_7 < 80 && angle9_10_11 < 80.0 && angle13_14_15 < 80.0 && angle17_18_19 < 80.0 &&
                angle6_7_8 > 110.0 && angle10_11_12 > 110.0 && angle14_15_16 > 110.0 && angle18_19_20 > 110.0
            ) {
                return Gesture.THUMB
            }
        }

        if (fingersTouch(4, 12) &&
            fingersTouch(4, 16) &&
            fingerStraights["index"]!! &&
            fingerStraights["pinky"]!!
        ) {
            val angle9_10_11 = try {
                calculateAngle(landmarks[9], landmarks[10], landmarks[11])
            } catch (e: Exception) {
                Double.MAX_VALUE
            }
            val angle13_14_15 = try {
                calculateAngle(landmarks[13], landmarks[14], landmarks[15])
            } catch (e: Exception) {
                Double.MAX_VALUE
            }
            val angle10_11_12 = try {
                calculateAngle(landmarks[10], landmarks[11], landmarks[12])
            } catch (e: Exception) {
                Double.MIN_VALUE
            }
            val angle14_15_16 = try {
                calculateAngle(landmarks[14], landmarks[15], landmarks[16])
            } catch (e: Exception) {
                Double.MIN_VALUE
            }

            Log.d(
                "GestureClassifier",
                "angles: 9-10-11=${"%.1f".format(angle9_10_11)}, 13-14-15=${
                    "%.1f".format(angle13_14_15)
                }, 10-11-12=${"%.1f".format(angle10_11_12)}, 14-15-16=${
                    "%.1f".format(
                        angle14_15_16
                    )
                }"
            )
            if (angle9_10_11 > 135.0 && angle13_14_15 > 135.0 && angle10_11_12 > 150.0 && angle14_15_16 > 150.0) {
                return Gesture.Aki_FOX_DEVIL
            }
        }

        return Gesture.NONE
    }

}
