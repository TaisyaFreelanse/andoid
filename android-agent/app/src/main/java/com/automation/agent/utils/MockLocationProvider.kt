package com.automation.agent.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.LocationProvider
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.*

/**
 * MockLocationProvider - Provides fake GPS coordinates
 * 
 * Features:
 * - Set mock location for GPS and Network providers
 * - Continuous location updates
 * - Random location jitter for realism
 * - Country-based location presets
 * 
 * Requirements:
 * - Developer options enabled
 * - Mock location app selected
 * - Or root access for system-level mock
 */
class MockLocationProvider(
    private val context: Context,
    private val rootUtils: RootUtils? = null
) {

    companion object {
        private const val TAG = "MockLocationProvider"
        
        // Provider names
        const val GPS_PROVIDER = LocationManager.GPS_PROVIDER
        const val NETWORK_PROVIDER = LocationManager.NETWORK_PROVIDER
        const val FUSED_PROVIDER = "fused"
        
        // Default accuracy
        private const val DEFAULT_ACCURACY = 10f // meters
        private const val DEFAULT_ALTITUDE = 100.0 // meters
        private const val DEFAULT_SPEED = 0f
        private const val DEFAULT_BEARING = 0f
        
        // Update interval
        private const val UPDATE_INTERVAL = 1000L // 1 second
        
        // Jitter for realism
        private const val MAX_JITTER = 0.0001 // ~11 meters
    }

    private val locationManager: LocationManager by lazy {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private var mockJob: Job? = null
    private var currentLatitude: Double = 0.0
    private var currentLongitude: Double = 0.0
    private var isRunning = false

    // ==================== Setup ====================

    /**
     * Check if mock location is allowed
     */
    fun isMockLocationAllowed(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Check if we're the selected mock location app
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    "mock_location"
                ) == "1"
            } else {
                android.provider.Settings.Secure.getInt(
                    context.contentResolver,
                    android.provider.Settings.Secure.ALLOW_MOCK_LOCATION,
                    0
                ) == 1
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking mock location permission: ${e.message}")
            false
        }
    }

    /**
     * Check if location permission is granted
     */
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Enable mock location provider via root
     */
    suspend fun enableMockLocationViaRoot(): Boolean {
        if (rootUtils == null) return false
        
        // Enable mock location in settings
        rootUtils.setSecureSetting("mock_location", "1")
        
        // Set our app as mock location app
        rootUtils.setSecureSetting("mock_location_app", context.packageName)
        
        return true
    }

    /**
     * Add test provider
     */
    private fun addTestProvider(providerName: String): Boolean {
        return try {
            // Remove existing test provider if any
            try {
                locationManager.removeTestProvider(providerName)
            } catch (e: Exception) {
                // Ignore if provider doesn't exist
            }

            // Add new test provider
            locationManager.addTestProvider(
                providerName,
                false, // requiresNetwork
                false, // requiresSatellite
                false, // requiresCell
                false, // hasMonetaryCost
                true,  // supportsAltitude
                true,  // supportsSpeed
                true,  // supportsBearing
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE
            )

            locationManager.setTestProviderEnabled(providerName, true)
            
            Log.d(TAG, "Test provider added: $providerName")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception adding test provider: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error adding test provider: ${e.message}")
            false
        }
    }

    // ==================== Mock Location ====================

    /**
     * Set mock location
     */
    fun setMockLocation(
        latitude: Double,
        longitude: Double,
        accuracy: Float = DEFAULT_ACCURACY,
        altitude: Double = DEFAULT_ALTITUDE
    ): Boolean {
        currentLatitude = latitude
        currentLongitude = longitude

        val gpsResult = setMockLocationForProvider(GPS_PROVIDER, latitude, longitude, accuracy, altitude)
        val networkResult = setMockLocationForProvider(NETWORK_PROVIDER, latitude, longitude, accuracy, altitude)

        return gpsResult || networkResult
    }

    /**
     * Set mock location for specific provider
     */
    private fun setMockLocationForProvider(
        providerName: String,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        altitude: Double
    ): Boolean {
        return try {
            // Add test provider if not exists
            if (!addTestProvider(providerName)) {
                return false
            }

            val location = createMockLocation(providerName, latitude, longitude, accuracy, altitude)
            locationManager.setTestProviderLocation(providerName, location)
            
            Log.d(TAG, "Mock location set for $providerName: $latitude, $longitude")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception setting mock location: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error setting mock location: ${e.message}")
            false
        }
    }

    /**
     * Create mock location object
     */
    private fun createMockLocation(
        provider: String,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        altitude: Double
    ): Location {
        return Location(provider).apply {
            this.latitude = latitude
            this.longitude = longitude
            this.accuracy = accuracy
            this.altitude = altitude
            this.speed = DEFAULT_SPEED
            this.bearing = DEFAULT_BEARING
            this.time = System.currentTimeMillis()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                this.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                this.bearingAccuracyDegrees = 0f
                this.speedAccuracyMetersPerSecond = 0f
                this.verticalAccuracyMeters = accuracy
            }
        }
    }

    // ==================== Continuous Updates ====================

    /**
     * Start continuous mock location updates
     */
    fun startMockLocationUpdates(
        latitude: Double,
        longitude: Double,
        addJitter: Boolean = true,
        intervalMs: Long = UPDATE_INTERVAL
    ) {
        stopMockLocationUpdates()
        
        currentLatitude = latitude
        currentLongitude = longitude
        isRunning = true

        mockJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive && isRunning) {
                val lat = if (addJitter) addRandomJitter(currentLatitude) else currentLatitude
                val lng = if (addJitter) addRandomJitter(currentLongitude) else currentLongitude
                
                setMockLocation(lat, lng)
                delay(intervalMs)
            }
        }
        
        Log.i(TAG, "Started continuous mock location updates at $latitude, $longitude")
    }

    /**
     * Stop continuous mock location updates
     */
    fun stopMockLocationUpdates() {
        isRunning = false
        mockJob?.cancel()
        mockJob = null
        
        Log.i(TAG, "Stopped mock location updates")
    }

    /**
     * Update current location (while updates are running)
     */
    fun updateLocation(latitude: Double, longitude: Double) {
        currentLatitude = latitude
        currentLongitude = longitude
    }

    /**
     * Add random jitter to coordinate for realism
     */
    private fun addRandomJitter(coordinate: Double): Double {
        val jitter = (Random().nextDouble() - 0.5) * 2 * MAX_JITTER
        return coordinate + jitter
    }

    // ==================== Cleanup ====================

    /**
     * Remove test providers
     */
    fun removeTestProviders() {
        stopMockLocationUpdates()
        
        try {
            locationManager.removeTestProvider(GPS_PROVIDER)
        } catch (e: Exception) {
            // Ignore
        }
        
        try {
            locationManager.removeTestProvider(NETWORK_PROVIDER)
        } catch (e: Exception) {
            // Ignore
        }
        
        Log.i(TAG, "Test providers removed")
    }

    /**
     * Disable mock location via root
     */
    suspend fun disableMockLocationViaRoot(): Boolean {
        if (rootUtils == null) return false
        
        rootUtils.setSecureSetting("mock_location", "0")
        rootUtils.deleteSetting("secure", "mock_location_app")
        
        return true
    }

    // ==================== Utility ====================

    /**
     * Get current mock location
     */
    fun getCurrentMockLocation(): Pair<Double, Double>? {
        return if (isRunning) {
            Pair(currentLatitude, currentLongitude)
        } else {
            null
        }
    }

    /**
     * Check if mock location is running
     */
    fun isRunning(): Boolean = isRunning

    /**
     * Calculate distance between two points (Haversine formula)
     */
    fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val r = 6371000.0 // Earth's radius in meters
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        
        return r * c
    }

    /**
     * Generate random location within radius
     */
    fun generateRandomLocationInRadius(
        centerLat: Double,
        centerLng: Double,
        radiusMeters: Double
    ): Pair<Double, Double> {
        val random = Random()
        
        // Convert radius to degrees (approximate)
        val radiusDegrees = radiusMeters / 111000.0 // ~111km per degree
        
        // Generate random offset
        val u = random.nextDouble()
        val v = random.nextDouble()
        val w = radiusDegrees * Math.sqrt(u)
        val t = 2 * Math.PI * v
        
        val x = w * Math.cos(t)
        val y = w * Math.sin(t)
        
        // Adjust for latitude
        val newLat = centerLat + y
        val newLng = centerLng + x / Math.cos(Math.toRadians(centerLat))
        
        return Pair(newLat, newLng)
    }
}

