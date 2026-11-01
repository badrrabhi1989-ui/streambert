/**
 * Idempotent edits on the Capacitor Android project: Kotlin plugin, app
 * version, native dependencies, permissions, picture-in-picture activity,
 * download foreground service, and FileProvider paths.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const androidDir = path.join(root, "android");
const appGradle = path.join(androidDir, "app", "build.gradle");
const rootGradle = path.join(androidDir, "build.gradle");

if (!fs.existsSync(appGradle)) {
  console.error("android/app/build.gradle is missing. Run `npx cap add android` first.");
  process.exit(1);
}

let rootText = fs.readFileSync(rootGradle, "utf8");
if (!rootText.includes("kotlin-gradle-plugin")) {
  rootText = rootText.replace(
    "classpath 'com.android.tools.build:gradle:8.7.2'\n",
    "classpath 'com.android.tools.build:gradle:8.7.2'\n        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21'\n",
  );
  fs.writeFileSync(rootGradle, rootText);
}

let app = fs.readFileSync(appGradle, "utf8");
if (!app.includes("kotlin-android")) {
  app = app.replace(
    "apply plugin: 'com.android.application'\n",
    "apply plugin: 'com.android.application'\napply plugin: 'kotlin-android'\n",
  );
}
app = app.replace(/versionCode \d+/, "versionCode 27000");
app = app.replace(/versionName "[^"]+"/, 'versionName "2.7.0"');
if (!app.includes("jvmTarget")) {
  app = app.replace(
    "    buildTypes {",
    "    kotlinOptions {\n        jvmTarget = '21'\n    }\n    buildTypes {",
  );
}
if (!app.includes("okhttp")) {
  app = app.replace(
    "    implementation project(':capacitor-android')\n",
    [
      "    implementation project(':capacitor-android')",
      '    implementation "com.squareup.okhttp3:okhttp:4.12.0"',
      '    implementation "androidx.webkit:webkit:$androidxWebkitVersion"',
      '    implementation "androidx.security:security-crypto:1.0.0"',
      '    implementation "androidx.documentfile:documentfile:1.0.1"',
      "",
    ].join("\n"),
  );
}
fs.writeFileSync(appGradle, app);

const manifestPath = path.join(androidDir, "app", "src", "main", "AndroidManifest.xml");
fs.writeFileSync(
  manifestPath,
  `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />
    <uses-feature android:name="android.software.leanback" android:required="false" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:banner="@drawable/tv_banner"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:usesCleartextTraffic="true"
        android:theme="@style/AppTheme">

        <activity
            android:configChanges="orientation|keyboardHidden|keyboard|screenSize|locale|smallestScreenSize|screenLayout|uiMode|navigation"
            android:name=".MainActivity"
            android:label="@string/title_activity_main"
            android:theme="@style/AppTheme.NoActionBarLaunch"
            android:banner="@drawable/tv_banner"
            android:launchMode="singleTask"
            android:exported="true">

            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>

        </activity>

        <activity
            android:name=".PopoutActivity"
            android:exported="false"
            android:theme="@style/AppTheme.NoActionBar"
            android:supportsPictureInPicture="true"
            android:resizeableActivity="true"
            android:configChanges="orientation|keyboardHidden|keyboard|screenSize|smallestScreenSize|screenLayout|uiMode"
            android:launchMode="singleTask" />

        <service
            android:name=".DownloadService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="\${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
    </application>

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
</manifest>
`,
);

fs.writeFileSync(
  path.join(androidDir, "app", "src", "main", "res", "xml", "file_paths.xml"),
  `<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <external-path name="my_images" path="." />
    <external-files-path name="external_files" path="." />
    <files-path name="files" path="." />
    <cache-path name="my_cache_images" path="." />
</paths>
`,
);

const colorsPath = path.join(androidDir, "app", "src", "main", "res", "values", "colors.xml");
if (!fs.existsSync(colorsPath)) {
  fs.writeFileSync(
    colorsPath,
    `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="colorPrimary">#0a0a0a</color>
    <color name="colorPrimaryDark">#0a0a0a</color>
    <color name="colorAccent">#8256d0</color>
</resources>
`,
  );
}

console.log("Patched Android project");
