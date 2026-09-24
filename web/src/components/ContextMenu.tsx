import {
  createContext,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ComponentType,
  type KeyboardEvent,
  type ReactNode,
} from "react";

/** A point in viewport coordinates: the pointer, or an edge of the control that opened it. */
export type MenuAnchor = { x: number; y: number };

export type MenuItem<Id extends string = string> = {
  id: Id;
  label: string;
  Icon: ComponentType<{ size?: number; "aria-hidden"?: boolean | "true" }>;
  /** Destructive actions render red. */
  danger?: boolean;
  /** Draw a hairline above this item (e.g. to set a destructive action apart). */
  separatorBefore?: boolean;
};

/** Keeps the menu this far from every viewport edge. */
const EDGE = 8;

/** How long the menu takes to grow or shrink around content that changed size. */
const RESIZE_MS = 280;
const RESIZE_EASE = "cubic-bezier(0.16, 1, 0.3, 1)";

/**
 * The long-press that opens a menu is still held. Its release would otherwise click
 * whatever item appeared under the finger. Call this synchronously, before React paints.
 */
export function suppressClickAfterLongPress(): void {
  const onClick = (event: MouseEvent) => {
    event.preventDefault();
    event.stopPropagation();
    window.removeEventListener("click", onClick, true);
  };
  window.addEventListener("click", onClick, true);
  const drop = () => {
    // The click is dispatched after pointerup, still before a 0ms timer.
    window.setTimeout(() => window.removeEventListener("click", onClick, true), 0);
  };
  window.addEventListener("pointerup", drop, { capture: true, once: true });
  window.addEventListener("pointercancel", drop, { capture: true, once: true });
}

/**
 * Lets content inside the menu (the reaction row growing into the full set, and back) resize
 * it smoothly: call `willResize` before the state change, `didResize` from a layout effect once
 * the new content is in the DOM. The menu re-fits itself on screen and animates from the old
 * box to the new one — position and size together, so a menu that has to move up to stay on
 * screen glides there instead of jumping.
 */
export type MenuFit = { willResize(): void; didResize(): void };
const MenuFitContext = createContext<MenuFit | null>(null);
export function useMenuFit(): MenuFit | null {
  return useContext(MenuFitContext);
}

type Placement = { left: number; top: number; origin: string };

/** Where a menu of this size opens for `anchor`: at it, flipped up or left when it would run
 * off screen, and never closer than `EDGE` to a viewport edge. */
function place(anchor: MenuAnchor, width: number, height: number): Placement {
  const maxLeft = Math.max(EDGE, window.innerWidth - width - EDGE);
  const maxTop = Math.max(EDGE, window.innerHeight - height - EDGE);
  const fitsRight = anchor.x + width + EDGE <= window.innerWidth;
  const fitsBelow = anchor.y + height + EDGE <= window.innerHeight;
  const left = Math.min(maxLeft, Math.max(EDGE, fitsRight ? anchor.x : anchor.x - width));
  const top = Math.min(maxTop, Math.max(EDGE, fitsBelow ? anchor.y : anchor.y - height));
  /* Grow out of the corner at the anchor, the way native menus open. */
  const origin = `${fitsRight ? "left" : "right"} ${fitsBelow ? "top" : "bottom"}`;
  return { left, top, origin };
}

/** Where an open menu goes when its size changes: it stays put and grows away from its anchor
 * (up when it opened upwards, left when it opened leftwards), then slides just far enough to
 * stay `EDGE` inside the viewport — never flipping to the other side of the anchor. */
function refit(at: Placement, from: { width: number; height: number } | null, width: number, height: number): Placement {
  let left = at.left;
  let top = at.top;
  if (from) {
    if (at.origin.startsWith("right")) left -= width - from.width;
    if (at.origin.endsWith("bottom")) top -= height - from.height;
  }
  left = Math.min(Math.max(EDGE, left), Math.max(EDGE, window.innerWidth - width - EDGE));
  top = Math.min(Math.max(EDGE, top), Math.max(EDGE, window.innerHeight - height - EDGE));
  return { left, top, origin: at.origin };
}

function prefersReducedMotion(): boolean {
  return typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
}

/**
 * Animates `node` from `from` pixels tall to the height it has now, in step with the menu's own
 * resize (same timing), clipped meanwhile. For content inside the menu whose height changed —
 * the reaction grid losing rows to a search — so what sits under it slides instead of jumping.
 */
