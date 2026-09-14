package com.migraineme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * One food the model saw in a meal photo. [grams] is an estimate the user
 * edits before anything is logged, and [alternates] are the other things it
 * could have been, offered when they tap the name.
 */
data class PhotoFoodItem(
    val name: String,
    val grams: Double,
    val confidence: String,
    val alternates: List<String>
)

/**
 * Identifies the foods in a meal photo via the identify-food-photo edge
 * function. The photo is downscaled here, sent once, and never stored.
 */
class FoodPhotoService(private val context: Context) {

    companion object {
        private const val TAG = "FoodPhotoService"
        // The edge function sends the image at "low" detail (512px), so
        // anything past this is bytes on the wire for nothing.
        private const val MAX_EDGE_PX = 768
        private const val JPEG_QUALITY = 80
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /**
     * Read the captured photo, downscale it, and ask the edge function what
     * is on the plate. Returns an empty list when the photo has no food in
     * it — that is a real answer, not an error.
     */
    suspend fun identify(accessToken: String, photoUri: Uri): List<PhotoFoodItem> =
        withContext(Dispatchers.IO) {
            try {
                val base64 = encodePhoto(photoUri) ?: run {
                    Log.e(TAG, "Could not read photo $photoUri")
                    return@withContext emptyList()
                }

                val body = JSONObject().apply {
                    put("image_base64", base64)
                    put("mime_type", "image/jpeg")
                }

                val url = "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/identify-food-photo"
                val request = Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer $accessToken")
                    .header("apikey", BuildConfig.SUPABASE_ANON_KEY)
                    .header("Content-Type", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val raw = response.body?.string()
                    if (!response.isSuccessful || raw == null) {
                        Log.e(TAG, "identify-food-photo failed: ${response.code} - $raw")
                        return@withContext emptyList()
                    }

                    val items = JSONObject(raw).optJSONArray("items") ?: return@withContext emptyList()
                    val out = mutableListOf<PhotoFoodItem>()
                    for (i in 0 until items.length()) {
                        val o = items.optJSONObject(i) ?: continue
                        val name = o.optString("name").trim()
                        if (name.isEmpty()) continue
                        val alts = o.optJSONArray("alternates")
                        out.add(
                            PhotoFoodItem(
                                name = name,
                                grams = o.optDouble("grams", 100.0).takeIf { it > 0 } ?: 100.0,
                                confidence = o.optString("confidence", "low"),
                                alternates = buildList {
                                    if (alts != null) for (j in 0 until alts.length()) {
                                        alts.optString(j).trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                                    }
                                }
                            )
                        )
                    }
                    Log.d(TAG, "Identified ${out.size} food(s) in photo")
                    out
                }
            } catch (e: Exception) {
                Log.e(TAG, "Photo identification failed: ${e.message}", e)
                emptyList()
            }
        }

    /**
     * Downscale to [MAX_EDGE_PX] on the long edge and base64 it. Honours the
     * EXIF rotation the camera wrote, otherwise a portrait plate arrives
     * sideways and the model reads the scale cues wrong.
     */
    private fun encodePhoto(photoUri: Uri): String? {
        // inJustDecodeBounds makes decodeStream return null BY DESIGN — it only
        // fills `bounds` — so the stream has to be null-checked on its own. An
        // elvis on the decode result would bail out on every single photo.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = context.contentResolver.openInputStream(photoUri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= MAX_EDGE_PX) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = context.contentResolver.openInputStream(photoUri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: run {
            Log.e(TAG, "Photo decoded to nothing (${bounds.outWidth}x${bounds.outHeight}, sample=$sample)")
            return null
        }

        val rotation = context.contentResolver.openInputStream(photoUri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        if (rotation != 0f) {
            bitmap = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(rotation) }, true
            )
        }

        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }
}
