import { failure, idPattern, invalid, json, pickLogo, tmdb, typePattern } from "./_lib/tmdb.js";

export const maxDuration = 10;

// Kept separate from /api/title: the hero asks for a logo on every rotation and only needs
// this one cheap lookup, which the CDN can hold for a month.
export default {
  async fetch(request) {
    const url = new URL(request.url);
    const type = url.searchParams.get("type") || "";
    const id = url.searchParams.get("id") || "";
    if (!typePattern.test(type) || !idPattern.test(id)) return invalid("Invalid type or id.");

    try {
      const images = await tmdb(`${type}/${id}/images`, { include_image_language: "en,null" });
      return json({ logoPath: pickLogo(images.logos) }, 200, 30 * 24 * 60 * 60);
    } catch (error) {
      return failure(error);
    }
  }
};
