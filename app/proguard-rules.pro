# ML Kit starts its components by reflection (MlKitInitProvider instantiates each registrar listed in the manifest).
# R8 full mode keeps the class names but drops their no-arg constructors, so ML Kit never starts and the smart index
# labels nothing in release builds (debug builds were fine). Seen as NoSuchMethodException: ...Registrar.<init> in logcat.
-keep class * implements com.google.firebase.components.ComponentRegistrar { public <init>(); }

# ML Kit's bundled labeller reports every call to Google Play services' API telemetry (TelemetryLogging), which the
# manifest can't switch off, so R8 strips the call. The name is obfuscated and pinned to image-labeling 17.0.9. After an
# upgrade, check the call is still removed: R8 lists removed code in usage.txt, so this must print the zzc line:
#   grep -A5 "mlkit_vision_label_bundled.zztf:" app/build/outputs/mapping/release/usage.txt | grep zzc
# Debug builds don't run R8, so they still send this telemetry: privacy holds for release builds only.
-assumenosideeffects class com.google.android.gms.internal.mlkit_vision_label_bundled.zztf {
    void zzc(int, int, long, long);
}

# Text recognition (text-recognition 16.0.1, through play-services-mlkit-text-recognition-common 19.1.0) has the same
# telemetry in its own copy of the class. Check after an upgrade, as above:
#   grep -A5 "mlkit_vision_text_common.zzue:" app/build/outputs/mapping/release/usage.txt | grep zzc
-assumenosideeffects class com.google.android.gms.internal.mlkit_vision_text_common.zzue {
    void zzc(int, int, long, long);
}

# Same telemetry in the bundled face detector (People): one call in the detector, one in ML Kit's face API wrapper.
# Pinned to face-detection 16.1.7 (and its play-services-mlkit-face-detection 17.1.0). After an upgrade, both must print:
#   grep -A5 "mlkit_vision_face_bundled.zzsg:" app/build/outputs/mapping/release/usage.txt | grep zzc
#   grep -A5 "mlkit_vision_face.zzoe:" app/build/outputs/mapping/release/usage.txt | grep zzc
-assumenosideeffects class com.google.android.gms.internal.mlkit_vision_face_bundled.zzsg {
    void zzc(int, int, long, long);
}
-assumenosideeffects class com.google.android.gms.internal.mlkit_vision_face.zzoe {
    void zzc(int, int, long, long);
}
