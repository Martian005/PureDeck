export const maxDuration = 20;

const imagePathPattern = /^\/[A-Za-z0-9/_-]+\.(jpg|jpeg|png)$/i;
// Backdrops need w1280; posters, provider badges and title logos are far smaller.
const sizes = new Set(["w92", "w154", "w185", "w300", "w500", "w780", "w1280"]);

export default {
  async fetch(request) {
    const params = new URL(request.url).searchParams;
    const path = params.get("path") || "";
    const size = params.get("size") || "w1280";
    if (!imagePathPattern.test(path) || !sizes.has(size)) {
      return Response.json({ error: { code: "INVALID_REQUEST", message: "Invalid artwork path." } }, { status: 400 });
    }

    try {
      const response = await fetch(`https://image.tmdb.org/t/p/${size}${path}`, {
        signal: AbortSignal.timeout(15_000)
      });
      if (!response.ok) throw new Error("Image request failed");
      return new Response(response.body, {
        headers: {
          "Content-Type": response.headers.get("content-type") || "image/jpeg",
          "Cache-Control": "public, s-maxage=2592000, stale-while-revalidate=31536000"
        }
      });
    } catch {
      return Response.json({ error: { code: "UPSTREAM_UNAVAILABLE", message: "Artwork is temporarily unavailable." } }, { status: 502 });
    }
  }
};
