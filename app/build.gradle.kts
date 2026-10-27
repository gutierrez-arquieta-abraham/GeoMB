plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.memegrados.GeoMB"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.memegrados.GeoMB"
        minSdk = 24
        targetSdk = 36
        versionCode = 26
        versionName = "1.6.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Maps API key por variante: la key compartida (AndroidManifest antes) quedó restringida en
    // Google Cloud Console a "Apps para Android" con el SHA-1 de Play App Signing (release) -- el
    // keystore de debug tiene OTRO SHA-1, así que debug necesita su PROPIA key (sin esa
    // restricción, o restringida al SHA-1 de debug) para que el mapa siga funcionando al compilar
    // localmente. Ver AndroidManifest.xml: <meta-data android:value="${mapsApiKey}">.
    buildTypes {
        debug {
            manifestPlaceholders["mapsApiKey"] = "AIzaSyBP-_aWAJWygVA33obzPPlhlyu1afgNV5g"
        }
        release {
            manifestPlaceholders["mapsApiKey"] = "AIzaSyBVWqLHPP_BgAX0y1qewGY98-zk00McoSo"
            isMinifyEnabled = true      // R8: optimiza y reduce el código
            isShrinkResources = true    // elimina recursos no usados (requiere minify)
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.play.services.maps)
    implementation(libs.play.services.location)
    implementation(libs.play.services.ads)   // banner de AdMob (activity_main.xml: AdView adSize/adUnitId)

    // Firebase (login con Google + registro en Firestore)
    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-messaging")   // push (FCM): afectaciones y aviso de actualización
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Traducción automática de la interfaz (motor de Google, sin conexión tras descargar modelo)
    implementation("com.google.mlkit:translate:17.0.3")

    // Mini base de datos LOCAL (Room): recorridos, tiempos por estación, errores, búsquedas y
    // económicos favoritos. Se sincroniza a Firestore (usuarios/{uid}/...) cuando hay sesión y red.
    implementation("androidx.room:room-runtime:2.6.1")
    annotationProcessor("androidx.room:room-compiler:2.6.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
