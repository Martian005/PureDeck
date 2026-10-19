import { failure, invalid, json, titleOf, tmdb, yearOf } from "./_lib/tmdb.js";

export const maxDuration = 15;

const MAX_RESULTS = 12;

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const query = (url.searchParams.get("q") || "").trim();
    if (query.length < 1 || query.length > 80) {
      return invalid("Invalid search query.");
    }

    try {
      const body = await tmdb("search/multi", { query, include_adult: "false", language: "en-US", page: "1" });
      if (!Array.isArray(body.results)) throw new Error("Invalid TMDB response");
      const results = body.results
        .filter((item) => item.media_type === "tv" || item.media_type === "movie")
        .slice(0, MAX_RESULTS)
        .map((item) => ({
          id: item.id,
          mediaType: item.media_type,
          title: titleOf(item.media_type, item),
          year: yearOf(item.media_type, item),
          overview: (item.overview || "").slice(0, 400),
          posterPath: item.poster_path || null,
          backdropPath: item.backdrop_path || null
        }))
        .filter((item) => item.title);
      return json({ results }, 200, 60 * 60);
    } catch (error) {
      return failure(error);
    }
  }
};
