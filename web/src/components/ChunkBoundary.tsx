import { Component, type ReactNode } from "react";

/*
 * Lazy views whose chunk may fail to download: a dropped connection, or a deploy that replaced the
 * chunks under a long-open tab. An uncaught error would unmount the whole app, so `ChunkBoundary`
 * catches the failure and shows its fallback instead. Asking again in place can't help: the
 * browser keeps a failed import for the life of the page (Chromium does, and React.lazy keeps the
 * rejection too), so the fallback offers a reload.
 */

/** A lazy view's chunk didn't download. */
export class ChunkLoadError extends Error {
  constructor(cause: unknown) {
    super("A part of the app couldn’t be downloaded.", { cause });
    this.name = "ChunkLoadError";
  }
}

/** Wraps a dynamic import so its failure is told apart from the view's own errors. */
export function loadChunk<T>(load: () => Promise<T>): () => Promise<T> {
  return () =>
    load().catch((error: unknown) => {
      throw new ChunkLoadError(error);
    });
}

type ChunkBoundaryProps = {
  fallback: ReactNode;
  /** Called once the fallback is on screen, e.g. to close the view that needed the chunk. */
  onError?: (error: ChunkLoadError) => void;
  children: ReactNode;
};

/** Shows `fallback` when a lazy child's chunk failed; every other error passes on unchanged. */
export class ChunkBoundary extends Component<ChunkBoundaryProps, { error: unknown }> {
  state: { error: unknown } = { error: null };

  static getDerivedStateFromError(error: unknown): { error: unknown } {
    return { error };
  }

  componentDidCatch(error: unknown): void {
    if (error instanceof ChunkLoadError) this.props.onError?.(error);
  }

  render(): ReactNode {
    const { error } = this.state;
    if (error === null) return this.props.children;
    if (!(error instanceof ChunkLoadError)) throw error;
    return this.props.fallback;
  }
}
