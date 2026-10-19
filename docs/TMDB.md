# How PureDeck uses TMDB

[TMDB](https://www.themoviedb.org) supplies the artwork and metadata. It never talks to the TV directly; only the backend in `api/` does, and it holds the key.

## Getting a free API key

1. Sign up at https://www.themoviedb.org/signup and confirm your email.
2. Open https://www.themoviedb.org/settings/api and click **Create** (or *Request an API key*).
3. Pick **Developer**, accept the terms, and fill the form. For "Type of use" choose personal; a short description like "home-screen launcher for my own TV" is enough.
4. Copy the **API Key (v3 auth)**, a 32-character hex string.

The key is free for non-commercial use and personal use.

## Where the key goes

- **Backend (recommended):** Vercel → Project → Settings → Environment Variables → `TMDB_API_KEY`. Never commit it.
- **Direct from the TV (testing only):** `tmdb.apiKey` in `local.properties`, with `catalogue.baseUrl` left empty. The key is then compiled into the APK.

## Why a backend at all

On my Android TV 8 set the direct calls to `api.themoviedb.org` and `image.tmdb.org` timed out even though everything else worked. My Google TV on the same Wi-Fi, and the emulator on a different network, reached TMDB fine. I suspect my ISP's DNS combined with the older TV's network handling, but I haven't proven it. Putting a small server in between fixed it on the old TV and keeps the key out of the APK.

If your TVs are all newer you can skip the backend and use `tmdb.apiKey` directly for testing, but expect the same problem on older sets.

## What the backend calls

| Endpoint on the backend | Used for |
| --- | --- |
| `/api/catalogue?provider=&region=` | hero rows: recently added and trending titles with backdrops and title logos |
| `/api/search?q=` | search (TMDB `search/multi`, movies and shows only) |
| `/api/title?type=&id=&region=` | detail card and where to watch (TMDB details and watch providers) |
| `/api/logo?type=&id=` | title logo (TMDB images) |
| `/api/artwork?path=&size=` | every image, proxied from `image.tmdb.org` |

`provider` is a TMDB watch-provider id (Netflix 8, Prime Video 9, Crunchyroll 283, JioHotstar 2336, ...). TMDB and JustWatch use the same ids for streaming services, which is what lets the two sources be combined.

TMDB doesn't say when a service added a title. "Recently added" and "Trending" therefore come from JustWatch's per-country lists, then get artwork from TMDB. When JustWatch returns too little, the backend tops up from TMDB's own discover results for that provider and region.

## Check it works

```bash
curl "https://YOUR-PROJECT.vercel.app/api/catalogue?provider=8&region=YOUR_REGION"
```

A healthy answer is JSON with an `items` array. Common problems:

| Response | Cause | Fix |
| --- | --- | --- |
| `CONFIGURATION_ERROR` | `TMDB_API_KEY` missing | add it in Vercel and redeploy |
| `502 UPSTREAM_UNAVAILABLE` | TMDB unreachable or key rejected | check Vercel function logs and the key |
| empty `items` | provider has nothing in that region | try another provider or region |

## Attribution

TMDB asks apps to say so: *This product uses the TMDB API but is not endorsed or certified by TMDB.* It's in the README.
