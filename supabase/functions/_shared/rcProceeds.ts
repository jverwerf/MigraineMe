// supabase/functions/_shared/rcProceeds.ts
//
// Lifetime proceeds RevenueCat holds for one user, in USD: gross less the
// store's cut less tax. Used by the affiliate functions, both to take a
// baseline when a user is first attributed to a partner and to accrue
// commission afterwards.
//
// APP_USER_ID CASING: iOS registers the Supabase user id as an UPPERCASE
// UUID (Swift's uuidString), Android lowercase — the same person can be
// two RevenueCat customers. Both spellings are queried and summed; miss
// this and iPhone subscribers look like they never paid.

const RC_PROJECT = "ec488213"; // MigraineMe

type RcSubscription = {
  country?: string | null;
  store?: string | null;
  total_revenue_in_usd?: { proceeds?: number; currency?: string } | null;
};

export type RcProceeds = {
  proceeds: number;
  country: string | null;
  store: string | null;
  // false = RevenueCat has no customer under either casing (vs proceeds 0,
  // which means "found but no money yet").
  found: boolean;
};

export async function fetchProceeds(userId: string): Promise<RcProceeds> {
  const key = Deno.env.get("REVENUECAT_SECRET_KEY")!;
  const variants = [userId.toUpperCase(), userId.toLowerCase()];
  let proceeds = 0;
  let country: string | null = null;
  let store: string | null = null;
  let found = false;

  for (const id of variants) {
    const res = await fetch(
      `https://api.revenuecat.com/v2/projects/${RC_PROJECT}/customers/${id}/subscriptions?limit=50`,
      { headers: { Authorization: `Bearer ${key}` } },
    );
    if (res.status === 404) continue; // customer doesn't exist under this casing
    if (!res.ok) throw new Error(`RC ${res.status} for ${id}: ${await res.text()}`);
    found = true;
    const body = await res.json();
    for (const sub of (body.items ?? []) as RcSubscription[]) {
      proceeds += sub.total_revenue_in_usd?.proceeds ?? 0;
      country ??= sub.country ?? null;
      store ??= sub.store ?? null;
    }
    // Uppercase and lowercase can BOTH exist (same human, two devices),
    // so keep going rather than breaking on the first hit.
  }

  return { proceeds: Math.round(proceeds * 100) / 100, country, store, found };
}

// What the user had already paid before a partner brought them in. A partner
// earns on revenue from the moment of attribution, not on the years before
// it, so this is stored on the attribution row and subtracted at accrual.
// Never throws: a RevenueCat hiccup must not cost the partner the attribution.
export async function baselineProceeds(userId: string): Promise<number> {
  try {
    return (await fetchProceeds(userId)).proceeds;
  } catch (err) {
    console.error(`[rcProceeds] baseline lookup failed for ${userId}: ${err}`);
    return 0;
  }
}
