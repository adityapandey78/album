# ML Kit's bundled labeller reports every call to Google Play services' API telemetry (TelemetryLogging), which the
# manifest can't switch off, so R8 strips the call. The name is obfuscated and pinned to image-labeling 17.0.9. After an
# upgrade, check the call is still removed: R8 lists removed code in usage.txt, so this must print the zzc line:
#   grep -A5 "mlkit_vision_label_bundled.zztf:" app/build/outputs/mapping/release/usage.txt | grep zzc
# Debug builds don't run R8, so they still send this telemetry: privacy holds for release builds only.
-assumenosideeffects class com.google.android.gms.internal.mlkit_vision_label_bundled.zztf {
    void zzc(int, int, long, long);
}
