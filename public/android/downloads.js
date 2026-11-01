/* Video downloads are not part of this app. The bridge methods stay so the
 * desktop preload surface still matches, and every call is a no-op. */
(function () {
  var api = {
    getDownloads: function () {
      return [];
    },
    save: function () {},
    runningCount: function () {
      return 0;
    },
    async checkDownloader() {
      return { exists: false, token: null, removed: true };
    },
    async runDownload() {
      return { ok: false, error: "Downloads have been removed" };
    },
    async deleteDownload() {
      return { ok: true };
    },
    async fileExists() {
      return false;
    },
    async pruneSubtitles() {
      return { ok: true, subtitlePaths: [] };
    },
    async killAll() {
      return { ok: true };
    },
    async downloadsSize() {
      return { bytes: 0 };
    },
    async deleteAll() {
      return { deleted: 0, errors: 0 };
    },
    async deleteSubtitleFile() {
      return { ok: true };
    },
  };
  window.__sbDownloads = api;
})();
