import { useCallback, useEffect, useRef, useState, type FormEvent, type PointerEvent as ReactPointerEvent } from "react";
import { createPortal, flushSync } from "react-dom";
import {
  ChevronDown,
  ChevronUp,
  Download,
  FileX,
  Lock,
  Minus,
  PanelLeft,
  Plus,
  Search,
  X,
} from "lucide-react";
import type { PDFDocumentProxy } from "pdfjs-dist";
import type { EventBus, PDFFindController, PDFViewer as PdfJsViewer } from "pdfjs-dist/web/pdf_viewer.mjs";
import "pdfjs-dist/web/pdf_viewer.css";
import {
  PDF_DAMAGED_MESSAGE,
  PDF_DAMAGED_TITLE,
  PDF_LOADING,
  PDF_NO_RESULTS,
  PDF_PROTECTED_MESSAGE,
  PDF_PROTECTED_TITLE,
  PDF_SEARCH_PLACEHOLDER,
  PDF_WRONG_PASSWORD,
  pdfPageSubtitle,
} from "../files";
import { isOpenableUrl } from "../links";
import { loadPdfViewer, openPdfBlob, type PdfLib } from "../media/pdfjs";
import { lastPdfPage, pdfSidebarChoice, rememberPdfPage, rememberPdfSidebar } from "../media/pdfMemory";
import { renderPdfCard } from "../media/pdfPreview";
import { FileName } from "./FileBubble";
import { PdfPagesList, PdfThumbnailCache } from "./PdfPages";

/** Pages are at most this wide at 100 % (fit width), with this margin around them. */
const MAX_PAGE_WIDTH = 920;
const PAGE_MARGIN = 12;
/** Zoom, relative to fit width (§10.2: 1× to 6×). */
const MIN_ZOOM = 1;
const MAX_ZOOM = 6;
/** The header's zoom steps, in percent of fit width. */
const ZOOM_STOPS = [100, 125, 150, 200, 250, 300, 400, 500, 600];
/** A double tap toggles between fit width and this. */
const TAP_ZOOM = 2.5;
const DOUBLE_TAP_MS = 300;
/** Pinch and Ctrl/⌘ + wheel zoom with CSS first; the pages are drawn sharp once it settles. */
const SETTLE_MS = 350;
/** The pages sidebar (instead of the drawer) from here up (§10.2: the web from 900 px). */
const WIDE_QUERY = "(min-width: 900px)";
/** A swipe this far to the left closes the pages drawer. */
const DRAWER_SWIPE = 56;

type Status = "loading" | "password" | "ready" | "damaged";
type Matches = { current: number; total: number };

function clamp(value: number, low: number, high: number): number {
  return Math.min(high, Math.max(low, value));
}

function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => window.matchMedia?.(query).matches ?? false);
  useEffect(() => {
    const mq = window.matchMedia?.(query);
    if (!mq) return;
    const onChange = () => setMatches(mq.matches);
    onChange();
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, [query]);
  return matches;
}

/**
 * pdf.js's viewer asks its `L10n` for its own English strings; Shroud labels what the reader
 * meets itself (page landmarks: `Page {n} of {count}`), so this one translates nothing and never
 * fetches a locale file.
 */
const silentL10n = {
  getLanguage: () => "en-us",
  getDirection: () => "ltr",
  get: async () => "",
  translate: async () => undefined,
  translateOnce: async () => undefined,
  destroy: async () => undefined,
  pause: () => undefined,
  resume: () => undefined,
};

/** Height ÷ width of every page, as pdf.js knows them (pages not yet read share page 1's). */
function pageRatios(viewer: PdfJsViewer): number[] {
  const out: number[] = [];
  for (let i = 0; i < viewer.pagesCount; i++) {
    const vp = (viewer.getPageView(i) as { viewport?: { width: number; height: number } } | undefined)?.viewport;
    out.push(vp && vp.width > 0 ? vp.height / vp.width : Math.SQRT2);
  }
  return out;
}

/**
 * Shroud's own PDF viewer (docs/file-sharing.md §10.2), built on pdf.js's viewer components:
 * pages scroll continuously, only the ones in view (and a page either side) are drawn, at screen
 * scale × zoom, with a selectable text layer and the document's links. pdf.js reads the
 * decrypted `Blob` in ranges in its worker; nothing leaves the browser.
 */
