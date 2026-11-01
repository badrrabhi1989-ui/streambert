/* Streambert Android bootstrap.
 * Runs before the React bundle. In Electron and in a normal browser this file
 * returns immediately so the desktop app is unchanged.
 * On Android, native code injects window.__SB_BOOT and window.sbNative before
 * this script (see StreambertEngine). The other bridge scripts are then loaded
 * synchronously so window.electron exists before any app module evaluates
 * isElectron / secureStorage.
 */
(function () {
  if (window.electron) return;
  if (!window.__SB_BOOT || !window.sbNative) return;

  window.__STREAMBERT_ANDROID__ = true;
  var files = [
    "webview.js",
    "hianime.js",
    "subtitles.js",
    "downloads.js",
    "electron.js",
  ];
  for (var i = 0; i < files.length; i++) {
    document.write(
      '<script src="./android/' + files[i] + '"><\/script>',
    );
  }
})();
