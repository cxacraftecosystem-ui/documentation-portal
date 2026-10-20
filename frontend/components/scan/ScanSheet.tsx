"use client";

/**
 * The top-bar scanner: camera, "Choose a photo" and "Type the code", then `lib/scanDispatch.ts`, then
 * one result card with its button. Opened by `TopBarScanButton` (the mobile/tablet header) and drawn
 * inline by `/scan`, so the two cannot drift. It never navigates on its own: the person presses
 * Open or Join (REQUIREMENTS_2026-10-20 §6).
 *
 * The camera uses `BarcodeDetector` where the browser has it and the bundled decoder
 * (`lib/qrDecode.ts`, the Designer Repository's, byte for byte) where it does not; a camera-less
 * browser still has the photo and typed paths. Nothing scanned is stored or written into the typed
 * box: not a join card's secret, not a payment or authenticator link, not an identity QR. The box
 * holds only what the person typed, and a scan clears it.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Camera, CameraOff, ImageUp, Keyboard, QrCode } from "lucide-react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { ButtonRow } from "@/components/ui/ButtonRow";
import { decodeQrFromFile, decodeQrFromVideoFrame } from "@/lib/qrImageDecode";
import { SCAN_COPY, dispatchScan, type ScanOutcome } from "@/lib/scanDispatch";
import { runScanAction, scanServices } from "@/lib/scanServices";

const FRAME_INTERVAL_MS = 220;

/** Frame refusals that mean "keep looking"; anything else is a property of the symbol itself. */
const KEEP_LOOKING = new Set(["NO_SYMBOL", "UNREADABLE", "DATA_UNRECOVERABLE"]);

const POSITIVE = new Set(["OPEN", "REDEEM_JOIN_CARD", "JOIN_SESSION", "ADD_FROM_PEER", "JOIN_WORKSHOP", "POINTER"]);

function cameraRefusal(error: unknown): string {
  const name = error instanceof Error ? error.name : "";
  if (name === "NotAllowedError" || name === "SecurityError") return `Camera blocked. ${SCAN_COPY.choosePhoto} or type the code.`;
  if (name === "NotFoundError" || name === "OverconstrainedError") return `No camera found. ${SCAN_COPY.choosePhoto} or type the code.`;
  return `Couldn't open the camera. ${SCAN_COPY.choosePhoto} or type the code.`;
}

/** The dispatcher's answer, with its one button. */
export function ScanOutcomeCard({
  outcome,
  busy,
  onPress
}: {
  outcome: ScanOutcome;
  busy: boolean;
  onPress: (outcome: ScanOutcome) => void;
}) {
  const positive = POSITIVE.has(outcome.kind);
  return (
    <div
      data-scan-outcome={outcome.kind}
      className={
        positive
          ? "panel grid gap-3 p-4"
          : "grid gap-3 rounded-lg border border-amber-500 bg-amber-100 p-4 text-amber-800"
      }
    >
      <div className="min-w-0">
        <p className={positive ? "text-sm font-semibold text-ink-900" : "text-sm font-medium"}>{outcome.title}</p>
        {outcome.detail ? <p className="mt-1 text-xs text-ink-muted">{outcome.detail}</p> : null}
      </div>
      {outcome.action ? (
        <ButtonRow fill>
          <button type="button" className="field-button" onClick={() => onPress(outcome)} disabled={busy}>
            {outcome.action.label}
          </button>
        </ButtonRow>
      ) : null}
    </div>
  );
}

