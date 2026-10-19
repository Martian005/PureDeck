import { justWatchLinks, normalize } from "./_lib/justwatch.js";
import {
  failure, idPattern, invalid, json, pickLogo, regionPattern, titleOf, tmdb, typePattern, yearOf
} from "./_lib/tmdb.js";

export const maxDuration = 20;

// Best-first. "included" = covered by a subscription.
const KINDS = [["flatrate", "included"], ["free", "free"], ["ads", "ads"], ["rent", "rent"], ["buy", "buy"]];
const MAX_PROVIDERS = 8;

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const type = url.searchParams.get("type") || "";
    const id = url.searchParams.get("id") || "";
    const region = (url.searchParams.get("region") || "IN").toUpperCase();
    if (!typePattern.test(type) || !idPattern.test(id) || !regionPattern.test(region)) {
      return invalid("Invalid type, id or region.");
    }

    try {
      const detail = await tmdb(`${type}/${id}`, {
        append_to_response: "watch/providers,images",
        include_image_language: "en,null",
        language: "en-US"
      });
      const title = titleOf(type, detail);
      const year = yearOf(type, detail);
      const providers = providersFor(detail["watch/providers"]?.results?.[region]);
      const links = providers.length ? await justWatchLinks({ title, type, tmdbId: id, region }) : new Map();

      return json({
        id: detail.id,
        mediaType: type,
        title,
        year,
        overview: detail.overview || "",
        rating: detail.vote_average ? Math.round(detail.vote_average * 10) / 10 : null,
        genres: (detail.genres || []).map((genre) => genre.name),
        meta: metaOf(type, detail),
        backdropPath: detail.backdrop_path || null,
        posterPath: detail.poster_path || null,
        logoPath: pickLogo(detail.images?.logos),
        providers: providers.map((provider) => ({ ...provider, url: links.get(normalize(provider.name)) ?? null }))
      }, 200, 6 * 60 * 60);
    } catch (error) {
      return failure(error);
    }
  }
};

function metaOf(type, detail) {
  if (type === "tv") {
    const seasons = detail.number_of_seasons;
    return seasons ? `${seasons} season${seasons === 1 ? "" : "s"}` : null;
  }
  return detail.runtime ? `${Math.floor(detail.runtime / 60)}h ${detail.runtime % 60}m` : null;
}

/** One entry per service, in its best form (a subscription beats a rental). */
function providersFor(region = {}) {
  const byId = new Map();
  KINDS.forEach(([key, kind], rank) => {
    for (const provider of region[key] || []) {
      const existing = byId.get(provider.provider_id);
      if (existing && existing.rank <= rank) continue;
      byId.set(provider.provider_id, {
        rank,
        priority: provider.display_priority ?? 99,
        id: provider.provider_id,
        name: provider.provider_name,
        logoPath: provider.logo_path || null,
        kind
      });
    }
  });
  return [...byId.values()]
    .sort((a, b) => a.rank - b.rank || a.priority - b.priority)
    .slice(0, MAX_PROVIDERS)
    .map(({ rank, priority, ...provider }) => provider);
}