export function PdfViewer({
  messageId,
  name,
  blob,
  onDownload,
  onOpenLink,
  onClose,
}: {
  messageId: string;
  name: string;
  blob: Blob;
  onDownload: () => void;
  /** The thread's way of opening a link from a message (http, https and mailto only). */
  onOpenLink: (url: string) => void;
  onClose: () => void;
}) {
  const wide = useMediaQuery(WIDE_QUERY);
  const [status, setStatus] = useState<Status>("loading");
  const [pageCount, setPageCount] = useState<number | null>(null);
  const [page, setPage] = useState(1);
  const [zoom, setZoom] = useState(1);
  /** The first page is on the canvas: the spinner goes. */
  const [painted, setPainted] = useState(false);
  const [password, setPassword] = useState("");
  const [passwordWrong, setPasswordWrong] = useState(false);
  const [passwordBusy, setPasswordBusy] = useState(false);
  const [searchOpen, setSearchOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [matches, setMatches] = useState<Matches | null>(null);
  const [searching, setSearching] = useState(false);
  /** Pages of the parsed document; known before pdf.js lays them out, so the sidebar is there first. */
  const [docPages, setDocPages] = useState<number | null>(null);
  const [cache, setCache] = useState<PdfThumbnailCache | null>(null);
  const [ratios, setRatios] = useState<number[]>([]);
  /** The reader's sidebar choice this session; null: open for more than one page. */
  const [sidebarPref, setSidebarPref] = useState<boolean | null>(() => pdfSidebarChoice());
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [drawerDrag, setDrawerDrag] = useState(0);
  const drawerGesture = useRef<{ id: number; x: number; y: number; dragging: boolean } | null>(null);

  const dialog = useRef<HTMLDivElement>(null);
  const container = useRef<HTMLDivElement>(null);
  const viewerEl = useRef<HTMLDivElement>(null);
  const searchField = useRef<HTMLInputElement>(null);
  const passwordField = useRef<HTMLInputElement>(null);
  const engine = useRef<{
    lib: PdfLib;
    viewer: PdfJsViewer;
    eventBus: EventBus;
    find: PDFFindController;
    doc: PDFDocumentProxy | null;
  } | null>(null);
  const passwordReply = useRef<((password: string) => void) | null>(null);
  /** CSS px width of a page at pdf.js scale 1; fit width = available ÷ this. */
  const unitWidth = useRef(0);
  const fitScale = useRef(1);
  const zoomRef = useRef(1);
  const pinch = useRef({ factor: 1 });
  /** A re-fit is moving the scale: the zoom (relative to fit) stays what it was. */
  const refitting = useRef(false);
  const latest = useRef({ onClose, onOpenLink, onDownload, messageId });
  latest.current = { onClose, onOpenLink, onDownload, messageId };

  /** The scale fit width means here: the page at most 920 wide, 12 from the edges. */
  const measureFit = useCallback(() => {
    const node = container.current;
    if (!node || !unitWidth.current) return fitScale.current;
    const available = Math.min(MAX_PAGE_WIDTH, Math.max(120, node.clientWidth - 2 * PAGE_MARGIN));
    fitScale.current = available / unitWidth.current;
    return fitScale.current;
  }, []);

  /** Sets the zoom (relative to fit width) at once, drawing the pages for it. */
  const zoomTo = useCallback((next: number) => {
    const viewer = engine.current?.viewer;
    if (!viewer?.pdfDocument) return;
    const z = clamp(next, MIN_ZOOM, MAX_ZOOM);
    zoomRef.current = z;
    viewer.currentScale = fitScale.current * z;
    setZoom(z);
  }, []);

  /**
   * Pinch and Ctrl/⌘ + wheel: the pages scale with CSS around `origin` (client px) and are drawn
   * again at the new scale once the gesture settles. pdf.js rounds scales to 0.01, so tiny steps
   * accumulate until they move it.
   */
  const pinchBy = useCallback((factor: number, origin: [number, number]) => {
    const viewer = engine.current?.viewer;
    const node = container.current;
    if (!viewer?.pdfDocument || !node) return;
    pinch.current.factor *= factor;
    const current = viewer.currentScale;
    const target = clamp(current * pinch.current.factor, fitScale.current * MIN_ZOOM, fitScale.current * MAX_ZOOM);
    if (Math.round(target * 100) === Math.round(current * 100)) {
      if (target === current) pinch.current.factor = 1;
      return;
    }
    pinch.current.factor = 1;
    const rect = node.getBoundingClientRect();
    // pdf.js measures the origin from the container's offset; hand it client px in that frame.
    const x = origin[0] - rect.left + node.offsetLeft;
    const y = origin[1] - rect.top + node.offsetTop;
    viewer.updateScale({ scaleFactor: target / current, origin: [x, y], drawingDelay: SETTLE_MS });
  }, []);

  const stepZoom = useCallback(
    (direction: 1 | -1) => {
      const percent = Math.round(zoomRef.current * 100);
      const stop =
        direction > 0 ? ZOOM_STOPS.find((s) => s > percent + 1) : [...ZOOM_STOPS].reverse().find((s) => s < percent - 1);
      zoomTo((stop ?? (direction > 0 ? ZOOM_STOPS[ZOOM_STOPS.length - 1] : ZOOM_STOPS[0])) / 100);
    },
    [zoomTo],
  );

  /**
   * Jumps to a page. pdf.js scrolls the page's top edge to the very top; the 12 px gap above it
   * stays in view, as it does between pages.
   */
  const goToPage = useCallback((number: number) => {
    const viewer = engine.current?.viewer;
    const node = container.current;
    if (!viewer?.pdfDocument || !node) return;
    viewer.currentPageNumber = clamp(Math.round(number), 1, viewer.pagesCount);
    node.scrollTop = Math.max(0, node.scrollTop - PAGE_MARGIN);
  }, []);

  /* --------------------------------------------------------------------- load the document */
  useEffect(() => {
    let cancelled = false;
    const abort = new AbortController();
    let task: ReturnType<typeof openPdfBlob> | null = null;
    let viewer: PdfJsViewer | null = null;
    let thumbs: PdfThumbnailCache | null = null;

    void (async () => {
      let parts: Awaited<ReturnType<typeof loadPdfViewer>>;
      try {
        parts = await loadPdfViewer();
      } catch {
        if (!cancelled) setStatus("damaged");
        return;
      }
      if (cancelled || !container.current || !viewerEl.current) return;
      const { lib, viewer: components } = parts;

      /* Links: a page of this document jumps there; http, https and mailto open the way a link
         in a message does; anything else does nothing. */
      class ShroudLinkService extends components.PDFLinkService {
        override addLinkAttributes(link: HTMLAnchorElement, url: string): void {
          if (!isOpenableUrl(url)) {
            link.removeAttribute("href");
            link.removeAttribute("title");
            link.onclick = () => false;
            return;
          }
          link.href = url;
          link.title = url.toLowerCase().startsWith("mailto:") ? url.slice("mailto:".length) : url;
          link.target = "_blank";
          link.rel = "noopener noreferrer nofollow";
          link.onclick = (event) => {
            event.preventDefault();
            latest.current.onOpenLink(url);
            return false;
          };
        }
      }

      const eventBus = new components.EventBus();
      const linkService = new ShroudLinkService({ eventBus });
      const find = new components.PDFFindController({ eventBus, linkService, updateMatchesCountOnProgress: true });
      viewer = new components.PDFViewer({
        container: container.current,
        viewer: viewerEl.current,
        eventBus,
        linkService,
        findController: find,
        removePageBorders: true,
        // Forms drawn as they look, not as editable fields; no annotation editing.
        annotationMode: lib.AnnotationMode.ENABLE,
        annotationEditorMode: lib.AnnotationEditorType.DISABLE,
        l10n: silentL10n as never,
        enablePermissions: false,
        enableAutoLinking: true,
      });
      linkService.setViewer(viewer);
      engine.current = { lib, viewer, eventBus, find, doc: null };
      const v = viewer;

      eventBus.on("pagesinit", () => {
        const count = v.pagesCount;
        for (let i = 0; i < count; i++) {
          v.getPageView(i)?.div?.setAttribute("aria-label", `Page ${i + 1} of ${count}`);
        }
        measureFit();
        v.currentScale = fitScale.current * zoomRef.current;
        const last = lastPdfPage(latest.current.messageId);
        if (last && last > 1 && last <= count) goToPage(last);
        else if (container.current) container.current.scrollTop = 0;
        setPage(v.currentPageNumber);
        setPageCount(count);
        setRatios(pageRatios(v));
        setStatus("ready");
      });
      // Every page's size is read: the list rows take their own aspects.
      eventBus.on("pagesloaded", () => setRatios(pageRatios(v)));
      eventBus.on("pagechanging", ({ pageNumber }: { pageNumber: number }) => {
        setPage(pageNumber);
        rememberPdfPage(latest.current.messageId, pageNumber);
      });
      let cardDrawn = false;
      eventBus.on("pagerendered", () => {
        setPainted(true);
        // Thumbnails wait for the first page, so they never hold it up.
        thumbs?.start();
        const doc = engine.current?.doc;
        if (!cardDrawn && doc) {
          cardDrawn = true;
          // The bubble's local render (§10.1), from the document that is open anyway.
          void renderPdfCard(doc, latest.current.messageId).catch(() => undefined);
        }
      });
      eventBus.on("scalechanging", ({ scale }: { scale: number }) => {
        if (!fitScale.current || refitting.current) return;
        const z = scale / fitScale.current;
        zoomRef.current = z;
        setZoom(z);
      });
      eventBus.on("updatefindmatchescount", ({ matchesCount }: { matchesCount: Matches }) => {
        setMatches(matchesCount);
      });
      eventBus.on(
        "updatefindcontrolstate",
        ({ state, matchesCount }: { state: number; matchesCount: Matches }) => {
          setSearching(state === components.FindState.PENDING);
          setMatches(matchesCount);
        },
      );

      /* Pinch on touch screens: pdf.js's own touch tracker, feeding the same zoom as the wheel. */
      new lib.TouchManager({
        container: container.current,
        isPinchingDisabled: () => false,
        onPinching: (origin: [number, number], previous: number, distance: number) => {
          if (previous > 0) pinchBy(distance / previous, origin);
        },
        signal: abort.signal,
      });

      task = openPdfBlob(lib, blob);
      task.onPassword = (reply: (password: string) => void, reason: number) => {
        if (cancelled) return;
        passwordReply.current = reply;
        setPasswordWrong(reason === lib.PasswordResponses.INCORRECT_PASSWORD);
        setPasswordBusy(false);
        setStatus("password");
      };
      let doc: PDFDocumentProxy;
      try {
        doc = await task.promise;
      } catch {
        if (!cancelled) setStatus("damaged");
        return;
      }
      if (cancelled) return;
      passwordReply.current = null;
      engine.current.doc = doc;
      let firstRatio = Math.SQRT2;
      try {
        const first = await doc.getPage(1);
        const natural = first.getViewport({ scale: 1 });
        unitWidth.current = natural.width * lib.PixelsPerInch.PDF_TO_CSS_UNITS;
        firstRatio = natural.height / natural.width;
      } catch {
        if (!cancelled) setStatus("damaged");
        return;
      }
      if (cancelled) return;
      const cacheForDoc = new PdfThumbnailCache(doc);
      thumbs = cacheForDoc;
      // The sidebar takes its place first (committed now; pdf.js measures the stage when it lays
      // out the pages), so the pages are fitted to the stage once.
      flushSync(() => {
        setCache(cacheForDoc);
        setRatios(Array.from({ length: doc.numPages }, () => firstRatio));
        setDocPages(doc.numPages);
      });
      v.setDocument(doc);
      linkService.setDocument(doc);
    })();

    return () => {
      cancelled = true;
      abort.abort();
      thumbs?.close();
      try {
        viewer?.setDocument(null as never);
      } catch {
        /* torn down mid-load */
      }
      engine.current = null;
      passwordReply.current = null;
      // Destroys the document and its worker: the decrypted bytes it read go with them.
      void task?.destroy();
    };
  }, [blob, measureFit, pinchBy, goToPage]);

  /*
   * Fit width follows the stage (the window, the sidebar opening or closing), keeping the zoom.
   * While the size moves (the sidebar's 220 ms), pdf.js scales the pages with CSS and draws them
   * once it settles.
   */
  useEffect(() => {
    const node = container.current;
    if (!node) return;
    let frame = 0;
    const observer = new ResizeObserver(() => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        const viewer = engine.current?.viewer;
        if (!viewer?.pdfDocument || !unitWidth.current) return;
        const before = fitScale.current;
        measureFit();
        if (Math.abs(before - fitScale.current) <= 0.001) return;
        const target = fitScale.current * zoomRef.current;
        refitting.current = true;
        try {
          viewer.updateScale({ scaleFactor: target / viewer.currentScale, drawingDelay: SETTLE_MS });
        } finally {
          refitting.current = false;
        }
      });
    });
    observer.observe(node);
    return () => {
      cancelAnimationFrame(frame);
      observer.disconnect();
    };
  }, [measureFit]);

  /* Ctrl/⌘ + wheel and trackpad pinch (which arrives as ctrl + wheel) zoom; the page never does. */
  useEffect(() => {
    const root = dialog.current;
    if (!root) return;
    const onWheel = (event: WheelEvent) => {
      if (!(event.ctrlKey || event.metaKey)) return;
      event.preventDefault();
      const node = container.current;
      if (!node) return;
      const delta = event.deltaMode === 1 ? event.deltaY * 16 : event.deltaY;
      const factor = clamp(Math.exp(-delta * 0.01), 0.8, 1.25);
      const rect = node.getBoundingClientRect();
      const inside =
        event.clientX >= rect.left && event.clientX <= rect.right && event.clientY >= rect.top && event.clientY <= rect.bottom;
      pinchBy(factor, inside ? [event.clientX, event.clientY] : [rect.left + rect.width / 2, rect.top + rect.height / 2]);
    };
    root.addEventListener("wheel", onWheel, { passive: false });
    return () => root.removeEventListener("wheel", onWheel);
  }, [pinchBy]);

  /* A double tap on a touch screen toggles fit width ↔ 2.5× around the tap. */
  useEffect(() => {
    const node = container.current;
    if (!node) return;
    let last: { at: number; x: number; y: number } | null = null;
    let down: { x: number; y: number; id: number } | null = null;
    let fingers = 0;
    const onDown = (event: PointerEvent) => {
      if (event.pointerType !== "touch") return;
      fingers += 1;
      down = fingers === 1 ? { x: event.clientX, y: event.clientY, id: event.pointerId } : null;
    };
    const onUp = (event: PointerEvent) => {
      if (event.pointerType !== "touch") return;
      fingers = Math.max(0, fingers - 1);
      if (!down || down.id !== event.pointerId) return;
      const moved = Math.hypot(event.clientX - down.x, event.clientY - down.y) > 10;
      down = null;
      if (moved || (event.target as Element | null)?.closest?.("a")) {
        last = null;
        return;
      }
      const now = performance.now();
      if (last && now - last.at < DOUBLE_TAP_MS && Math.hypot(event.clientX - last.x, event.clientY - last.y) < 40) {
        last = null;
        const viewer = engine.current?.viewer;
        if (!viewer?.pdfDocument) return;
        const target = zoomRef.current > 1.05 ? MIN_ZOOM : TAP_ZOOM;
        const rect = node.getBoundingClientRect();
        viewer.updateScale({
          scaleFactor: (fitScale.current * target) / viewer.currentScale,
          origin: [event.clientX - rect.left + node.offsetLeft, event.clientY - rect.top + node.offsetTop],
        });
        return;
      }
      last = { at: now, x: event.clientX, y: event.clientY };
    };
    const onCancel = (event: PointerEvent) => {
      if (event.pointerType !== "touch") return;
      fingers = Math.max(0, fingers - 1);
      down = null;
    };
    node.addEventListener("pointerdown", onDown);
    node.addEventListener("pointerup", onUp);
    node.addEventListener("pointercancel", onCancel);
    return () => {
      node.removeEventListener("pointerdown", onDown);
      node.removeEventListener("pointerup", onUp);
      node.removeEventListener("pointercancel", onCancel);
    };
  }, []);

  /* ------------------------------------------------------------------------------- search */
  const dispatchFind = useCallback((type: "" | "again", text: string, previous = false) => {
    engine.current?.eventBus.dispatch("find", {
      source: null,
      type,
      query: text,
      caseSensitive: false,
      entireWord: false,
      highlightAll: true,
      findPrevious: previous,
      // Matching ignores case and diacritics (§10.2).
      matchDiacritics: false,
    });
  }, []);

  const openSearch = useCallback(() => {
    if (status !== "ready") return;
    setSearchOpen(true);
    requestAnimationFrame(() => {
      searchField.current?.focus();
      searchField.current?.select();
    });
  }, [status]);

  const closeSearch = useCallback(() => {
    setSearchOpen(false);
    setQuery("");
    setMatches(null);
    setSearching(false);
    engine.current?.eventBus.dispatch("findbarclose", { source: null });
    container.current?.focus({ preventScroll: true });
  }, []);

  /* -------------------------------------------------------------------------------- pages */
  const sidebarOpen = wide && cache != null && (sidebarPref ?? (docPages ?? 0) > 1);
  const pagesShown = wide ? sidebarOpen : drawerOpen;

  const togglePages = useCallback(() => {
    if (!cache) return;
    if (wide) {
      const next = !sidebarOpen;
      setSidebarPref(next);
      rememberPdfSidebar(next);
    } else {
      setDrawerDrag(0);
      setDrawerOpen((open) => !open);
    }
  }, [cache, wide, sidebarOpen]);

  const closeDrawer = useCallback(() => {
    setDrawerOpen(false);
    setDrawerDrag(0);
    drawerGesture.current = null;
  }, []);

  /* The drawer belongs to narrow windows; widening the window puts the sidebar in its place. */
  useEffect(() => {
    if (wide) closeDrawer();
  }, [wide, closeDrawer]);

  const pickPage = useCallback(
    (number: number) => {
      goToPage(number);
      if (!wide) closeDrawer();
      container.current?.focus({ preventScroll: true });
    },
    [wide, goToPage, closeDrawer],
  );

  /* A swipe to the left closes the drawer; it follows the finger on the way. */
  function onDrawerDown(event: ReactPointerEvent<HTMLElement>) {
    if (event.pointerType === "mouse") return;
    drawerGesture.current = { id: event.pointerId, x: event.clientX, y: event.clientY, dragging: false };
  }
  function onDrawerMove(event: ReactPointerEvent<HTMLElement>) {
    const g = drawerGesture.current;
    if (!g || g.id !== event.pointerId) return;
    const dx = event.clientX - g.x;
    const dy = event.clientY - g.y;
    if (!g.dragging) {
      if (Math.abs(dx) < 10 || Math.abs(dx) < Math.abs(dy) * 1.2 || dx > 0) {
        if (Math.abs(dy) > 10) drawerGesture.current = null;
        return;
      }
      g.dragging = true;
      event.currentTarget.setPointerCapture(event.pointerId);
    }
    setDrawerDrag(Math.min(0, dx));
  }
  function onDrawerUp(event: ReactPointerEvent<HTMLElement>) {
    const g = drawerGesture.current;
    drawerGesture.current = null;
    if (!g?.dragging || g.id !== event.pointerId) return;
    if (event.clientX - g.x < -DRAWER_SWIPE) closeDrawer();
    else setDrawerDrag(0);
  }

  /* --------------------------------------------------------------------------------- keys */
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const mod = event.ctrlKey || event.metaKey;
      const typing = (event.target as HTMLElement | null)?.closest?.("input, textarea") != null;
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        if (searchOpen) closeSearch();
        else if (drawerOpen && !wide) closeDrawer();
        else latest.current.onClose();
        return;
      }
      if (mod && event.key.toLowerCase() === "f") {
        event.preventDefault();
        event.stopPropagation();
        openSearch();
        return;
      }
      const viewer = engine.current?.viewer;
      if (status !== "ready" || !viewer) return;
      if (mod && (event.key === "+" || event.key === "=")) {
        event.preventDefault();
        stepZoom(1);
        return;
      }
      if (mod && (event.key === "-" || event.key === "_")) {
        event.preventDefault();
        stepZoom(-1);
        return;
      }
      if (mod && event.key === "0") {
        event.preventDefault();
        zoomTo(1);
        return;
      }
      if (typing || mod || event.altKey) return;
      switch (event.key) {
        case "ArrowLeft":
        case "PageUp":
          event.preventDefault();
          goToPage(viewer.currentPageNumber - 1);
          break;
        case "ArrowRight":
        case "PageDown":
          event.preventDefault();
          goToPage(viewer.currentPageNumber + 1);
          break;
        case "Home":
          event.preventDefault();
          goToPage(1);
          break;
        case "End":
          event.preventDefault();
          goToPage(viewer.pagesCount);
          break;
      }
    };
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [searchOpen, drawerOpen, wide, status, closeSearch, openSearch, closeDrawer, stepZoom, zoomTo, goToPage]);

  /* The page underneath doesn't scroll; focus comes back where it was. */
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    container.current?.focus({ preventScroll: true });
    return () => {
      document.body.style.overflow = overflow;
      previous?.focus?.({ preventScroll: true });
    };
  }, []);

  useEffect(() => {
    if (status === "password") passwordField.current?.focus();
  }, [status, passwordWrong]);

  function submitPassword(event: FormEvent) {
    event.preventDefault();
    const reply = passwordReply.current;
    if (!reply || !password) return;
    setPasswordBusy(true);
    setPassword("");
    setStatus("loading");
    reply(password);
  }

  const ready = status === "ready";
  const subtitle =
    status === "ready" && pageCount ? pdfPageSubtitle(page, pageCount) : status === "loading" ? PDF_LOADING : "";
  const percent = Math.round(zoom * 100);
  const showPlaceholders = status === "loading" && pageCount == null;
  const showSpinner = status === "loading" || (ready && !painted);
  const countLine = !query
    ? ""
    : matches && matches.total > 0
      ? `${Math.max(1, matches.current)} of ${matches.total}`
      : searching
        ? ""
        : PDF_NO_RESULTS;

  return createPortal(
    <div className="pdf-viewer-scrim" onMouseDown={() => latest.current.onClose()}>
      <div
        ref={dialog}
        className="pdf-viewer"
        role="dialog"
        aria-modal="true"
        aria-label={name}
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className={`pdf-viewer-head${searchOpen ? " is-searching" : ""}`}>
          <button className="icon-btn" type="button" aria-label="Close" title="Close (Esc)" onClick={() => latest.current.onClose()}>
            <X size={18} />
          </button>
          {/* Pages sits right after Close, above the sidebar it opens (§10.2). */}
          <button
            className={`icon-btn pdf-pages-btn${pagesShown ? " is-on" : ""}`}
            type="button"
            aria-label="Pages"
            title="Pages"
            aria-pressed={pagesShown}
            disabled={!cache}
            onClick={togglePages}
          >
            <PanelLeft size={18} />
          </button>
          {searchOpen ? (
            <>
              <label className="pdf-search">
                <Search size={15} aria-hidden="true" />
                <input
                  ref={searchField}
                  type="search"
                  enterKeyHint="search"
                  placeholder={PDF_SEARCH_PLACEHOLDER}
                  aria-label={PDF_SEARCH_PLACEHOLDER}
                  value={query}
                  onChange={(event) => {
                    setQuery(event.target.value);
                    if (!event.target.value) setMatches(null);
                    dispatchFind("", event.target.value);
                  }}
                  onKeyDown={(event) => {
                    if (event.key !== "Enter") return;
                    event.preventDefault();
                    if (query) dispatchFind("again", query, event.shiftKey);
                  }}
                />
                <span className="pdf-search-count" aria-live="polite">
                  {countLine}
                </span>
              </label>
              <span className="pdf-head-actions">
                <button
                  className="icon-btn"
                  type="button"
                  aria-label="Previous result"
                  title="Previous result"
                  disabled={!matches?.total}
                  onClick={() => dispatchFind("again", query, true)}
                >
                  <ChevronUp size={18} />
                </button>
                <button
                  className="icon-btn"
                  type="button"
                  aria-label="Next result"
                  title="Next result"
                  disabled={!matches?.total}
                  onClick={() => dispatchFind("again", query)}
                >
                  <ChevronDown size={18} />
                </button>
                <button className="pdf-search-done" type="button" onClick={closeSearch}>
                  Done
                </button>
              </span>
            </>
          ) : (
            <>
              <div className="pdf-viewer-title">
                <h2>
                  <FileName name={name} />
                </h2>
                {subtitle ? (
                  <span className="pdf-viewer-subtitle" aria-live="polite">
                    {subtitle}
                  </span>
                ) : null}
              </div>
              <span className="pdf-head-actions">
                <button
                  className="icon-btn"
                  type="button"
                  aria-label="Search"
                  title="Search"
                  disabled={!ready}
                  onClick={openSearch}
                >
                  <Search size={18} />
                </button>
                <span className="pdf-zoom" role="group" aria-label="Zoom">
                  <button
                    className="icon-btn"
                    type="button"
                    aria-label="Zoom out"
                    title="Zoom out"
                    disabled={!ready || percent <= ZOOM_STOPS[0]}
                    onClick={() => stepZoom(-1)}
                  >
                    <Minus size={17} />
                  </button>
                  <span className="pdf-zoom-value" aria-live="polite">
                    {percent}%
                  </span>
                  <button
                    className="icon-btn"
                    type="button"
                    aria-label="Zoom in"
                    title="Zoom in"
                    disabled={!ready || percent >= ZOOM_STOPS[ZOOM_STOPS.length - 1]}
                    onClick={() => stepZoom(1)}
                  >
                    <Plus size={17} />
                  </button>
                </span>
                <button className="text-viewer-download pdf-download" type="button" aria-label="Download" onClick={onDownload}>
                  <Download size={15} aria-hidden="true" />
                  <span>Download</span>
                </button>
              </span>
            </>
          )}
        </header>

        <div className="pdf-viewer-body">
          {wide && cache ? (
            <aside className={`pdf-sidebar${sidebarOpen ? " is-open" : ""}`} aria-label="Pages" inert={!sidebarOpen}>
              <div className="pdf-sidebar-inner">
                <PdfPagesList cache={cache} ratios={ratios} current={page} active={sidebarOpen} onPick={pickPage} />
              </div>
            </aside>
          ) : null}
          <div className="pdf-viewer-stage">
            <div
              ref={container}
              className={`pdf-viewer-scroll${ready ? "" : " is-hidden"}`}
              tabIndex={-1}
            >
              <div ref={viewerEl} className="pdfViewer" />
            </div>

            {showPlaceholders ? (
              <div className="pdf-placeholders" aria-hidden="true">
                <span className="pdf-placeholder" />
                <span className="pdf-placeholder" />
              </div>
            ) : null}
            {showSpinner ? <span className="pdf-spinner" role="status" aria-label={PDF_LOADING} /> : null}

            {status === "password" ? (
              <form className="pdf-state" onSubmit={submitPassword}>
                <span className="pdf-state-glyph" aria-hidden="true">
                  <Lock size={24} />
                </span>
                <h3>{PDF_PROTECTED_TITLE}</h3>
                <p>{PDF_PROTECTED_MESSAGE}</p>
                <input
                  ref={passwordField}
                  className="field"
                  type="password"
                  autoComplete="off"
                  placeholder="Password"
                  aria-label="Password"
                  aria-invalid={passwordWrong || undefined}
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                />
                {passwordWrong ? (
                  <p className="err" role="alert">
                    {PDF_WRONG_PASSWORD}
                  </p>
                ) : null}
                <button className="btn btn-primary" type="submit" disabled={!password || passwordBusy}>
                  Open
                </button>
              </form>
            ) : null}

            {status === "damaged" ? (
              <div className="pdf-state" role="alert">
                <span className="pdf-state-glyph" aria-hidden="true">
                  <FileX size={24} />
                </span>
                <h3>{PDF_DAMAGED_TITLE}</h3>
                <p>{PDF_DAMAGED_MESSAGE}</p>
                <button className="btn btn-primary" type="button" onClick={onDownload}>
                  <Download size={17} aria-hidden="true" />
                  Download
                </button>
              </div>
            ) : null}
          </div>
          {!wide && cache && drawerOpen ? (
            <>
              <div className="pdf-drawer-scrim" aria-hidden="true" onClick={closeDrawer} />
              <aside
                className={`pdf-drawer${drawerDrag ? " is-dragging" : ""}`}
                aria-label="Pages"
                style={drawerDrag ? { transform: `translateX(${drawerDrag}px)` } : undefined}
                onPointerDown={onDrawerDown}
                onPointerMove={onDrawerMove}
                onPointerUp={onDrawerUp}
                onPointerCancel={onDrawerUp}
              >
                <PdfPagesList cache={cache} ratios={ratios} current={page} active onPick={pickPage} />
              </aside>
            </>
          ) : null}
        </div>
      </div>
    </div>,
    document.body,
  );
}
