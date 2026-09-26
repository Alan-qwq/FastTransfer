plugins {
    id("com.android.application")
}

android {
    namespace = "com.alan.fasttransfer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.alan.fasttransfer"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // 使用 debug 签名，保证在没有自定义 keystore 时也能产出可安装的 release 包
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.kotlin_module"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        // 手势返回已在 AndroidX 中兼容处理，这里不做强制要求
        disable += setOf("GestureBackNavigation")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        // 让编译错误信息保持英文，便于排查
        options.forkOptions.jvmArgs = listOf("-Duser.language=en", "-Duser.country=US")
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.9.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // 扫码连接：CameraX 取景 + ZXing 纯 Java 解码（体积远小于 ML Kit）
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.zxing:core:3.5.4")

    testImplementation("junit:junit:4.13.2")
}

// ============================================================
// Pure-JVM protocol self-test: compiles and runs the real protocol
// layer (HttpServer / HttpUtil / DTO / Json) to verify HTTP parsing,
// chunked bodies, JSON round trips and upload progress.
//   ./gradlew protocolTest
// ============================================================
val protocolTestSources = fileTree("$rootDir/tools/protocoltest") {
    include("**/*.java")
}.files

val protocolCoreSources = listOf(
    "LocalsendProtocol.java",
    "dto/DeviceInfoDto.java",
    "dto/FileDto.java",
    "dto/PrepareUploadRequestDto.java",
    "dto/PrepareUploadResponseDto.java",
    "net/HttpServer.java",
    "net/HttpUtil.java",
    "net/HttpResult.java",
    "util/Json.java",
    "transfer/TransferSession.java",
    "transfer/TransferFile.java",
    "transfer/HistoryEntry.java",
    "transfer/PeerInfo.java",
    "transfer/Peer.java",
    "util/MimeTypes.java",
    "util/LocaleUtil.java",
    "util/PathNormalizer.java",
    "web/WebMultipart.java",
    "web/WebNames.java",
    "web/WebApproval.java",
    "web/WebPage.java"
).map { file("src/main/java/com/alan/fasttransfer/core/$it") }

val protocolTestCompile by tasks.registering(JavaCompile::class) {
    group = "verification"
    description = "Compiles the pure-JVM protocol self-test"
    setSource(files(protocolTestSources, protocolCoreSources))
    classpath = files(configurations.getByName("debugRuntimeClasspath"))
    destinationDirectory.set(layout.buildDirectory.dir("generated/protocolTest/classes"))
    options.encoding = "UTF-8"
    options.compilerArgs.add("-nowarn")
}

tasks.register<JavaExec>("protocolTest") {
    group = "verification"
    description = "Runs the pure-JVM protocol self-test"
    dependsOn(protocolTestCompile)
    mainClass.set("com.alan.fasttransfer.test.ProtocolTest")
    classpath = files(
        layout.buildDirectory.dir("generated/protocolTest/classes"),
        configurations.getByName("debugRuntimeClasspath")
    )
    standardOutput = System.out
}

// ============================================================
// Recursion guard check: programmatic navigation selection must be
// guarded, otherwise showTab -> setSelectedItemId -> callback -> showTab
// recurses until StackOverflowError (this really happened once).
//   ./gradlew guardCheck
// ============================================================
val guardCheckCompile by tasks.registering(JavaCompile::class) {
    group = "verification"
    description = "Compiles the recursion guard checker"
    setSource(files("$rootDir/tools/guardcheck/RecursionGuardCheck.java"))
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("generated/guardCheck/classes"))
    options.encoding = "UTF-8"
    options.compilerArgs.add("-nowarn")
}

tasks.register<JavaExec>("guardCheck") {
    group = "verification"
    description = "Verifies programmatic navigation selection is recursion-guarded"
    dependsOn(guardCheckCompile)
    mainClass.set("RecursionGuardCheck")
    classpath = files(layout.buildDirectory.dir("generated/guardCheck/classes"))
    jvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
    args(file("src/main/java").absolutePath)
    standardOutput = System.out
}

// Context 包装防护：包装 Context 前必须判断 instanceof Activity，
// 否则 Fragment 的 getActivity() 会返回 null（改显示大小时已因此闪退）
val contextCheckCompile by tasks.registering(JavaCompile::class) {
    group = "verification"
    description = "Compiles the context wrapper guard checker"
    setSource(files("$rootDir/tools/guardcheck/ContextWrapperCheck.java"))
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("generated/contextCheck/classes"))
    options.encoding = "UTF-8"
    options.compilerArgs.add("-nowarn")
}

