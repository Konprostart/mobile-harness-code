package com.mobileharness.code.runtime

import com.mobileharness.code.model.AndroidTemplate
import com.mobileharness.code.model.projectSlug
import java.io.File

/** Source inputs for a WEB project: the asset directory to embed plus optional manifest metadata. */
data class WebAppProjectConfig(
    /** The directory whose contents are copied into `app/src/main/assets/www/`. */
    val sourceDir: File,
    /** Entry HTML file relative to [sourceDir] (e.g. `index.html`). */
    val entryPoint: String = "index.html",
    /** A validated custom manifest when present, or null. */
    val manifest: WebAppManifest? = null,
)

object AndroidProjectTemplateGenerator {
    fun packageName(name: String): String {
        val raw = projectSlug(name).replace('-', '_').take(40).ifBlank { "app" }
        val suffix = if (raw.first().isLetter()) raw else "app_$raw"
        return "com.pocketdev.$suffix"
    }

    fun generate(
        destination: File,
        appName: String,
        template: AndroidTemplate,
        gradleMemoryProfile: GradleMemoryProfile = GradleMemoryProfile.DEFAULT,
        web: WebAppProjectConfig? = null,
    ) {
        val parent = requireNotNull(destination.parentFile)
        val staging = File(parent, ".${destination.name}.android-template")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not prepare the Android project" }
        try {
            val namespace = packageName(appName)
            writeCommon(staging, appName, namespace, template, gradleMemoryProfile)
            when (template) {
                AndroidTemplate.COMPOSE -> writeCompose(staging, namespace)
                AndroidTemplate.XML -> writeXml(staging, namespace)
                AndroidTemplate.WEB -> writeWeb(staging, namespace, web)
            }
            if (destination.exists()) {
                check(destination.listFiles().isNullOrEmpty()) { "Project folder is not empty" }
                check(destination.delete()) { "Could not replace the empty project folder" }
            }
            check(staging.renameTo(destination)) { "Could not finish creating the Android project" }
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    /** Generates a WEB project that wraps a ready HTML/CSS/JS app in a WebView shell. */
    fun generateWebApp(
        destination: File,
        appName: String,
        gradleMemoryProfile: GradleMemoryProfile,
        web: WebAppProjectConfig,
    ) = generate(destination, appName, AndroidTemplate.WEB, gradleMemoryProfile, web)

    private fun writeCommon(
        root: File,
        appName: String,
        namespace: String,
        template: AndroidTemplate,
        gradleMemoryProfile: GradleMemoryProfile,
    ) {
        write(root, "settings.gradle.kts", """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
            rootProject.name = ${kotlinString(appName)}
            include(":app")
        """)
        write(root, "build.gradle.kts", """
            plugins {
                id("com.android.application") version "8.11.0" apply false
                id("org.jetbrains.kotlin.android") version "1.9.22" apply false
            }
        """)
        write(root, "gradle.properties", """
            ${GradleMemory.JVMARGS_KEY}=${gradleMemoryProfile.jvmArgs}
            android.useAndroidX=true
            kotlin.code.style=official
            android.nonTransitiveRClass=true
        """)
        write(root, "app/build.gradle.kts", appBuildGradle(namespace, template))
        write(root, ".gitignore", """
            .gradle/
            local.properties
            **/build/
            *.iml
        """)
        write(root, ".pocketdev/android-project.json", """{"schemaVersion":1,"template":"${template.name}","packageName":"$namespace"}""")
        write(root, "app/src/main/res/values/strings.xml", """
            <resources><string name="app_name">${xmlEscape(appName)}</string></resources>
        """)
    }

    private fun appBuildGradle(namespace: String, template: AndroidTemplate): String {
        val compose = template == AndroidTemplate.COMPOSE
        val dependencies = when (template) {
            AndroidTemplate.COMPOSE -> """
                implementation("androidx.core:core-ktx:1.8.0")
                implementation("androidx.activity:activity-compose:1.5.1")
                implementation("androidx.compose.ui:ui:1.3.0")
                implementation("androidx.compose.foundation:foundation:1.3.0")
                implementation("androidx.compose.material3:material3:1.0.0")
            """.trimIndent()
            AndroidTemplate.XML -> """
                implementation("androidx.core:core-ktx:1.8.0")
                implementation("androidx.appcompat:appcompat:1.6.1")
                implementation("com.google.android.material:material:1.9.0")
            """.trimIndent()
            AndroidTemplate.WEB -> """
                implementation("androidx.core:core-ktx:1.8.0")
                implementation("androidx.appcompat:appcompat:1.6.1")
                implementation("androidx.webkit:webkit:1.12.1")
            """.trimIndent()
        }
        return """
            plugins {
                id("com.android.application")
                id("org.jetbrains.kotlin.android")
            }

            android {
                namespace = "$namespace"
                compileSdk = 36

                defaultConfig {
                    applicationId = "$namespace"
                    minSdk = 28
                    targetSdk = 36
                    versionCode = 1
                    versionName = "1.0"
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                kotlinOptions { jvmTarget = "17" }
                buildFeatures { compose = $compose }
                ${if (compose) "composeOptions { kotlinCompilerExtensionVersion = \"1.5.10\" }" else ""}
            }

            dependencies {
                $dependencies
            }
        """
    }

    private fun writeCompose(root: File, namespace: String) {
        val packagePath = namespace.replace('.', '/')
        write(root, "app/src/main/AndroidManifest.xml", """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:theme="@style/Theme.PocketDevApp" android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """)
        write(root, "app/src/main/res/values/themes.xml", """
            <resources>
                <style name="Theme.PocketDevApp" parent="android:style/Theme.Material.Light.NoActionBar">
                    <item name="android:fontFamily">sans</item>
                    <item name="android:colorAccent">#6750A4</item>
                    <item name="android:statusBarColor">#FFFBFE</item>
                    <item name="android:navigationBarColor">#FFFBFE</item>
                    <item name="android:windowLightStatusBar">true</item>
                    <item name="android:windowLightNavigationBar">true</item>
                </style>
            </resources>
        """)
        write(root, "app/src/main/java/$packagePath/MainActivity.kt", """
            package $namespace

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.foundation.layout.Arrangement
            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent { MaterialTheme { Welcome() } }
                }
            }

            @Composable
            private fun Welcome() {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Text("Built with PocketDev")
                }
            }
        """)
    }

    private fun writeXml(root: File, namespace: String) {
        val packagePath = namespace.replace('.', '/')
        write(root, "app/src/main/AndroidManifest.xml", """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:theme="@style/Theme.PocketDevApp" android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """)
        write(root, "app/src/main/res/values/themes.xml", """
            <resources>
                <style name="Theme.PocketDevApp" parent="Theme.MaterialComponents.DayNight.NoActionBar">
                    <item name="colorPrimary">#6750A4</item>
                    <item name="android:statusBarColor">?android:colorBackground</item>
                    <item name="android:navigationBarColor">?android:colorBackground</item>
                    <item name="android:windowLightStatusBar">true</item>
                    <item name="android:windowLightNavigationBar">true</item>
                </style>
            </resources>
        """)
        write(root, "app/src/main/res/values-night/themes.xml", """
            <resources>
                <style name="Theme.PocketDevApp" parent="Theme.MaterialComponents.DayNight.NoActionBar">
                    <item name="colorPrimary">#D0BCFF</item>
                    <item name="android:statusBarColor">?android:colorBackground</item>
                    <item name="android:navigationBarColor">?android:colorBackground</item>
                    <item name="android:windowLightStatusBar">false</item>
                    <item name="android:windowLightNavigationBar">false</item>
                </style>
            </resources>
        """)
        write(root, "app/src/main/res/layout/activity_main.xml", """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="match_parent" android:layout_height="match_parent" android:gravity="center" android:orientation="vertical">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content" android:text="Built with PocketDev" android:textSize="22sp" />
            </LinearLayout>
        """)
        write(root, "app/src/main/java/$packagePath/MainActivity.kt", """
            package $namespace

            import android.os.Bundle
            import androidx.appcompat.app.AppCompatActivity

            class MainActivity : AppCompatActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.activity_main)
                }
            }
        """)
    }

    private fun writeWeb(root: File, namespace: String, web: WebAppProjectConfig?) {
        val packagePath = namespace.replace('.', '/')
        val entryPoint = web?.entryPoint?.takeIf { it.isNotBlank() } ?: "index.html"
        val manifest = web?.manifest
        val hasNetwork = manifest?.permissions?.contains("network") == true
        val themeColor = manifest?.theme?.takeIf { Regex("^#[0-9a-fA-F]{6}$").matches(it) } ?: "#FFFFFF"

        write(root, "app/src/main/AndroidManifest.xml", """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                ${if (hasNetwork) "<uses-permission android:name=\"android.permission.INTERNET\" />" else ""}
                <application android:theme="@style/Theme.PocketDevApp" android:label="@string/app_name">
                    <activity android:name=".MainActivity" android:exported="true" android:configChanges="orientation|screenSize|keyboardHidden">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """)
        write(root, "app/src/main/res/values/themes.xml", """
            <resources>
                <style name="Theme.PocketDevApp" parent="Theme.AppCompat.DayNight.NoActionBar">
                    <item name="colorPrimary">$themeColor</item>
                    <item name="android:statusBarColor">$themeColor</item>
                    <item name="android:navigationBarColor">$themeColor</item>
                    <item name="android:windowLightStatusBar">true</item>
                </style>
            </resources>
        """)
        write(root, "app/src/main/java/$packagePath/NativeBridge.kt", nativeBridgeSource(namespace))
        write(root, "app/src/main/java/$packagePath/MainActivity.kt", webActivitySource(namespace, entryPoint, themeColor))
        val assets = File(root, "app/src/main/assets/www")
        if (web?.sourceDir?.isDirectory == true) {
            copyWebAssets(web.sourceDir, assets)
        } else {
            assets.mkdirs()
            File(assets, "index.html").writeText(
                "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><title>Built with PocketDev</title></head><body style=\"font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0\"><h2>Built with PocketDev</h2></body></html>\n",
            )
        }
    }

    private fun nativeBridgeSource(namespace: String): String = """
        package $namespace

        import android.annotation.SuppressLint
        import android.content.ClipData
        import android.content.ClipboardManager
        import android.content.Context
        import android.content.Intent
        import android.os.Build
        import android.os.Handler
        import android.os.Looper
        import android.os.VibrationEffect
        import android.os.Vibrator
        import android.os.VibratorManager
        import android.webkit.JavascriptInterface
        import android.widget.Toast

        /**
         * Minimal, strictly scoped JavaScript bridge. Exposes only safe, non-destructive helpers; it
         * has no file, network or process access. Methods are idempotent and validate their inputs.
         */
        class NativeBridge(private val context: Context) {
            @JavascriptInterface
            fun copyToClipboard(text: String?) = onUi {
                val safe = (text ?: "").take(1_000_000)
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("web", safe))
            }

            @JavascriptInterface
            fun shareText(text: String?) = onUi {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, (text ?: "").take(1_000_000))
                }
                runCatching {
                    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            @JavascriptInterface
            fun vibrate(millis: Long) = onUi {
                val duration = millis.coerceIn(0L, 2000L)
                if (duration <= 0L) return@onUi
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    manager.defaultVibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    (context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(duration)
                }
            }

            @JavascriptInterface
            fun toast(message: String?) = onUi {
                Toast.makeText(context, (message ?: "").take(200), Toast.LENGTH_SHORT).show()
            }

            @JavascriptInterface
            fun appName(): String = context.getString(R.string.app_name)

            private fun onUi(block: () -> Unit) {
                if (Looper.myLooper() == Looper.getMainLooper()) block()
                else Handler(Looper.getMainLooper()).post(block)
            }
        }
    """

    private fun webActivitySource(namespace: String, entryPoint: String, themeColor: String): String = """
        package $namespace

        import android.annotation.SuppressLint
        import android.content.Intent
        import android.os.Bundle
        import android.webkit.WebResourceRequest
        import android.webkit.WebResourceResponse
        import android.webkit.WebView
        import androidx.appcompat.app.AppCompatActivity
        import androidx.webkit.WebViewAssetLoader
        import androidx.webkit.WebViewClientCompat

        class MainActivity : AppCompatActivity() {
            private lateinit var webView: WebView

            @SuppressLint("SetJavaScriptEnabled")
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                webView = WebView(this)
                setContentView(webView)
                setThemeColor("$themeColor")

                val loader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
                    .build()

                webView.webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        loader.shouldInterceptRequest(request.url)

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (request.url.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                        return true
                    }
                }

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportZoom(false)
                    displayZoomControls = false
                }

                webView.addJavascriptInterface(NativeBridge(this), "AndroidBridge")
                webView.loadUrl("https://${'$'}{WebViewAssetLoader.DEFAULT_DOMAIN}/assets/www/$entryPoint")
            }

            override fun onBackPressed() {
                if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
            }

            private fun setThemeColor(color: String) {
                runCatching {
                    val parsed = android.graphics.Color.parseColor(color)
                    window.statusBarColor = parsed
                    window.navigationBarColor = parsed
                    webView.setBackgroundColor(parsed)
                }
            }
        }
    """

    private fun copyWebAssets(sourceDir: File, destination: File) {
        require(sourceDir.isDirectory) { "Web asset source is not a directory" }
        destination.mkdirs()
        val sourceRoot = sourceDir.canonicalFile.toPath()
        sourceDir.walkTopDown()
            .onEnter { dir -> dir.name != ".git" && dir.name != "node_modules" }
            .filter { it.isFile }
            .forEach { file ->
                val relative = file.relativeTo(sourceDir).invariantSeparatorsPath
                val target = File(destination, relative).canonicalFile
                check(target.toPath().startsWith(destination.canonicalFile.toPath())) { "Unsafe web asset path: $relative" }
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
            }
    }

    private fun write(root: File, relative: String, content: String) {
        File(root, relative).apply {
            parentFile?.mkdirs()
            writeText(content.trimIndent().trim() + "\n")
        }
    }

    private fun kotlinString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    private fun xmlEscape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
