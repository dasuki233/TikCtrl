# MediaPipe - keep all classes and native methods
-keep class com.google.mediapipe.** { *; }
-keep class com.google.mediapipe.tasks.** { *; }
-keep class com.google.mediapipe.framework.** { *; }
-keep class com.google.mediapipe.components.** { *; }
-keep class com.google.mediapipe.formats.** { *; }
-keep class com.google.mediapipe.solutions.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }
-keepclasseswithmembernames class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# CameraX - keep key classes
-keep class androidx.camera.** { *; }
-keepclassmembers class androidx.camera.** { *; }

# Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlin.coroutines.** { *; }

# Accessibility service
-keep class com.tikctrl.app.GestureActionService { *; }
-keep class com.tikctrl.app.HandGestureService { *; }
-keep class com.tikctrl.app.GestureClassifier { *; }
-keep class com.tikctrl.app.GestureClassifier$Gesture { *; }
-keep class com.tikctrl.app.GestureMappingManager { *; }
-keep class com.tikctrl.app.GestureMappingManager$Action { *; }
-keep class com.tikctrl.app.GestureMappingManager$SingleHandMode { *; }
-keep class com.tikctrl.app.ConfigManager { *; }

# SharedPreferences keys (reflection access)
-keepclassmembers class com.tikctrl.app.** { *; }

# MPImage and BitmapImageBuilder (used by reflection in MediaPipe)
-keep class com.google.mediapipe.framework.image.** { *; }

# Native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Parcelable
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
