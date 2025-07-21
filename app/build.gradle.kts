plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.google.gms.google.services)
}

android {
    namespace = "com.example.mytpu"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.mytpu"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "D&C1917_b1.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildToolsVersion = "35.0.0"
}

dependencies {
    // https://mvnrepository.com/artifact/com.squareup.okhttp3/okhttp
    implementation ("com.google.android.material:material:1.10.0")
    implementation ("com.google.android.gms:play-services-auth:20.7.0")
    implementation ("androidx.preference:preference:1.2.1")
    implementation ("com.github.yukuku:ambilwarna:2.0.1")
    implementation ("io.apisense:rhino-android:1.0")
    implementation ("org.apache.commons:commons-lang3:3.12.0")
    implementation ("commons-codec:commons-codec:1.15")
    implementation ("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation ("com.squareup.okhttp3:okhttp-urlconnection:4.11.0")
    implementation ("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation ("org.jsoup:jsoup:1.15.4")
    implementation ("androidx.work:work-runtime:2.9.0")
    implementation ("androidx.concurrent:concurrent-futures:1.1.0")
    implementation ("androidx.drawerlayout:drawerlayout:1.2.0")
    // Retrofit
    implementation ("androidx.appcompat:appcompat:1.6.1")
    implementation ("androidx.fragment:fragment-ktx:1.6.2")
    implementation ("com.squareup.retrofit2:retrofit:2.9.0")
    implementation ("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation ("androidx.cardview:cardview:1.0.0")
    implementation ("com.github.bumptech.glide:glide:4.12.0")
    // Gson
    implementation ("com.google.code.gson:gson:2.10.1")
    // LiveData
    implementation ("androidx.lifecycle:lifecycle-livedata:2.5.1")

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.security.crypto)
    implementation(libs.media3.common)
    implementation(libs.firebase.crashlytics.buildtools)
    implementation(libs.cronet.embedded)
    implementation(libs.swiperefreshlayout)
    implementation(libs.browser)
    implementation(libs.firebase.auth)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
}