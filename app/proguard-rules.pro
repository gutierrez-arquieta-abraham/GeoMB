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

# Conserva números de línea para que los stack traces de Play Console sean legibles,
# ocultando el nombre real del archivo fuente.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- GeoMB (release) ---
# Firestore se usa con Map + merge() (sin POJOs), así que no hacen falta reglas de modelo.
# Firebase, ML Kit y Play Services incluyen sus propias reglas de consumidor.
# Se conservan las anotaciones de Firebase por si a futuro se leen objetos con toObject().
-keepattributes *Annotation*,Signature