import { useQuery } from "@tanstack/react-query";
export async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    ...init,
    headers: { "Content-Type": "application/json", ...init?.headers },
  });
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as {
      detail?: string;
      message?: string;
    };
    throw new Error(
      problem.detail ||
        problem.message ||
        `Request failed (${response.status})`,
    );
  }
  return response.status === 204
    ? (undefined as T)
    : (response.json() as Promise<T>);
}
export function useApi<T>(path: string, refresh = true) {
  return useQuery<T>({
    queryKey: [path],
    queryFn: ({ signal }) => request<T>(path, { signal }),
    refetchInterval: refresh ? 10000 : false,
    retry: 1,
  });
}
export function rangeQuery(minutes: number): string {
  const to = new Date(Math.floor(Date.now() / 60000) * 60000);
  return new URLSearchParams({
    from: new Date(to.getTime() - minutes * 60000).toISOString(),
    to: to.toISOString(),
  }).toString();
}
export function ms(value: number | null | undefined): string {
  return value == null
    ? "—"
    : value >= 1000
      ? `${(value / 1000).toFixed(2)} s`
      : `${value.toFixed(value < 10 ? 2 : 1)} ms`;
}
export function percent(value: number | null | undefined): string {
  return value == null ? "—" : `${(value * 100).toFixed(2)}%`;
}
export function count(value: number): string {
  return Intl.NumberFormat("en-US", {
    notation: value >= 10000 ? "compact" : "standard",
    maximumFractionDigits: 1,
  }).format(value);
}
