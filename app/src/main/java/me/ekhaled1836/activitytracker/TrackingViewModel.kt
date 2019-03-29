package me.ekhaled1836.activitytracker

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.*

class TrackingViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val ACTION_LOCATION = "me.ekhaled1836.activitytracker.action.location"
        const val EXTRA_LOCATION = "me.ekhaled1836.activitytracker.extra.location"
    }

//    private val locationUpdate = MutableLiveData<Location>()

    private val locationAvailabilityLiveData = MutableLiveData<Boolean>()

    private val localBroadcastManager by lazy {
        LocalBroadcastManager.getInstance(application)
    }

    val fusedLocationProvider: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(application)
    }

    private val locationCallback by lazy {
        object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult?) {
                locationResult ?: return
                for (location in locationResult.locations) {
                    val intent = Intent(ACTION_LOCATION)
                    intent.putExtra(EXTRA_LOCATION, location)
                    localBroadcastManager.sendBroadcast(intent)
//                    locationUpdate.postValue(location)
                }
            }

            override fun onLocationAvailability(locationAvailability: LocationAvailability?) {
                super.onLocationAvailability(locationAvailability)
                locationAvailabilityLiveData.postValue(locationAvailability?.isLocationAvailable == true)
            }
        }
    }

    fun getLocationCallback(): LocationCallback {
        return locationCallback
    }

    fun getLocationAvailability(): LiveData<Boolean> {
        return locationAvailabilityLiveData
    }

    /*fun getLocationUpdate(): LiveData<Location> {
        return locationUpdate
    }*/

    /*override fun onCleared() {
        super.onCleared()
    }*/
}