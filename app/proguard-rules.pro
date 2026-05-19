# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Mozilla Nimbus references optional Glean telemetry classes. Lumina does not
# initialize Glean yet, so these are safe to ignore for local release builds.
-dontwarn mozilla.telemetry.glean.Glean
-dontwarn mozilla.telemetry.glean.GleanInternalAPI
-dontwarn mozilla.telemetry.glean.internal.CommonMetricData
-dontwarn mozilla.telemetry.glean.internal.DynamicLabelType
-dontwarn mozilla.telemetry.glean.internal.Lifetime
-dontwarn mozilla.telemetry.glean.internal.TimeUnit
-dontwarn mozilla.telemetry.glean.private.EventExtras
-dontwarn mozilla.telemetry.glean.private.EventMetricType
-dontwarn mozilla.telemetry.glean.private.TimingDistributionMetricType
