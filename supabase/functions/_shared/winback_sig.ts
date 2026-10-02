// Signature for the winback email's claim link (winback-daily writes it,
// winback-claim checks it). HMAC-SHA256 of the user id keyed on the service
// role key, so links cannot be forged for other accounts and need no table.
export async function winbackSig(key: string, userId: string): Promise<string> {
  const k = await crypto.subtle.importKey("raw", new TextEncoder().encode(key), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = new Uint8Array(await crypto.subtle.sign("HMAC", k, new TextEncoder().encode(`winback:${userId.toLowerCase()}`)));
  return btoa(String.fromCharCode(...mac)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "").slice(0, 22);
}