export function animateHeight(node: HTMLElement, from: number): void {
  const to = node.offsetHeight;
  if (from === to || typeof node.animate !== "function" || prefersReducedMotion()) return;
  node.getAnimations().forEach((animation) => animation.cancel());
  const overflow = node.style.overflowY;
  node.style.overflowY = "hidden";
  const animation = node.animate([{ height: `${from}px` }, { height: `${to}px` }], {
    duration: RESIZE_MS,
    easing: RESIZE_EASE,
  });
  let finished = false;
  const done = () => {
    if (finished) return;
    finished = true;
    node.style.overflowY = overflow;
  };
  animation.onfinish = done;
  animation.oncancel = done;
  window.setTimeout(done, RESIZE_MS + 50);
}

/**
 * The web client's one popup menu: message actions, the account menu — anything that opens
 * at a point and offers a short list of actions.
 *
 * Opens at `anchor` and flips up or left when it would run off screen, the way native menus
 * do. Focus moves into it so the arrow keys work; Escape, a click outside, scrolling, resizing
 * or leaving the window close it without acting. Escape is taken in the capture phase with
 * `stopImmediatePropagation`, so it never also reaches a screen-level Escape handler (cancel a
 * reply, leave a settings page).
 */
export function ContextMenu<Id extends string>({
  anchor,
  items,
  label,
  header,
  onSelect,
  onClose,
  trigger,
  returnFocus,
  settle = false,
}: {
  anchor: MenuAnchor;
  items: MenuItem<Id>[];
  /** Accessible name of the menu. */
  label: string;
  /** Optional non-interactive block above the items (the account menu's name and handle). */
  header?: ReactNode;
  onSelect: (id: Id) => void;
  onClose: () => void;
  /**
   * The control that opened the menu, if any. Focus goes back to it when the menu closes, and
   * pressing it again is left to its own click (a toggle) rather than counted as "outside".
   */
  trigger?: HTMLElement | null;
  /** Where focus goes back when there is no `trigger` (the control focused when the keyboard
   * opened the menu); clicks on it still close the menu. */
  returnFocus?: HTMLElement | null;
  /** Opened by a finger that is still down. Ignore that gesture, then accept taps. */
  settle?: boolean;
}) {
  const menu = useRef<HTMLDivElement>(null);
  const [position, setPosition] = useState<Placement | null>(null);
  const [live, setLive] = useState(!settle);
  /* Content that grows after opening without announcing it re-fits the menu on screen.
     Compared with the size it was placed at, not "skip the first callback": a throttled frame can
     deliver that first callback only after the menu has already grown. */
  const [grown, setGrown] = useState(0);
  const placedSize = useRef({ width: 0, height: 0 });
  const placedAt = useRef<Placement | null>(null);
  const lastAnchor = useRef("");
  /* The announced resize: the box before the change, and the animation carrying the menu from
     it to the box after. */
  const boxBefore = useRef<{ left: number; top: number; width: number; height: number } | null>(null);
  const resizeAnimation = useRef<Animation | null>(null);
  useEffect(() => {
    const node = menu.current;
    if (!node || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(() => {
      // Mid-animation sizes are the resize animation's own business.
      if (resizeAnimation.current) return;
      const placed = placedSize.current;
      if (node.offsetWidth === placed.width && node.offsetHeight === placed.height) return;
      setGrown((n) => n + 1);
    });
    observer.observe(node);
    return () => observer.disconnect();
  }, []);
  /* Read through refs: callers pass inline closures, and re-subscribing the window listeners
     on every render would drop events mid-gesture. */
  const close = useRef(onClose);
  close.current = onClose;
  const toggler = useRef(trigger);
  toggler.current = trigger;
  const opener = useRef(trigger ?? returnFocus);
  opener.current = trigger ?? returnFocus;

  /* Measure once mounted (invisible until then), then clamp into the viewport. The layout size,
     not getBoundingClientRect: that includes the scale-in animation's first frame and comes up
     4% short, which left a flipped menu hanging past its anchor. */
  useLayoutEffect(() => {
    const node = menu.current;
    if (!node) return;
    const { offsetWidth: width, offsetHeight: height } = node;
    placedSize.current = { width, height };
    /* First placement at an anchor flips around it; an open menu that changed size (or lost
       room to a keyboard) only slides to stay on screen. */
    const key = `${anchor.x},${anchor.y}`;
    const at = key === lastAnchor.current ? placedAt.current : null;
    lastAnchor.current = key;
    const next = at ? refit(at, null, width, height) : place(anchor, width, height);
    placedAt.current = next;
    setPosition(next);
  }, [anchor.x, anchor.y, items.length, grown]);

  const menuFit = useMemo<MenuFit>(
    () => ({
      willResize() {
        const node = menu.current;
        const at = placedAt.current;
        if (!node || !at) return;
        /* A change while the last one is still animating starts from the box it has reached:
           the animated size is the laid-out size, and the animated offsets are the computed ones. */
        const style = getComputedStyle(node);
        const left = parseFloat(style.left);
        const top = parseFloat(style.top);
        const width = node.offsetWidth;
        const height = node.offsetHeight;
        resizeAnimation.current?.cancel();
        resizeAnimation.current = null;
        boxBefore.current = {
          left: Number.isFinite(left) ? left : at.left,
          top: Number.isFinite(top) ? top : at.top,
          width,
          height,
        };
      },
      didResize() {
        const node = menu.current;
        const before = boxBefore.current;
        boxBefore.current = null;
        if (!node) return;
        const width = node.offsetWidth;
        const height = node.offsetHeight;
        placedSize.current = { width, height };
        const at = placedAt.current;
        const after = at ? refit(at, before, width, height) : place(anchor, width, height);
        placedAt.current = after;
        // Straight onto the node: React's own update comes a render later.
        node.style.left = `${after.left}px`;
        node.style.top = `${after.top}px`;
        setPosition(after);
        if (!before || typeof node.animate !== "function" || prefersReducedMotion()) return;
        if (
          before.left === after.left &&
          before.top === after.top &&
          before.width === width &&
          before.height === height
        ) {
          return;
        }
        /* Position and size together; the box clips meanwhile, so the new content is revealed
           by the growing edge instead of spilling out with a scrollbar. */
        const overflow = node.style.overflow;
        node.style.overflow = "hidden";
        const animation = node.animate(
          [
            { left: `${before.left}px`, top: `${before.top}px`, width: `${before.width}px`, height: `${before.height}px` },
            { left: `${after.left}px`, top: `${after.top}px`, width: `${width}px`, height: `${height}px` },
          ],
          { duration: RESIZE_MS, easing: RESIZE_EASE },
        );
        resizeAnimation.current = animation;
        let finished = false;
        const done = () => {
          if (finished) return;
          finished = true;
          if (resizeAnimation.current === animation) resizeAnimation.current = null;
          node.style.overflow = overflow;
        };
        animation.onfinish = done;
        animation.oncancel = done;
        // The finish event waits for a rendered frame, which a hidden tab may not get for a while.
        window.setTimeout(done, RESIZE_MS + 50);
      },
    }),
    [anchor],
  );

  /* The finger that opened the menu is still down. Hits pass through until it lifts. */
  useEffect(() => {
    if (!settle) {
      setLive(true);
      return;
    }
    setLive(false);
    const enable = () => setLive(true);
    window.addEventListener("pointerup", enable, { capture: true, once: true });
    window.addEventListener("pointercancel", enable, { capture: true, once: true });
    return () => {
      window.removeEventListener("pointerup", enable, true);
      window.removeEventListener("pointercancel", enable, true);
    };
  }, [settle]);

  /* Focus has to wait until the menu is placed: a `visibility: hidden` element can't take it.
     A long-press also waits until the finger lifts, so focusing under it can't scroll the thread. */
  const placed = position !== null;
  useEffect(() => {
    if (placed && live) {
      // The first action, not a control in the header: Enter must not pick a reaction.
      const first =
        menu.current?.querySelector<HTMLButtonElement>(".ctx-menu-item") ??
        menu.current?.querySelector<HTMLButtonElement>("button");
      first?.focus({ preventScroll: true });
    }
  }, [placed, live]);

  useEffect(() => {
    const openedAt = performance.now();
    const dismiss = (event?: Event) => {
      // Focusing the first item can nudge a scroller in the same turn the menu opens.
      if (event?.type === "scroll" && performance.now() - openedAt < 350) return;
      // Scrolling inside the menu (the full reaction set) is using it, not leaving it.
      if (event?.type === "scroll" && event.target instanceof Node && menu.current?.contains(event.target)) {
        return;
      }
      close.current();
    };
    const onResize = () => {
      /* Typing in the menu (the reaction search) on a phone brings the keyboard up, which some
         browsers report as a resize: keep the menu and fit it into what is left. */
      if (document.activeElement && menu.current?.contains(document.activeElement)) {
        setGrown((n) => n + 1);
        return;
      }
      dismiss();
    };
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (menu.current?.contains(target) || toggler.current?.contains(target)) return;
      close.current();
    };
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key !== "Escape") return;
      // A search box in the menu with text in it takes the first Escape to clear itself.
      const target = event.target;
      if (target instanceof HTMLInputElement && target.value && menu.current?.contains(target)) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      opener.current?.focus({ preventScroll: true });
      close.current();
    };
    window.addEventListener("pointerdown", onPointerDown, true);
    window.addEventListener("keydown", onKeyDown, true);
    window.addEventListener("resize", onResize);
    window.addEventListener("blur", dismiss);
    document.addEventListener("scroll", dismiss, true);
    return () => {
      window.removeEventListener("pointerdown", onPointerDown, true);
      window.removeEventListener("keydown", onKeyDown, true);
      window.removeEventListener("resize", onResize);
      window.removeEventListener("blur", dismiss);
      document.removeEventListener("scroll", dismiss, true);
    };
  }, []);

  /* Arrow keys walk the items and wrap; Home/End jump; Tab leaves the menu. A header with
     controls (the reaction row) is one stop for Up/Down, walked with Left/Right; grown into a
     grid (the full set), Up/Down move a row within it and leave it past its first or last row.
     A text field in the header keeps its own keys, except Down, which goes to the first choice. */
  function onMenuKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const root = menu.current;
    if (!root) return;
    const active = document.activeElement as HTMLButtonElement | null;
    const headerButtons = [...root.querySelectorAll<HTMLButtonElement>(".ctx-menu-header button")];
    const choices = headerButtons.filter((button) => button.getAttribute("role") === "menuitemcheckbox");
    if (active instanceof HTMLInputElement && root.contains(active)) {
      if (event.key !== "ArrowDown") return;
      event.preventDefault();
      (choices[0] ?? headerButtons[0])?.focus();
      return;
    }
    const inHeader = active != null && headerButtons.includes(active);
    if (inHeader && (event.key === "ArrowRight" || event.key === "ArrowLeft")) {
      const at = headerButtons.indexOf(active);
      const step = event.key === "ArrowRight" ? 1 : -1;
      event.preventDefault();
      headerButtons[(at + step + headerButtons.length) % headerButtons.length]?.focus();
      return;
    }
    if (inHeader && (event.key === "ArrowDown" || event.key === "ArrowUp")) {
      // Rows by centre line: a smaller button centred in a row ("more") is in that row.
      const down = event.key === "ArrowDown";
      const centre = (b: HTMLElement) => b.offsetTop + b.offsetHeight / 2;
      const middle = (b: HTMLElement) => b.offsetLeft + b.offsetWidth / 2;
      const here = centre(active);
      const half = active.offsetHeight / 2;
      const beyond = headerButtons.filter((b) => (down ? centre(b) - here : here - centre(b)) >= half);
      if (beyond.length > 0) {
        const rowCentre = down ? Math.min(...beyond.map(centre)) : Math.max(...beyond.map(centre));
        const row = beyond.filter((b) => Math.abs(centre(b) - rowCentre) < half);
        const nearest = row.reduce((best, b) =>
          Math.abs(middle(b) - middle(active)) < Math.abs(middle(best) - middle(active)) ? b : best,
        );
        event.preventDefault();
        nearest.focus();
        return;
      }
    }
    const items = [...root.querySelectorAll<HTMLButtonElement>(".ctx-menu-item")];
    const headerStop =
      headerButtons.find((button) => button.getAttribute("aria-checked") === "true") ??
      choices[0] ??
      headerButtons[0];
    const stops = [...(headerStop ? [inHeader ? active : headerStop] : []), ...items];
    if (stops.length === 0) return;
    const index = inHeader ? 0 : stops.indexOf(active as HTMLButtonElement);
    let next = -1;
    if (event.key === "ArrowDown") next = (index + 1) % stops.length;
    else if (event.key === "ArrowUp") next = (index - 1 + stops.length) % stops.length;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = stops.length - 1;
    else if (event.key === "Tab") {
      // Tab moves between the header's own controls; from an action it leaves the menu.
      if (inHeader) return;
      close.current();
      return;
    }
    if (next < 0) return;
    event.preventDefault();
    stops[next]?.focus();
  }

  return (
    <div
      ref={menu}
      className="ctx-menu"
      role="menu"
      aria-label={label}
      style={{
        left: position?.left ?? anchor.x,
        top: position?.top ?? anchor.y,
        transformOrigin: position?.origin,
        visibility: position ? "visible" : "hidden",
        pointerEvents: live ? "auto" : "none",
      }}
      onKeyDown={onMenuKeyDown}
      onContextMenu={(event) => event.preventDefault()}
    >
      <MenuFitContext.Provider value={menuFit}>
        {header ? (
          <div className="ctx-menu-header" role="none">
            {header}
          </div>
        ) : null}
        {items.map(({ id, label: text, Icon, danger, separatorBefore }) => (
          <div key={id} role="none">
            {separatorBefore ? <hr className="ctx-menu-sep" /> : null}
            <button
              type="button"
              role="menuitem"
              className={danger ? "ctx-menu-item danger" : "ctx-menu-item"}
              onClick={() => {
                opener.current?.focus({ preventScroll: true });
                onSelect(id);
              }}
            >
              <Icon size={16} aria-hidden="true" />
              {text}
            </button>
          </div>
        ))}
      </MenuFitContext.Provider>
    </div>
  );
}
