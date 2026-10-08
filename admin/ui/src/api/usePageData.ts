import { useCallback, useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { ApiError, api } from "./client";

export interface PageData<T> {
  data: T | null;
  error: ApiError | Error | null;
  loading: boolean;
  reload: () => void;
}

/** Loads one GET route for a page; a 401 sends the operator to sign-in, anything else is
 *  returned for the page to render as its "Couldn't load" frame. */
export function usePageData<T>(path: string): PageData<T> {
  const navigate = useNavigate();
  const location = useLocation();
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | Error | null>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);

  const reload = useCallback(() => setTick((value) => value + 1), []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api<T>(path)
      .then((result) => {
        if (cancelled) return;
        setData(result);
        setError(null);
      })
      .catch((failure: unknown) => {
        if (cancelled) return;
        if (failure instanceof ApiError && failure.status === 401) {
          navigate("/sign-in", { replace: true, state: { from: location.pathname } });
          return;
        }
        setError(failure instanceof Error ? failure : new Error(String(failure)));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [path, tick, navigate, location.pathname]);

  return { data, error, loading, reload };
}
