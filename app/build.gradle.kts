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
        versionCode = 18
        versionName = "1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
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

    // Firebase (login con Google + registro en Firestore)
    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-messaging")   // push (FCM): afectaciones y aviso de actualización
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Traducción automática de la interfaz (motor de Google, sin conexión tras descargar modelo)
    implementation("com.google.mlkit:translate:17.0.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}