// Files under api/_lib are helpers, not endpoints (Vercel ignores underscore-prefixed paths).
const API = "https://api.themoviedb.org/3";

export const regionPattern = /^[A-Z]{2}$/;
export const typePattern = /^(tv|movie)$/;
export const idPattern = /^\d{1,9}$/;

export class ConfigError extends Error {}

export async function tmdb(path, params = {}, timeoutMs = 10_000) {
  const key = process.env.TMDB_API_KEY;
  if (!key) throw new ConfigError("TMDB_API_KEY is not set");
  const query = new URLSearchParams({ api_key: key, ...params });
  const response = await fetch(`${API}/${path}?${query}`, { signal: AbortSignal.timeout(timeoutMs) });
  if (!response.ok) throw new Error(`TMDB ${response.status}`);
  return response.json();
}

export function json(body, status = 200, cacheSeconds = 0) {
  const headers = cacheSeconds
    ? { "Cache-Control": `public, s-maxage=${cacheSeconds}, stale-while-revalidate=${cacheSeconds * 4}` }
    : {};
  return Response.json(body, { status, headers });
}

export function invalid(message) {
  return json({ error: { code: "INVALID_REQUEST", message } }, 400);
}

export function failure(error) {
  if (error instanceof ConfigError) {
    return json({ error: { code: "CONFIGURATION_ERROR", message: "Catalogue service is not configured." } }, 500);
  }
  return json({ error: { code: "UPSTREAM_UNAVAILABLE", message: "Catalogue is temporarily unavailable." } }, 502);
}

export const titleOf = (type, item) => (type === "tv" ? item.name : item.title) || null;

export const yearOf = (type, item) =>
  ((type === "tv" ? item.first_air_date : item.release_date) || "").slice(0, 4) || null;

/**
 * Best title logo: a transparent PNG (BitmapFactory can't decode SVG), English first,
 * wide wordmarks before square emblems, then most-voted.
 */
export function pickLogo(logos = []) {
  const usable = logos.filter((logo) => typeof logo.file_path === "string" && /\.png$/i.test(logo.file_path));
  const rank = (logo) => [
    logo.iso_639_1 === "en" ? 0 : logo.iso_639_1 == null ? 1 : 2,
    logo.aspect_ratio >= 1.5 ? 0 : 1,
    -(logo.vote_average || 0),
    -(logo.width || 0)
  ];
  const better = (a, b) => {
    const ra = rank(a);
    const rb = rank(b);
    for (let i = 0; i < ra.length; i++) if (ra[i] !== rb[i]) return ra[i] - rb[i];
    return 0;
  };
  return usable.sort(better)[0]?.file_path ?? null;
}
