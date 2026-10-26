package nodomain.freeyourgadget.gadgetbridge.activities.discovery

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.location.LocationManagerCompat
import nodomain.freeyourgadget.gadgetbridge.BuildConfig

object DiscoveryRequirements {
    enum class Requirement {
        BLUETOOTH_ADAPTER_EXISTS,
        PERMISSIONS_GRANTED,
        LOCATION_ENABLED,
        BLUETOOTH_ENABLED,
    }

    /**
     * BLE scans need location before Android 12. From 12 on, the Beagle Fit Sync build declares
     * BLUETOOTH_SCAN as neverForLocation and has no location permission, so it does not ask.
     */
    private val needsLocation =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !BuildConfig.BEAGLEFIT

    val permissions: List<String> = buildList {
        if (needsLocation) {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    fun missingPermissions(context: Context): List<String> = permissions.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * Returns the first requirement that is not met, or null if discovery can start.
     */
    @JvmStatic
    fun firstUnmet(context: Context): Requirement? {
        val adapter = context.getSystemService<BluetoothManager>()?.adapter
            ?: return Requirement.BLUETOOTH_ADAPTER_EXISTS
        if (missingPermissions(context).isNotEmpty()) {
            return Requirement.PERMISSIONS_GRANTED
        }
        val locationManager = context.getSystemService<LocationManager>()
        if (needsLocation && (locationManager == null || !LocationManagerCompat.isLocationEnabled(locationManager))) {
            return Requirement.LOCATION_ENABLED
        }
        if (!adapter.isEnabled) {
            return Requirement.BLUETOOTH_ENABLED
        }
        return null
    }
}
