import { recentlyAdded, trendingTitles } from "./_lib/justwatch.js";
import { failure, invalid, json, pickLogo, regionPattern, titleOf, tmdb, yearOf } from "./_lib/tmdb.js";

export const maxDuration = 20;

const providerPattern = /^\d+(\|\d+)*$/;
// Trending = most popular right now among titles from roughly the last 18 months.
const NEW_RELEASE_DAYS = 540;
const MAX_ITEMS = 48;
const ADDED_DAYS = 30;
const MAX_CANDIDATES = 50;
const ENRICH_BATCH = 25;
// Below this many real additions (JioHotstar has ~17 a month; Zee5 and SonyLIV none) TMDB tops it up.
const MAX_TRENDING = 24;
const CACHE_SECONDS = 6 * 60 * 60;

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const provider = url.searchParams.get("provider") || "";
    const region = (url.searchParams.get("region") || "IN").toUpperCase();
    if (!providerPattern.test(provider) || !regionPattern.test(region)) {
      return invalid("Invalid provider or region.");
    }

    try {
      const { items, source, note } = await catalogue(provider, region);
      return json({ items, source, note }, 200, CACHE_SECONDS);
    } catch (error) {
      return failure(error);
    }
  }
};

const daysAgo = (days) => new Date(Date.now() - days * 86_400_000).toISOString().slice(0, 10);

/** Two labelled groups, interleaved so neither dominates the front: - "added": titles JustWatch says this service really added in this region (last 30 days) - "trending": what is trending on the service in this region (JustWatch's per-country ranking) */
async function catalogue(provider, region) {
  const providerIds = provider.split("|").map(Number);
  const [{ titles, note }, indiaTrending] = await Promise.all([
    recentlyAdded({ providerIds, region, days: ADDED_DAYS }),
    trendingTitles({ providerIds, region })
  ]);
  // JustWatch's ranking is per-country. TMDB popularity is global, so it is only a fallback.
  const trendingCandidates = indiaTrending.length ? indiaTrending : await discoverCatalogue(provider, region).catch(() => []);
  const added = (await enrich(titles.slice(0, MAX_CANDIDATES))).map((item) => ({ ...item, tag: "added" }));
  const trending = (await enrich(trendingCandidates.slice(0, MAX_TRENDING)))
    .map((item) => ({ ...item, tag: "trending" }));
  const items = unique(interleave([added, trending])).slice(0, MAX_ITEMS);
  return { items, source: `added:${added.length}+trending:${trending.length}`, note };
}

async function enrich(candidates) {
  const found = [];
  for (let i = 0; i < candidates.length; i += ENRICH_BATCH) {
    const batch = candidates.slice(i, i + ENRICH_BATCH);
    const results = await Promise.allSettled(
      batch.map((c) => tmdb(`${c.mediaType}/${c.id}`, {
        append_to_response: "images", include_image_language: "en,null", language: "en-US"
      }, 8_000))
    );
    results.forEach((result, k) => {
      if (result.status !== "fulfilled") return;
      const { id, mediaType } = batch[k];
      const detail = result.value;
      const title = titleOf(mediaType, detail);
      if (!title || !detail.backdrop_path?.startsWith("/")) return;
      found.push({
        id, mediaType, title, year: yearOf(mediaType, detail),
        backdropPath: detail.backdrop_path, logoPath: pickLogo(detail.images?.logos),
        popularity: detail.popularity || 0
      });
    });
  }
  // Notable titles first; the TV shuffles anyway, but a short list should lead with the best.
  return found.sort((a, b) => b.popularity - a.popularity).map(({ popularity, ...item }) => item);
}

function unique(items) {
  const seen = new Set();
  return items.filter((item) => {
    const key = `${item.mediaType}:${item.id}`;
    return seen.has(key) ? false : (seen.add(key), true);
  });
}

/**
 * Trending: the service's most popular titles in this region among shows and films from the last
 * ~18 months. Older long-running series are excluded on purpose, or they top every list.
 */
async function discoverCatalogue(provider, region) {
  const today = daysAgo(0);
  const base = {
    watch_region: region,
    with_watch_providers: provider,
    with_watch_monetization_types: "flatrate|free|ads",
    include_adult: "false",
    sort_by: "popularity.desc"
  };
  const sources = [
    ["tv", { ...base, "first_air_date.gte": daysAgo(NEW_RELEASE_DAYS), "first_air_date.lte": today }],
    ["movie", { ...base, "primary_release_date.gte": daysAgo(NEW_RELEASE_DAYS), "primary_release_date.lte": today }]
  ];

  let lists = await discoverAll(sources, [1, 2]);
  if (lists.every((list) => list.length === 0)) {
    // Quiet provider or region: fall back to what is simply popular rather than an empty hero.
    lists = await discoverAll(
      [["tv", base], ["movie", base]],
      [1, 2]
    );
  }
  return interleave(lists).slice(0, MAX_ITEMS);
}

async function discoverAll(sources, pages) {
  const requests = sources.map(([type, params]) =>
    Promise.allSettled(pages.map((page) => discover(type, { ...params, page: String(page) })))
  );
  const settled = await Promise.all(requests);
  // One failed page shouldn't blank the hero, but all of them failing is a real outage.
  if (settled.every((pagesOfSource) => pagesOfSource.every((r) => r.status === "rejected"))) {
    throw new Error("TMDB unavailable");
  }
  return settled.map((pagesOfSource) =>
    pagesOfSource.flatMap((result) => (result.status === "fulfilled" ? result.value : []))
  );
}

async function discover(type, params) {
  const body = await tmdb(`discover/${type}`, params);
  if (!Array.isArray(body.results)) throw new Error("Invalid TMDB response");
  return body.results.flatMap((item) => {
    const title = titleOf(type, item);
    const backdropPath = typeof item.backdrop_path === "string" ? item.backdrop_path : null;
    if (!title || !backdropPath?.startsWith("/")) return [];
    return [{ id: item.id, mediaType: type, title, year: yearOf(type, item), backdropPath }];
  });
}

/** Round-robin across the lists, dropping repeats, so no single source dominates the front. */
function interleave(lists) {
  const seen = new Set();
  const out = [];
  for (let i = 0; lists.some((list) => i < list.length); i++) {
    for (const list of lists) {
      const item = list[i];
      if (!item) continue;
      const key = `${item.mediaType}:${item.id}`;
      if (seen.has(key)) continue;
      seen.add(key);
      out.push(item);
    }
  }
  return out;
}
