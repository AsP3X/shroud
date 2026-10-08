import { CircleAlert, CircleCheck, X } from "lucide-react";
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";

interface ToastItem {
  id: number;
  text: string;
  tone: "ok" | "danger";
}

const ToastContext = createContext<(text: string, tone?: ToastItem["tone"]) => void>(() => {});

export function useToast() {
  return useContext(ToastContext);
}

/** The frame "User detail · Device removed": one line at the bottom, dismissable, gone after 8 s. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const show = useCallback((text: string, tone: ToastItem["tone"] = "ok") => {
    const id = Date.now() + Math.random();
    setItems((current) => [...current, { id, text, tone }]);
  }, []);
  const dismiss = (id: number) => setItems((current) => current.filter((item) => item.id !== id));

  useEffect(() => {
    if (items.length === 0) return;
    const handle = setTimeout(() => setItems((current) => current.slice(1)), 8000);
    return () => clearTimeout(handle);
  }, [items]);

  return (
    <ToastContext.Provider value={show}>
      {children}
      <div className="toasts" aria-live="polite">
        {items.map((item) => (
          <div className="toast" key={item.id} role="status">
            {item.tone === "ok" ? <CircleCheck aria-hidden="true" className="toast__icon toast__icon--ok" /> : <CircleAlert aria-hidden="true" className="toast__icon toast__icon--danger" />}
            <span className="toast__text">{item.text}</span>
            <button type="button" className="icon-button" aria-label="Dismiss" onClick={() => dismiss(item.id)}>
              <X aria-hidden="true" />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}
