import { useCallback, useEffect, useState } from 'react';

/** Minimal data-loading hook: { data, error, loading, reload }. */
export function useAsync<T>(fn: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | undefined>();
  const [error, setError] = useState<string | undefined>();
  const [loading, setLoading] = useState(true);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const load = useCallback(fn, deps);
  const reload = useCallback(() => {
    setLoading(true);
    load().then((d) => { setData(d); setError(undefined); }).catch((e: Error) => setError(e.message)).finally(() => setLoading(false));
  }, [load]);
  useEffect(reload, [reload]);
  return { data, error, loading, reload };
}
