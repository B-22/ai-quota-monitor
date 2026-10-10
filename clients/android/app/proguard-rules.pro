# Token Monitor Mobile keeps release builds unminified for now: the app has no
# reflection-based framework that would need keep rules, and a small APK is not
# worth a R8 configuration that has to be revisited every release.
-dontwarn org.conscrypt.**