/** The scanner's body, for the dialog or a page. `onDone` runs when the person leaves for a result. */
export function ScanPanel({ onDone }: { onDone?: () => void }) {
  const router = useRouter();
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const handlingRef = useRef(false);
  /** Bumped by every start and stop: a camera request or a frame loop from an older one gives up. */
  const generationRef = useRef(0);
  const startingRef = useRef(false);
  const fileRef = useRef<HTMLInputElement | null>(null);

  const [scanning, setScanning] = useState(false);
  const [starting, setStarting] = useState(false);
  const [typed, setTyped] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<ScanOutcome | null>(null);

  const stopCamera = useCallback(() => {
    generationRef.current += 1;
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = null;
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
    setScanning(false);
  }, []);

  useEffect(() => stopCamera, [stopCamera]);

  useEffect(() => {
    const onHidden = () => {
      if (document.visibilityState === "hidden") stopCamera();
    };
    document.addEventListener("visibilitychange", onHidden);
    return () => document.removeEventListener("visibilitychange", onHidden);
  }, [stopCamera]);

  const handle = useCallback(async (raw: string) => {
    setProblem(null);
    setOutcome(null);
    setBusy(true);
    try {
      setOutcome(await dispatchScan(raw, scanServices()));
    } finally {
      setBusy(false);
    }
  }, []);

  /** A scanned payload is answered, never shown: the typed box is cleared, not filled. */
  const fromScanner = useCallback(
    (raw: string) => {
      setTyped("");
      void handle(raw);
    },
    [handle]
  );

  const startCamera = useCallback(async () => {
    // A second tap while the browser is still asking would open a second stream nobody stops.
    if (startingRef.current) return;
    setProblem(null);
    setOutcome(null);
    if (!navigator.mediaDevices?.getUserMedia) {
      setProblem(`No camera here. ${SCAN_COPY.choosePhoto} or type the code.`);
      return;
    }
    stopCamera();
    handlingRef.current = false;
    const generation = generationRef.current;
    startingRef.current = true;
    setStarting(true);
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: "environment" } } });
    } catch (error) {
      if (generation === generationRef.current) setProblem(cameraRefusal(error));
      return;
    } finally {
      startingRef.current = false;
      setStarting(false);
    }
    if (generation !== generationRef.current) {
      // Stopped, closed or restarted while the browser was asking: this stream is nobody's.
      stream.getTracks().forEach((track) => track.stop());
      return;
    }
    streamRef.current = stream;
    const video = videoRef.current;
    if (!video) {
      stopCamera();
      return;
    }
    video.srcObject = stream;
    try {
      await video.play();
    } catch {
      // Autoplay refusal is not fatal; frames still arrive.
    }
    setScanning(true);

    const readFrame = async () => {
      if (generation !== generationRef.current) return;
      timerRef.current = null;
      const element = videoRef.current;
      if (!element || !streamRef.current || handlingRef.current) return;
      let read: Awaited<ReturnType<typeof decodeQrFromVideoFrame>> = null;
      try {
        read = await decodeQrFromVideoFrame(element);
      } catch {
        read = null;
      }
      if (generation !== generationRef.current) return;
      if (!read || (!read.ok && KEEP_LOOKING.has(read.reason))) {
        if (streamRef.current && generation === generationRef.current) timerRef.current = setTimeout(() => void readFrame(), FRAME_INTERVAL_MS);
        return;
      }
      handlingRef.current = true;
      stopCamera();
      if (!read.ok) {
        // A symbol this app does not print (an Aadhaar Secure QR is one): one sentence, nothing kept.
        setOutcome({ kind: "REFUSED", title: SCAN_COPY.unknown });
        return;
      }
      fromScanner(read.text);
    };
    void readFrame();
  }, [fromScanner, stopCamera]);

  async function readFile(file: File) {
    stopCamera();
    setProblem(null);
    setOutcome(null);
    setBusy(true);
    let text: string | null = null;
    try {
      const read = await decodeQrFromFile(file, file.name);
      if (read.ok) text = read.text;
      else if (KEEP_LOOKING.has(read.reason) || read.reason === "FILE_UNREADABLE" || read.reason === "NO_IMAGE_SUPPORT") setProblem(read.message);
      else setOutcome({ kind: "REFUSED", title: SCAN_COPY.unknown });
    } catch {
      setProblem("That picture couldn't be read.");
    } finally {
      setBusy(false);
    }
    if (text !== null) fromScanner(text);
  }

  async function press(current: ScanOutcome) {
    if (!current.action) return;
    setBusy(true);
    try {
      const next = await runScanAction(current.action);
      if ("navigate" in next) {
        if (next.external) {
          window.open(next.navigate, "_blank", "noopener,noreferrer");
        } else {
          onDone?.();
          router.push(next.navigate);
        }
        return;
      }
      setOutcome(next.outcome);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-4" data-testid="scan-panel">
      <div className="relative overflow-hidden rounded-lg border border-line-200 bg-ink-900">
        <video
          ref={videoRef}
          muted
          playsInline
          aria-label="Camera preview"
          className={scanning ? "block aspect-square w-full object-cover sm:aspect-video" : "hidden"}
        />
        {scanning ? (
          <div aria-hidden className="pointer-events-none absolute inset-0 grid place-items-center">
            <div className="aspect-square w-3/5 max-w-64 rounded-xl border-2 border-white/90" />
          </div>
        ) : (
          <div className="grid aspect-[2/1] place-items-center p-4">
            <QrCode className="h-10 w-10 text-white/70" aria-hidden />
          </div>
        )}
      </div>

      <ButtonRow>
        {scanning ? (
          <button type="button" className="field-button-secondary" onClick={stopCamera}>
            <CameraOff className="h-4 w-4" aria-hidden />
            {SCAN_COPY.stopCamera}
          </button>
        ) : (
          <button type="button" className="field-button" onClick={() => void startCamera()} disabled={busy || starting} aria-busy={starting || undefined}>
            <Camera className="h-4 w-4" aria-hidden />
            {SCAN_COPY.useCamera}
          </button>
        )}
        <button type="button" className="field-button-secondary" onClick={() => fileRef.current?.click()} disabled={busy}>
          <ImageUp className="h-4 w-4" aria-hidden />
          {SCAN_COPY.choosePhoto}
        </button>
      </ButtonRow>
      <input
        ref={fileRef}
        type="file"
        accept="image/*"
        className="hidden"
        data-testid="scan-file"
        onChange={(event) => {
          const file = event.target.files?.[0];
          event.target.value = "";
          if (file) void readFile(file);
        }}
      />

      <form
        className="grid gap-1.5"
        onSubmit={(event) => {
          event.preventDefault();
          if (!typed.trim() || busy) return;
          stopCamera();
          void handle(typed);
        }}
      >
        <label htmlFor="scan-typed" className="field-label flex items-center gap-1.5">
          <Keyboard className="h-3.5 w-3.5" aria-hidden />
          {SCAN_COPY.typeCode}
        </label>
        <div className="flex gap-2">
          <input
            id="scan-typed"
            className="field-input min-w-0 flex-1 font-mono"
            value={typed}
            onChange={(event) => {
              setTyped(event.target.value);
              setOutcome(null);
              setProblem(null);
            }}
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            placeholder="Session code or card"
          />
          <button type="submit" className="field-button" disabled={!typed.trim() || busy}>
            {SCAN_COPY.lookUp}
          </button>
        </div>
      </form>

      <div role="status" aria-live="polite" className="grid gap-3">
        {busy ? <p className="text-sm text-ink-muted">Checking…</p> : null}
        {problem ? (
          <p className="rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm text-amber-800">{problem}</p>
        ) : null}
        {outcome && !busy ? <ScanOutcomeCard outcome={outcome} busy={busy} onPress={(o) => void press(o)} /> : null}
      </div>
    </div>
  );
}

/** The dialog the top bar's scan button opens. */
export function ScanSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <FieldDialog
      open={open}
      onClose={onClose}
      title={SCAN_COPY.scanACode}
      icon={<QrCode className="h-5 w-5" aria-hidden />}
      className="max-w-lg"
    >
      {open ? <ScanPanel onDone={onClose} /> : null}
    </FieldDialog>
  );
}
