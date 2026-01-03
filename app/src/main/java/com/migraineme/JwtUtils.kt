package com.migraineme

import android.util.Base64
import org.json.JSONObject

object JwtUtils {

    /**
     * Extracts the Supabase user id (UUID) from a JWT access token.
     * Supabase tokens include the user id in the "sub" claim.
     *
     * Returns null if the token is not a JWT or cannot be decoded.
     */
    fun extractUserIdFromAccessToken(accessToken: String?): String? {
        if (accessToken.isNullOrBlank()) return null

        return try {
            val parts = accessToken.split(".")
            if (parts.size < 2) return null

            val payloadB64Url = parts[1]
            val payloadJson = String(
                Base64.decode(
                    payloadB64Url
                        .replace('-', '+')
                        .replace('_', '/')
                        .padEnd(((payloadB64Url.length + 3) / 4) * 4, '='),
                    Base64.DEFAULT
                )
            )

            val obj = JSONObject(payloadJson)
            val sub = obj.optString("sub", null)
            if (sub.isNullOrBlank()) null else sub
        } catch (_: Exception) {
            null
        }
    }
}
