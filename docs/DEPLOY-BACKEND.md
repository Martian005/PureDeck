# Deploy the catalogue backend

The `api/` folder is a set of Vercel serverless functions (Node, ES modules). Vercel's free Hobby plan is enough for personal use.

## Steps

1. **Get a TMDB key** (see [TMDB.md](TMDB.md)).
2. **Fork this repo** on GitHub.
3. In Vercel choose **Add New → Project**, import your fork, and leave the framework preset as *Other*. No build command is needed.
4. Under **Environment Variables** add:

   | Name | Value |
   | --- | --- |
   | `TMDB_API_KEY` | your TMDB v3 key |

5. Deploy. You get an address like `https://your-project.vercel.app`.
6. Test it:

   ```bash
   curl "https://your-project.vercel.app/api/catalogue?provider=8&region=YOUR_REGION"
   ```

7. Put that address in `catalogue.baseUrl` in `local.properties` and rebuild the app.

## With the Vercel CLI instead

```bash
npm i -g vercel
vercel login
vercel link          # pick or create a project
vercel env add TMDB_API_KEY production
vercel deploy --prod
```

`vercel link` writes a `.vercel/` folder and may write `.env.local` with a token. Both are git-ignored; delete the token file when you're done.

## Notes

- Changed the key? Redeploy. Env changes only apply to new deployments.
- Responses are cached at Vercel's edge (see `Cache-Control` in each function), so the TV stays fast and you stay far below the free limits.
- JustWatch's GraphQL endpoint is unofficial. If it changes, the backend falls back to TMDB's lists and the "Open in app" links may stop resolving to a specific title.
- Never put the key in the repo, the README or screenshots.
