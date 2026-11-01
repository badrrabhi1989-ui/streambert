import { useEffect, useState } from "react";

/**
 * Shown on Android. The TV displays a one-time code and QR; the phone opens
 * a page served by the app on the local network and pastes the keys.
 */
export default function PhoneKeyPairing({ focusable = false }) {
  const android = typeof window !== "undefined" && !!window.__STREAMBERT_ANDROID__;
  const [session, setSession] = useState(null);
  const [qr, setQr] = useState("");
  const [error, setError] = useState("");
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!session?.url) {
      setQr("");
      return;
    }
    let cancel = false;
    import("qrcode")
      .then((mod) => mod.default.toDataURL(session.url + "#" + session.code, {
        width: 320,
        margin: 1,
        errorCorrectionLevel: "M",
        color: { dark: "#111111", light: "#ffffff" },
      }))
      .then((url) => {
        if (!cancel) setQr(url);
      })
      .catch(() => {});
    return () => {
      cancel = true;
    };
  }, [session]);

  useEffect(() => {
    const onSaved = () => {
      setSaved(true);
      setSession(null);
      window.electron?.stopPhonePairing?.();
    };
    window.addEventListener("streambert:phone-keys", onSaved);
    return () => window.removeEventListener("streambert:phone-keys", onSaved);
  }, []);

  useEffect(() => {
    return () => {
      window.electron?.stopPhonePairing?.();
    };
  }, []);

  if (!android) return null;

  const start = async () => {
    setBusy(true);
    setError("");
    setSaved(false);
    try {
      const res = await window.electron.startPhonePairing();
      if (!res || res.ok === false) {
        setError(res?.error || "Could not start pairing");
        setSession(null);
      } else {
        setSession(res);
      }
    } catch (e) {
      setError(e.message || "Could not start pairing");
    } finally {
      setBusy(false);
    }
  };

  const cancel = () => {
    setSession(null);
    window.electron?.stopPhonePairing?.();
  };

  return (
    <div className="phone-pair">
      <div className="phone-pair-title">Enter keys from your phone</div>
      <p className="phone-pair-copy">
        The TV shows a one-time code. Scan it or open the address on a phone
        connected to the same Wi-Fi, paste the TMDB token (and Wyzie or SubDL
        keys if you use them), then the page closes itself.
      </p>
      {!session && (
        <button className={`btn btn-primary${focusable ? " tv-item" : ""}`} type="button" tabIndex={0} onClick={start} disabled={busy}>
          {busy ? "Starting…" : "Show pairing code"}
        </button>
      )}
      {session && (
        <div className="phone-pair-live">
          {qr && <img className="phone-pair-qr" src={qr} alt="Pairing QR code" />}
          <div className="phone-pair-code" aria-label="Pairing code">{session.code}</div>
          <div className="phone-pair-url">{session.url}</div>
          <p className="phone-pair-copy">
            The code works once and the page stops after a few minutes, or when you cancel.
          </p>
          <button className={`btn btn-ghost${focusable ? " tv-item" : ""}`} type="button" tabIndex={0} onClick={cancel}>
            Cancel
          </button>
        </div>
      )}
      {saved && <div className="phone-pair-saved">Saved from your phone.</div>}
      {error && <div className="phone-pair-error">{error}</div>}
    </div>
  );
}