tasks.register<JavaExec>("contextCheck") {
    group = "verification"
    description = "Verifies Context wrapping keeps the Activity identity"
    dependsOn(contextCheckCompile)
    mainClass.set("ContextWrapperCheck")
    classpath = files(layout.buildDirectory.dir("generated/contextCheck/classes"))
    jvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
    args(file("src/main/java").absolutePath)
    standardOutput = System.out
}

// 会话呈状态机（「传完了还显示正在发送」就是这里出的问题）
tasks.register<JavaExec>("sessionRenderTest") {
    group = "verification"
    description = "Runs the transfer session render-state self-test"
    dependsOn(protocolTestCompile)
    mainClass.set("SessionRenderTest")
    classpath = files(layout.buildDirectory.dir("generated/protocolTest/classes"))
    standardOutput = System.out
}

// 显示大小换算（改显示大小闪退就是这里出的问题）
tasks.register<JavaExec>("uiScaleTest") {
    group = "verification"
    description = "Runs the display-size conversion self-test"
    dependsOn(protocolTestCompile)
    mainClass.set("UiScaleTest")
    classpath = files(layout.buildDirectory.dir("generated/protocolTest/classes"))
    standardOutput = System.out
}

// 保存路径归一化（手写保存位置依赖它）
tasks.register<JavaExec>("pathNormalizerTest") {
    group = "verification"
    description = "Runs the save-path normalisation self-test"
    dependsOn(protocolTestCompile)
    mainClass.set("PathNormalizerTest")
    classpath = files(layout.buildDirectory.dir("generated/protocolTest/classes"))
    standardOutput = System.out
}

// 网页上传的 multipart 解析
tasks.register<JavaExec>("multipartTest") {
    group = "verification"
    description = "Runs the multipart parser self-test"
    dependsOn(protocolTestCompile)
    mainClass.set("WebMultipartTest")
    classpath = files(layout.buildDirectory.dir("generated/protocolTest/classes"))
    standardOutput = System.out
}

// 网页传输的纯逻辑（文件名安全、HTML 转义、页面结构）
tasks.register<JavaExec>("webPageTest") {
    group = "verification"
    description = "Runs the web transfer page self-test"
    dependsOn(protocolTestCompile)
    mainClass.set("WebPageTest")
    classpath = files(layout.buildDirectory.dir("generated/protocolTest/classes"))
    standardOutput = System.out
}

// 传输记录「点击打开」的判定逻辑
tasks.register<JavaExec>("historyOpenTest") {
    group = "verification"
    description = "Runs the history open-ability self-test"
    dependsOn(protocolTestCompile)
    mainClass.set("HistoryOpenTest")
    classpath = files(layout.buildDirectory.dir("generated/protocolTest/classes"))
    standardOutput = System.out
}

// ============================================================
// Layout inflatability check: every custom view written in the
// layouts must be constructible by LayoutInflater (non-abstract and
// with a public (Context, AttributeSet) constructor). Putting an
// abstract class such as NavigationBarView in XML crashes at runtime
// while compiling and linting fine, so this check catches it.
//   ./gradlew layoutCheck
// ============================================================
val layoutCheckCompile by tasks.registering(JavaCompile::class) {
    group = "verification"
    description = "Compiles the layout inflatability checker"
    setSource(files("$rootDir/tools/layoutcheck/LayoutChecker.java"))
    classpath = files()
    destinationDirectory.set(layout.buildDirectory.dir("generated/layoutCheck/classes"))
    options.encoding = "UTF-8"
    options.compilerArgs.add("-nowarn")
}

tasks.register<JavaExec>("layoutCheck") {
    group = "verification"
    description = "Verifies every custom view in the layouts can be inflated"
    dependsOn(layoutCheckCompile, "assembleDebug")
    mainClass.set("LayoutChecker")
    classpath = files(layout.buildDirectory.dir("generated/layoutCheck/classes"))
    jvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    val sdkDir = androidComponents.sdkComponents.sdkDirectory.get().asFile
    val androidJar = files("${sdkDir}/platforms/android-${android.compileSdk}/android.jar")
    val appClasses = layout.buildDirectory.dir(
        "intermediates/javac/debug/compileDebugJavaWithJavac/classes"
    )
    val runtimeClasspath = configurations.getByName("debugRuntimeClasspath")
    argumentProviders.add {
        listOf(file("src/main/res").absolutePath) +
            files(appClasses, runtimeClasspath, androidJar).files.map { it.absolutePath }
    }
    standardOutput = System.out
}
