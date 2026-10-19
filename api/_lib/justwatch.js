// TMDB says WHICH services carry a title but gives no links into them. JustWatch (TMDB's own
// availability source) does. This is an undocumented API: every failure here degrades to "no
const ENDPOINT = "https://apis.justwatch.com/graphql";

const QUERY = `query($q: String!, $country: Country!, $lang: Language!, $types: [ObjectType!]) {
  popularTitles(country: $country, first: 6, filter: { searchQuery: $q, objectTypes: $types }) {
    edges { node {
      content(country: $country, language: $lang) { externalIds { tmdbId } }
      offers(country: $country, platform: WEB) {
        monetizationType standardWebURL
        package { clearName technicalName }
      }
    } }
  }
}`;

const MONETIZATION_RANK = { FLATRATE: 0, FREE: 1, ADS: 2, RENT: 3, BUY: 4 };

export const normalize = (name) => String(name || "").toLowerCase().replace(/[^a-z0-9]/g, "");

/**
 * Map of normalized service name -> best URL for this exact title. Keys include both
 * JustWatch's display name and its technical name, so "Disney Plus" and "Disney+" both hit.
 */
export async function justWatchLinks({ title, type, tmdbId, region }) {
  try {
    const response = await fetch(ENDPOINT, {
      method: "POST",
      headers: { "Content-Type": "application/json", "User-Agent": "Mozilla/5.0" },
      body: JSON.stringify({
        query: QUERY,
        variables: { q: title, country: region, lang: "en", types: [type === "tv" ? "SHOW" : "MOVIE"] }
      }),
      signal: AbortSignal.timeout(6_000)
    });
    if (!response.ok) return new Map();
    const body = await response.json();
    const edges = body?.data?.popularTitles?.edges ?? [];
    // Titles are searched by name, so confirm it is the same show by TMDB id.
    const match = edges.map((edge) => edge.node).find((node) => node.content?.externalIds?.tmdbId === String(tmdbId));
    if (!match) return new Map();

    const best = new Map();
    for (const offer of match.offers ?? []) {
      if (!offer.standardWebURL || !offer.package) continue;
      const rank = MONETIZATION_RANK[offer.monetizationType] ?? 9;
      for (const key of [normalize(offer.package.clearName), normalize(offer.package.technicalName)]) {
        // JustWatch occasionally returns URLs with trailing whitespace.
        if (key && (!best.has(key) || rank < best.get(key).rank)) best.set(key, { rank, url: offer.standardWebURL.trim() });
      }
    }
    return new Map([...best].map(([key, value]) => [key, value.url]));
  } catch {
    return new Map();
  }
}

const NEW_TITLES = `query($country: Country!, $date: Date!, $packages: [String!]) {
  newTitles(country: $country, date: $date, first: 40, filter: { packages: $packages, objectTypes: [SHOW, MOVIE] }) {
    edges { node { objectType content(country: $country, language: "en") { externalIds { tmdbId } } } }
  }
}`;

const PACKAGES = `query($country: Country!) { packages(country: $country, platform: WEB) { packageId shortName } }`;

const DAY_BATCH = 15;

async function post(query, variables) {
  const response = await fetch(ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/json", "User-Agent": "Mozilla/5.0" },
    body: JSON.stringify({ query, variables }),
    signal: AbortSignal.timeout(8_000)
  });
  if (!response.ok) throw new Error(`JustWatch ${response.status}`);
  return (await response.json()).data;
}

const shortNameCache = new Map();

/** JustWatch package ids are the same numbers as TMDB provider ids; its filters want the short name. */
async function shortNames(region, providerIds) {
  if (!shortNameCache.has(region)) {
    const data = await post(PACKAGES, { country: region });
    shortNameCache.set(region, new Map((data?.packages ?? []).map((p) => [p.packageId, p.shortName])));
  }
  const names = shortNameCache.get(region);
  return providerIds.map((id) => names.get(id)).filter(Boolean);
}

/** Titles genuinely added to these services in `region` over the last `days`, newest first. */
export async function recentlyAdded({ providerIds, region, days }) {
  const failures = [];
  try {
    const packages = await shortNames(region, providerIds);
    if (!packages.length) return { titles: [], note: "no JustWatch package for this provider" };
    const dates = Array.from({ length: days }, (_, i) => new Date(Date.now() - i * 86_400_000).toISOString().slice(0, 10));
    const seen = new Set();
    const titles = [];
    for (let i = 0; i < dates.length; i += DAY_BATCH) {
      const batch = await Promise.allSettled(
        dates.slice(i, i + DAY_BATCH).map((date) => post(NEW_TITLES, { country: region, date, packages }))
      );
      for (const result of batch) {
        if (result.status !== "fulfilled") {
          failures.push(String(result.reason?.message ?? result.reason));
          continue;
        }
        for (const edge of result.value?.newTitles?.edges ?? []) {
          const tmdbId = Number(edge.node.content?.externalIds?.tmdbId);
          const mediaType = edge.node.objectType === "SHOW" ? "tv" : "movie";
          const key = `${mediaType}:${tmdbId}`;
          if (!tmdbId || seen.has(key)) continue;
          seen.add(key);
          titles.push({ id: tmdbId, mediaType });
        }
      }
    }
    // Surfaced in the catalogue response so a silent fallback to TMDB can be diagnosed.
    return { titles, note: failures.length ? `${failures.length} day lookups failed: ${failures[0]}` : undefined };
  } catch (error) {
    return { titles: [], note: String(error?.message ?? error) };
  }
}

const TRENDING = `query($country: Country!, $packages: [String!]) {
  popularTitles(country: $country, first: 50, sortBy: TRENDING, filter: { packages: $packages, objectTypes: [SHOW, MOVIE] }) {
    edges { node { objectType content(country: $country, language: "en") { externalIds { tmdbId } } } }
  }
}`;

/**
 * What is trending on these services in `region`, by JustWatch's own per-country ranking. Unlike
 * TMDB popularity (global), this reflects what people in that country are watching.
 */
export async function trendingTitles({ providerIds, region }) {
  try {
    const packages = await shortNames(region, providerIds);
    if (!packages.length) return [];
    const data = await post(TRENDING, { country: region, packages });
    const seen = new Set();
    const titles = [];
    for (const edge of data?.popularTitles?.edges ?? []) {
      const id = Number(edge.node.content?.externalIds?.tmdbId);
      const mediaType = edge.node.objectType === "SHOW" ? "tv" : "movie";
      const key = `${mediaType}:${id}`;
      if (!id || seen.has(key)) continue;
      seen.add(key);
      titles.push({ id, mediaType });
    }
    return titles;
  } catch {
    return [];
  }
}

