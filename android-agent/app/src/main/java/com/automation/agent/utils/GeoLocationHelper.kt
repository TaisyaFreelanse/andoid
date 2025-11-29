package com.automation.agent.utils

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * GeoLocationHelper - Determines geolocation from IP address
 * 
 * Uses free IP geolocation APIs to determine:
 * - Country code
 * - City
 * - Timezone
 * - Coordinates (latitude/longitude)
 * 
 * Useful for:
 * - Auto-setting timezone based on proxy location
 * - Auto-setting GPS coordinates based on proxy
 */
class GeoLocationHelper(
    private val httpClient: OkHttpClient? = null
) {

    companion object {
        private const val TAG = "GeoLocationHelper"
        
        // Free IP geolocation APIs
        private const val IPAPI_URL = "http://ip-api.com/json/"
        private const val IPINFO_URL = "https://ipinfo.io/json"
        private const val IPWHO_URL = "https://ipwho.is/"
        
        // Timeout
        private const val TIMEOUT_SECONDS = 10L
    }

    private val client: OkHttpClient by lazy {
        httpClient ?: OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()

    // ==================== IP Geolocation ====================

    /**
     * Get geolocation for current IP
     */
    suspend fun getCurrentIpLocation(): GeoLocation? {
        return getIpLocation(null)
    }

    /**
     * Get geolocation for specific IP
     */
    suspend fun getIpLocation(ip: String? = null): GeoLocation? {
        // Try multiple APIs in case one fails
        return tryIpApi(ip) 
            ?: tryIpInfo(ip) 
            ?: tryIpWho(ip)
    }

    /**
     * Try ip-api.com
     */
    private suspend fun tryIpApi(ip: String?): GeoLocation? = withContext(Dispatchers.IO) {
        try {
            val url = if (ip != null) "$IPAPI_URL$ip" else IPAPI_URL
            val request = Request.Builder().url(url).build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                
                val json = response.body?.string() ?: return@withContext null
                val data = gson.fromJson(json, IpApiResponse::class.java)
                
                if (data.status != "success") return@withContext null
                
                GeoLocation(
                    ip = data.query ?: ip ?: "",
                    countryCode = data.countryCode ?: "",
                    country = data.country ?: "",
                    region = data.regionName ?: "",
                    city = data.city ?: "",
                    timezone = data.timezone ?: "",
                    latitude = data.lat ?: 0.0,
                    longitude = data.lon ?: 0.0,
                    isp = data.isp ?: "",
                    org = data.org ?: ""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "ip-api.com failed: ${e.message}")
            null
        }
    }

    /**
     * Try ipinfo.io
     */
    private suspend fun tryIpInfo(ip: String?): GeoLocation? = withContext(Dispatchers.IO) {
        try {
            val url = if (ip != null) "https://ipinfo.io/$ip/json" else IPINFO_URL
            val request = Request.Builder().url(url).build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                
                val json = response.body?.string() ?: return@withContext null
                val data = gson.fromJson(json, IpInfoResponse::class.java)
                
                // Parse coordinates
                val coords = data.loc?.split(",")
                val lat = coords?.getOrNull(0)?.toDoubleOrNull() ?: 0.0
                val lng = coords?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                
                GeoLocation(
                    ip = data.ip ?: ip ?: "",
                    countryCode = data.country ?: "",
                    country = data.country ?: "",
                    region = data.region ?: "",
                    city = data.city ?: "",
                    timezone = data.timezone ?: "",
                    latitude = lat,
                    longitude = lng,
                    isp = data.org ?: "",
                    org = data.org ?: ""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "ipinfo.io failed: ${e.message}")
            null
        }
    }

    /**
     * Try ipwho.is
     */
    private suspend fun tryIpWho(ip: String?): GeoLocation? = withContext(Dispatchers.IO) {
        try {
            val url = if (ip != null) "$IPWHO_URL$ip" else IPWHO_URL
            val request = Request.Builder().url(url).build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                
                val json = response.body?.string() ?: return@withContext null
                val data = gson.fromJson(json, IpWhoResponse::class.java)
                
                if (data.success != true) return@withContext null
                
                GeoLocation(
                    ip = data.ip ?: ip ?: "",
                    countryCode = data.countryCode ?: "",
                    country = data.country ?: "",
                    region = data.region ?: "",
                    city = data.city ?: "",
                    timezone = data.timezone?.id ?: "",
                    latitude = data.latitude ?: 0.0,
                    longitude = data.longitude ?: 0.0,
                    isp = data.connection?.isp ?: "",
                    org = data.connection?.org ?: ""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "ipwho.is failed: ${e.message}")
            null
        }
    }

    // ==================== Timezone Helpers ====================

    /**
     * Get timezone for country code
     */
    fun getTimezoneForCountry(countryCode: String): String? {
        return COUNTRY_TIMEZONES[countryCode.uppercase()]?.firstOrNull()
    }

    /**
     * Get all timezones for country code
     */
    fun getAllTimezonesForCountry(countryCode: String): List<String> {
        return COUNTRY_TIMEZONES[countryCode.uppercase()] ?: emptyList()
    }

    /**
     * Validate timezone string
     */
    fun isValidTimezone(timezone: String): Boolean {
        return try {
            java.util.TimeZone.getTimeZone(timezone).id == timezone
        } catch (e: Exception) {
            false
        }
    }

    // ==================== Data Classes ====================

    data class GeoLocation(
        val ip: String,
        val countryCode: String,
        val country: String,
        val region: String,
        val city: String,
        val timezone: String,
        val latitude: Double,
        val longitude: Double,
        val isp: String = "",
        val org: String = ""
    )

    // API Response classes
    private data class IpApiResponse(
        val status: String?,
        val query: String?,
        val countryCode: String?,
        val country: String?,
        val regionName: String?,
        val city: String?,
        val timezone: String?,
        val lat: Double?,
        val lon: Double?,
        val isp: String?,
        val org: String?
    )

    private data class IpInfoResponse(
        val ip: String?,
        val country: String?,
        val region: String?,
        val city: String?,
        val loc: String?,
        val timezone: String?,
        val org: String?
    )

    private data class IpWhoResponse(
        val success: Boolean?,
        val ip: String?,
        @SerializedName("country_code")
        val countryCode: String?,
        val country: String?,
        val region: String?,
        val city: String?,
        val latitude: Double?,
        val longitude: Double?,
        val timezone: IpWhoTimezone?,
        val connection: IpWhoConnection?
    )

    private data class IpWhoTimezone(
        val id: String?,
        val utc: String?
    )

    private data class IpWhoConnection(
        val isp: String?,
        val org: String?
    )

    // ==================== Country Timezones ====================

    companion object {
        private val COUNTRY_TIMEZONES = mapOf(
            "US" to listOf("America/New_York", "America/Chicago", "America/Denver", "America/Los_Angeles", "America/Phoenix", "America/Anchorage", "Pacific/Honolulu"),
            "CA" to listOf("America/Toronto", "America/Vancouver", "America/Edmonton", "America/Winnipeg", "America/Halifax"),
            "GB" to listOf("Europe/London"),
            "DE" to listOf("Europe/Berlin"),
            "FR" to listOf("Europe/Paris"),
            "IT" to listOf("Europe/Rome"),
            "ES" to listOf("Europe/Madrid"),
            "NL" to listOf("Europe/Amsterdam"),
            "BE" to listOf("Europe/Brussels"),
            "AT" to listOf("Europe/Vienna"),
            "CH" to listOf("Europe/Zurich"),
            "PL" to listOf("Europe/Warsaw"),
            "CZ" to listOf("Europe/Prague"),
            "SE" to listOf("Europe/Stockholm"),
            "NO" to listOf("Europe/Oslo"),
            "DK" to listOf("Europe/Copenhagen"),
            "FI" to listOf("Europe/Helsinki"),
            "RU" to listOf("Europe/Moscow", "Asia/Yekaterinburg", "Asia/Novosibirsk", "Asia/Krasnoyarsk", "Asia/Irkutsk", "Asia/Vladivostok"),
            "UA" to listOf("Europe/Kiev"),
            "TR" to listOf("Europe/Istanbul"),
            "GR" to listOf("Europe/Athens"),
            "RO" to listOf("Europe/Bucharest"),
            "HU" to listOf("Europe/Budapest"),
            "PT" to listOf("Europe/Lisbon"),
            "IE" to listOf("Europe/Dublin"),
            "JP" to listOf("Asia/Tokyo"),
            "KR" to listOf("Asia/Seoul"),
            "CN" to listOf("Asia/Shanghai"),
            "HK" to listOf("Asia/Hong_Kong"),
            "TW" to listOf("Asia/Taipei"),
            "SG" to listOf("Asia/Singapore"),
            "MY" to listOf("Asia/Kuala_Lumpur"),
            "TH" to listOf("Asia/Bangkok"),
            "VN" to listOf("Asia/Ho_Chi_Minh"),
            "ID" to listOf("Asia/Jakarta", "Asia/Makassar", "Asia/Jayapura"),
            "PH" to listOf("Asia/Manila"),
            "IN" to listOf("Asia/Kolkata"),
            "PK" to listOf("Asia/Karachi"),
            "BD" to listOf("Asia/Dhaka"),
            "AE" to listOf("Asia/Dubai"),
            "SA" to listOf("Asia/Riyadh"),
            "IL" to listOf("Asia/Jerusalem"),
            "AU" to listOf("Australia/Sydney", "Australia/Melbourne", "Australia/Brisbane", "Australia/Perth", "Australia/Adelaide"),
            "NZ" to listOf("Pacific/Auckland"),
            "BR" to listOf("America/Sao_Paulo", "America/Rio_Branco", "America/Manaus"),
            "AR" to listOf("America/Argentina/Buenos_Aires"),
            "MX" to listOf("America/Mexico_City", "America/Tijuana", "America/Cancun"),
            "CO" to listOf("America/Bogota"),
            "CL" to listOf("America/Santiago"),
            "PE" to listOf("America/Lima"),
            "ZA" to listOf("Africa/Johannesburg"),
            "EG" to listOf("Africa/Cairo"),
            "NG" to listOf("Africa/Lagos"),
            "KE" to listOf("Africa/Nairobi"),
            "MA" to listOf("Africa/Casablanca")
        )
    }
}

