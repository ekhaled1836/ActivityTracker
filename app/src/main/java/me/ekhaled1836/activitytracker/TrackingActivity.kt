package me.ekhaled1836.activitytracker

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Resources
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.text.PrecomputedTextCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProviders
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationSettingsResponse
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.gms.tasks.Task
import kotlinx.android.synthetic.main.activity_tracking.*
import me.ekhaled1836.activitytracker.R
import kotlin.math.pow

// TODO Make use of the extended Kalman filter.

class TrackingActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_ACCESS_FINE_LOCATION = 1
        private const val PERMISSION_ACCESS_FINE_LOCATION_PRIORITY = 2
        private const val REQUEST_MAKE_GMS_USABLE = 1
        private const val REQUEST_CHECK_LOCATION_SETTINGS = 2
    }

    private val sharedPreferences by lazy {
        getPreferences(Context.MODE_PRIVATE)
    }

    private val viewModel by lazy {
        ViewModelProviders.of(this).get(TrackingViewModel::class.java)
    }

    private val timeTextMetricParams by lazy {
        TextViewCompat.getTextMetricsParams(tracking_timeText)
    }
    private val paceTextMetricParams by lazy {
        TextViewCompat.getTextMetricsParams(tracking_paceText)
    }
    private val meanPaceTextMetricParams by lazy {
        TextViewCompat.getTextMetricsParams(tracking_meanPaceText)
    }
    private val activeTimeTextMetricParams by lazy {
        TextViewCompat.getTextMetricsParams(tracking_activeTimeText)
    }
    private val distanceTextMetricParams by lazy {
        TextViewCompat.getTextMetricsParams(tracking_distanceText)
    }

    private val broadcastReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val location = intent?.getParcelableExtra<Location>("location")
            if(location != null) {
                processLocation(location)
            }
        }
    }

    private var timeOfStart = 0L

    private val timerHandler = Handler()

    private val timerRunnable by lazy {
        object : Runnable {
            override fun run() {
                val timeDifference = (SystemClock.uptimeMillis() - timeOfStart) / 1000
                val hours = timeDifference / 3600
                val minutes = (timeDifference / 60) - (hours * 60)
                val seconds = timeDifference % 60
                updateTextView("%02d:%02d:%02d".format(hours, minutes, seconds), timeTextMetricParams, tracking_timeText)
                timerHandler.postDelayed(this, 1000)
            }
        }
    }

    private var ttsEngine: TextToSpeech? = null

    private var googleMap: GoogleMap? = null

    private val polyline by lazy {
        googleMap?.addPolyline(PolylineOptions()
                .width(resources.displayMetrics.density * 2)
                .color(ContextCompat.getColor(this, R.color.colorAccent))
                .startCap(RoundCap())
                .endCap(RoundCap())
                .geodesic(true)
                .jointType(JointType.ROUND)
        )
    }

    private val latLngList = ArrayList<LatLng>()

    private var tracking: Boolean = false
    private var previousLocation: Location? = null
    private var activeTime = 0.0
    private var totalDistance = 0.0
    private var cueDistance = 0.0

    private var hasSpeed: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            timeOfStart = savedInstanceState.getLong("timeOfStart", 0L)
            tracking = savedInstanceState.getBoolean("tracking", false)
            previousLocation = savedInstanceState.getParcelable("previousLocation")
            activeTime = savedInstanceState.getDouble("activeTime", 0.0)
            totalDistance = savedInstanceState.getDouble("totalDistance", 0.0)
            cueDistance = savedInstanceState.getDouble("cueDistance", 0.0)
            savedInstanceState.getParcelableArrayList<LatLng>("latLngList")?.forEach { item ->
                latLngList.add(item)
            }
            init()
        } else {
            checkGMSUsable()
        }
    }

    private fun checkGMSUsable() {
        val googleApiAvailability = GoogleApiAvailability.getInstance()

        val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(this)

        if (resultCode == ConnectionResult.SUCCESS) {
            init()
        } else {
            googleApiAvailability.getErrorDialog(this, resultCode,
                REQUEST_MAKE_GMS_USABLE
            ) { _ ->
                requestGMS()
            }.show()
        }
    }

    private fun init() {
        setContentView(R.layout.activity_tracking)

        LocalBroadcastManager.getInstance(this).registerReceiver(
                broadcastReceiver, IntentFilter().apply {
            addAction(TrackingViewModel.ACTION_LOCATION)
        })

        val paceHintTextMetricParams = TextViewCompat.getTextMetricsParams(tracking_paceHintText)
        val meanPaceHintTextMetricParams = TextViewCompat.getTextMetricsParams(tracking_meanPaceHintText)
        val distanceHintTextMetricParams = TextViewCompat.getTextMetricsParams(tracking_distanceHintText)

        if (sharedPreferences.getBoolean("use_metric", true)) {
            updateTextView(resources.getString(R.string.hint_pace), paceHintTextMetricParams, tracking_paceHintText)
            updateTextView(resources.getString(R.string.hint_meanPace), meanPaceHintTextMetricParams, tracking_meanPaceHintText)
            updateTextView(resources.getString(R.string.hint_distance), distanceHintTextMetricParams, tracking_distanceHintText)
        } else {
            updateTextView(resources.getString(R.string.hint_imperial_pace), paceHintTextMetricParams, tracking_paceHintText)
            updateTextView(resources.getString(R.string.hint_imperial_meanPace), meanPaceHintTextMetricParams, tracking_meanPaceHintText)
            updateTextView(resources.getString(R.string.hint_imperial_distance), distanceHintTextMetricParams, tracking_distanceHintText)
            tracking_unitsCheckbox.isChecked = false
        }

        updateText(sharedPreferences.getBoolean("user_metric", true))

        tracking_unitsCheckbox.setOnCheckedChangeListener { _, isChecked ->
            with(sharedPreferences.edit()) {
                putBoolean("use_metric", isChecked)
                apply()
            }
            updateText(isChecked)
            if (isChecked) {
                updateTextView(resources.getString(R.string.hint_pace), paceHintTextMetricParams, tracking_paceHintText)
                updateTextView(resources.getString(R.string.hint_meanPace), meanPaceHintTextMetricParams, tracking_meanPaceHintText)
                updateTextView(resources.getString(R.string.hint_distance), distanceHintTextMetricParams, tracking_distanceHintText)
            } else {
                updateTextView(resources.getString(R.string.hint_imperial_pace), paceHintTextMetricParams, tracking_paceHintText)
                updateTextView(resources.getString(R.string.hint_imperial_meanPace), meanPaceHintTextMetricParams, tracking_meanPaceHintText)
                updateTextView(resources.getString(R.string.hint_imperial_distance), distanceHintTextMetricParams, tracking_distanceHintText)
                tracking_unitsCheckbox.isChecked = false
            }
        }

        if (!viewModel.getLocationAvailability().hasObservers()) {
            viewModel.getLocationAvailability().observeForever { locationAvailability ->
                if (!locationAvailability) {
                    checkLocationSettings()
                }
            }
        }

        (tracking_mapFragment as SupportMapFragment).getMapAsync { googleMap ->
            this.googleMap = googleMap
//            googleMap.mapType = GoogleMap.MAP_TYPE_TERRAIN
            try {
                /*val success = */googleMap?.setMapStyle(MapStyleOptions.loadRawResourceStyle(this,
                    R.raw.style_json
                ))
            } catch (resourceNotFoundException: Resources.NotFoundException) {
            }
            if (latLngList.isNotEmpty()) {
                polyline?.points = latLngList
                googleMap?.moveCamera(CameraUpdateFactory.newLatLng(latLngList.last()))
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    viewModel.fusedLocationProvider.lastLocation.addOnSuccessListener { location ->
                        if (location != null) {
                            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 16F))
                        } else {
                            googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 5F))
                        }
                    }.addOnFailureListener { _ ->
                        googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 5F))
                    }
                } else {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                        PERMISSION_ACCESS_FINE_LOCATION
                    )
                }
            }
        }

        tracking_startStopButton.setOnClickListener { _ ->
            tracking_startStopButton.isEnabled = false
            if (!tracking) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    checkLocationSettings()
                } else {
                    requestLocationPermission()
                }
            } else {
                stopUpdates(false)
            }
        }

        ttsEngine = TextToSpeech(this) { resultCode ->
            if (resultCode == TextToSpeech.ERROR) {
                ttsEngine = null
            }/* else {
                ttsEngine?.setPitch(0.9F)
            }*/
        }

        if (tracking) {
            tracking_startStopButton.isEnabled = false
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                checkLocationSettings()
            } else {
                requestLocationPermission()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_MAKE_GMS_USABLE -> {
                if (resultCode == Activity.RESULT_OK) {
                    checkGMSUsable()
                } else {
                    requestGMS()
                }
            }
            REQUEST_CHECK_LOCATION_SETTINGS -> {
                if (resultCode == Activity.RESULT_OK) {
                    checkLocationSettings()
                } else {
                    val alertDialogBuilder = AlertDialog.Builder(this)
                    alertDialogBuilder.setTitle("Accurate Location")
                    alertDialogBuilder.setMessage("These settings need to be enabled, in order to track your location accurately.")
                    alertDialogBuilder.setPositiveButton("Enable") { _, _ ->
                        checkLocationSettings()
                    }
                    alertDialogBuilder.setNegativeButton("Cancel") { _, _ ->
                        stopUpdates(false)
                    }
                    alertDialogBuilder.setCancelable(false)
                    alertDialogBuilder.show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_ACCESS_FINE_LOCATION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    try {
                        viewModel.fusedLocationProvider.lastLocation.addOnSuccessListener { location ->
                            if (location != null) {
                                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 16F))
                            } else {
                                googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 5F))
                            }
                        }.addOnFailureListener { _ ->
                            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0, 0.0), 5F))
                        }
                    } catch (_: SecurityException) {
                    }
                }
            }
            PERMISSION_ACCESS_FINE_LOCATION_PRIORITY -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    checkLocationSettings()
                } else {
                    requestLocationPermission()
                }
            }
        }
    }

    private fun checkLocationSettings() {
        val locationRequest = LocationRequest().apply {
            interval = 0
            fastestInterval = 0
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            smallestDisplacement = 0.2F
        }

        val locationSettingsRequest = LocationSettingsRequest.Builder().addLocationRequest(locationRequest).build()

        val settingsClient = LocationServices.getSettingsClient(this)

        val locationSettingsTask: Task<LocationSettingsResponse> = settingsClient.checkLocationSettings(locationSettingsRequest)

        locationSettingsTask.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    exception.startResolutionForResult(this@TrackingActivity,
                        REQUEST_CHECK_LOCATION_SETTINGS
                    )
                } catch (sendEx: IntentSender.SendIntentException) {
                    val alertDialogBuilder = AlertDialog.Builder(this)
                    alertDialogBuilder.setTitle("Error")
                    alertDialogBuilder.setMessage("An error occurred.\n" +
                            "Please try again!")
                    alertDialogBuilder.setNeutralButton("Okay") { _, _ -> }
                    alertDialogBuilder.setCancelable(true)
                    alertDialogBuilder.show()
                    stopUpdates(false)
                }
            } else {
                val alertDialogBuilder = AlertDialog.Builder(this)
                alertDialogBuilder.setTitle("Airplane mode")
                alertDialogBuilder.setMessage("It appears Airplane mode is on.\n" +
                        "Please disable it and try again!")
                alertDialogBuilder.setNeutralButton("Okay") { _, _ -> }
                alertDialogBuilder.setCancelable(true)
                alertDialogBuilder.show()
                stopUpdates(false)
            }
        }

        locationSettingsTask.addOnSuccessListener { _ ->
            try {
                viewModel.fusedLocationProvider.requestLocationUpdates(locationRequest, viewModel.getLocationCallback(), null).addOnCompleteListener { locationUpdatesTask ->
                    if (locationUpdatesTask.isSuccessful) {
                        if (!tracking) {
                            if (previousLocation != null) {
                                updateTextView("00:00:00", timeTextMetricParams, tracking_timeText)
                                updateTextView("00:00:00", paceTextMetricParams, tracking_paceText)
                                updateTextView("00:00:00", meanPaceTextMetricParams, tracking_meanPaceText)
                                updateTextView("00:00:00", activeTimeTextMetricParams, tracking_activeTimeText)
                                updateTextView("0.00", distanceTextMetricParams, tracking_distanceText)
                            }
                            timeOfStart = SystemClock.uptimeMillis()
                            tracking = true
                            tracking_startStopButton.text = resources.getString(R.string.action_stopTracking)
                        }
                        timerHandler.postDelayed(timerRunnable, 1000)
                        tracking_startStopButton.isEnabled = true
                    } else {
                        stopUpdates(false)
                    }
                }
            } catch (exception: SecurityException) {
                requestLocationPermission()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun processLocation(location: Location) {
        if (previousLocation != null) {
            if (location.hasSpeed() && location.speed > 0.2) {
                hasSpeed = true
                val latLng = LatLng(location.latitude, location.longitude)
                latLngList.add(latLng)

                val timeDifference = (location.elapsedRealtimeNanos - previousLocation!!.elapsedRealtimeNanos) / 1000000000
                activeTime += timeDifference

                val activeHours = (activeTime / 3600).toInt()
                val activeMinutes = ((activeTime / 60)).toInt() - (activeHours * 60)
                val activeSeconds = (activeTime % 60).toInt()

                val distance = timeDifference * location.speed
//                val distance = location.distanceTo(previousLocation!!)
                totalDistance += distance
                cueDistance += distance
                val displayDistance = if (sharedPreferences.getBoolean("use_metric", true)) totalDistance else totalDistance * 3.28084

                val meanPace = activeTime / totalDistance * if (sharedPreferences.getBoolean("use_metric", true)) 1000.0 else 1000.0 / 0.621371
                val meanPaceHours = (meanPace / 3600).toInt()
                val meanPaceMinutes = ((meanPace / 60)).toInt() - (meanPaceHours * 60)
                val meanPaceSeconds = (meanPace % 60).toInt()

                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    polyline?.points = latLngList
                    googleMap?.moveCamera(CameraUpdateFactory.newLatLng(latLng))

                    val pace = (location.speed.pow(-1) * if (sharedPreferences.getBoolean("use_metric", true)) 1000.0 else 1000.0 / 0.621371).toInt()
                    val paceHours = pace / 3600
                    val paceMinutes = (pace / 60) - (paceHours * 60)
                    val paceSeconds = pace % 60
                    updateTextView("%02d:%02d:%02d".format(paceHours, paceMinutes, paceSeconds), paceTextMetricParams, tracking_paceText)
                    updateTextView("%02d:%02d:%02d".format(activeHours, activeMinutes, activeSeconds), activeTimeTextMetricParams, tracking_activeTimeText)
                    updateTextView("%.2f".format(displayDistance), distanceTextMetricParams, tracking_distanceText)
                    updateTextView("%02d:%02d:%02d".format(meanPaceHours, meanPaceMinutes, meanPaceSeconds), meanPaceTextMetricParams, tracking_meanPaceText)
                }

                if (cueDistance > 500) {
                    cueDistance = totalDistance % 500
                    if (ttsEngine != null) {
                        if (sharedPreferences.getBoolean("use_metric", true)) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                ttsEngine!!.speak("active time: %02d hours %02d minutes %02d seconds".format(activeHours, activeMinutes, activeSeconds), TextToSpeech.QUEUE_FLUSH, null, "activeTime")
                                ttsEngine!!.speak("distance: %.02f meters".format(displayDistance), TextToSpeech.QUEUE_ADD, null, "distance")
                                ttsEngine!!.speak("average pace: %02d hours %02d minutes %02d seconds per kilometer".format(meanPaceHours, meanPaceMinutes, meanPaceSeconds), TextToSpeech.QUEUE_ADD, null, "meanPace")
                            } else {
                                val activeTimeMap = hashMapOf(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to "activeTime")
                                val distanceMap = hashMapOf(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to "distance")
                                val meanPaceMap = hashMapOf(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to "meanPace")
                                ttsEngine!!.speak("active time: %02d hours %02d minutes %02d seconds".format(activeHours, activeMinutes, activeSeconds), TextToSpeech.QUEUE_FLUSH, activeTimeMap)
                                ttsEngine!!.speak("distance: %.02f meters".format(displayDistance), TextToSpeech.QUEUE_ADD, distanceMap)
                                ttsEngine!!.speak("average pace: %02d hours %02d minutes %02d seconds per kilometer".format(meanPaceHours, meanPaceMinutes, meanPaceSeconds), TextToSpeech.QUEUE_ADD, meanPaceMap)
                            }
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                ttsEngine!!.speak("active time: %02d hours %02d minutes %02d seconds".format(activeHours, activeMinutes, activeSeconds), TextToSpeech.QUEUE_FLUSH, null, "activeTime")
                                ttsEngine!!.speak("distance: %.02f feet".format(displayDistance), TextToSpeech.QUEUE_ADD, null, "distance")
                                ttsEngine!!.speak("average pace: %02d hours %02d minutes %02d seconds per mile".format(meanPaceHours, meanPaceMinutes, meanPaceSeconds), TextToSpeech.QUEUE_ADD, null, "meanPace")
                            } else {
                                val activeTimeMap = hashMapOf(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to "activeTime")
                                val distanceMap = hashMapOf(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to "distance")
                                val meanPaceMap = hashMapOf(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to "meanPace")
                                ttsEngine!!.speak("active time: %02d hours %02d minutes %02d seconds".format(activeHours, activeMinutes, activeSeconds), TextToSpeech.QUEUE_FLUSH, activeTimeMap)
                                ttsEngine!!.speak("distance: %.02f feet".format(displayDistance), TextToSpeech.QUEUE_ADD, distanceMap)
                                ttsEngine!!.speak("average pace: %02d hours %02d minutes %02d seconds per mile".format(meanPaceHours, meanPaceMinutes, meanPaceSeconds), TextToSpeech.QUEUE_ADD, meanPaceMap)
                            }
                        }
                    }
                }
            } else {
                checkLocationSettings()
                if (hasSpeed && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    updateTextView("%02d:%02d:%02d".format(0, 0, 0), paceTextMetricParams, tracking_paceText)
                }
                hasSpeed = false
            }
        } else {
            latLngList.clear()
            timeOfStart = 0
            previousLocation = null
            activeTime = 0.0
            totalDistance = 0.0
            cueDistance = 0.0
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (location.hasSpeed() && location.speed > 0.2) {
                    val pace = (location.speed.pow(-1) * if (sharedPreferences.getBoolean("use_metric", true)) 1000.0 else 1000.0 / 0.621371).toInt()
                    val paceHours = pace / 3600
                    val paceMinutes = (pace / 60) - (paceHours * 60)
                    val paceSeconds = pace % 60
                    updateTextView("%02d:%02d:%02d".format(paceHours, paceMinutes, paceSeconds), paceTextMetricParams, tracking_paceText)
                    hasSpeed = true
                }
            }
        }
        previousLocation = location
    }

    private fun updateText(metric: Boolean) {
        if(previousLocation != null) {
            if (previousLocation!!.hasSpeed() && previousLocation!!.speed > 0.2) {
                val pace = (previousLocation!!.speed.pow(-1) * if (metric) 1000.0 else 1000.0 / 0.621371).toInt()
                val paceHours = pace / 3600
                val paceMinutes = (pace / 60) - (paceHours * 60)
                val paceSeconds = pace % 60
                updateTextView("%02d:%02d:%02d".format(paceHours, paceMinutes, paceSeconds), paceTextMetricParams, tracking_paceText)
                hasSpeed = true
            }
            if(totalDistance > 0.2) {
                val activeHours = (activeTime / 3600).toInt()
                val activeMinutes = ((activeTime / 60)).toInt() - (activeHours * 60)
                val activeSeconds = (activeTime % 60).toInt()

                val displayDistance = if (metric) totalDistance else totalDistance * 3.28084

                val meanPace = activeTime / totalDistance * if (metric) 1000.0 else 1000.0 / 0.621371
                val meanPaceHours = (meanPace / 3600).toInt()
                val meanPaceMinutes = ((meanPace / 60)).toInt() - (meanPaceHours * 60)
                val meanPaceSeconds = (meanPace % 60).toInt()

                updateTextView("%02d:%02d:%02d".format(activeHours, activeMinutes, activeSeconds), activeTimeTextMetricParams, tracking_activeTimeText)
                updateTextView("%.2f".format(displayDistance), distanceTextMetricParams, tracking_distanceText)
                updateTextView("%02d:%02d:%02d".format(meanPaceHours, meanPaceMinutes, meanPaceSeconds), meanPaceTextMetricParams, tracking_meanPaceText)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("timeOfStart", timeOfStart)
        outState.putBoolean("tracking", tracking)
        outState.putParcelable("previousLocation", previousLocation)
        outState.putDouble("activeTime", activeTime)
        outState.putDouble("totalDistance", totalDistance)
        outState.putDouble("cueDistance", cueDistance)
        outState.putParcelableArrayList("latLngList", latLngList)
    }

    private fun stopUpdates(finishing: Boolean) {
        if (tracking) {
            viewModel.fusedLocationProvider.removeLocationUpdates(viewModel.getLocationCallback())
            if (!finishing) {
                tracking = false
                tracking_startStopButton.text = resources.getString(R.string.action_startTracking)
                timerHandler.removeCallbacks(timerRunnable)
            }
        }
        tracking_startStopButton.isEnabled = true
    }

    private fun updateTextView(text: String, metricParams: PrecomputedTextCompat.Params, textView: AppCompatTextView) {
        val precomputedText = PrecomputedTextCompat.create(text, metricParams)
        TextViewCompat.setPrecomputedText(textView, precomputedText)
    }

    override fun onBackPressed() {
        if (tracking) {
            val alertDialogBuilder = AlertDialog.Builder(this)
            alertDialogBuilder.setTitle("Confirm Exit")
            alertDialogBuilder.setMessage("You are in the middle of a workout.\n" +
                    "Do you really want to exit?")
            alertDialogBuilder.setPositiveButton("No") { _, _ -> }
            alertDialogBuilder.setNegativeButton("Yes") { _, _ ->
                super.onBackPressed()
            }
            alertDialogBuilder.setCancelable(true)
            alertDialogBuilder.show()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsEngine?.shutdown()
        if (tracking) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver)
            timerHandler.removeCallbacks(timerRunnable)
        }
        if (isFinishing) stopUpdates(true) // onDestroy is called only for one of two reasons: 1.finish() 2.Configuration change.
        Log.e("TrackingActivity", "onDestroy")
    }

    private fun requestGMS() {
        val alertDialogBuilder = AlertDialog.Builder(this)
        alertDialogBuilder.setTitle("Google Play Services")
        alertDialogBuilder.setMessage("The app requires Google Play Services to function.")
        alertDialogBuilder.setPositiveButton("Okay") { _, _ ->
            checkGMSUsable()
        }
        alertDialogBuilder.setNegativeButton("Exit") { _, _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask()
            } else {
                finish()
            }
        }
        alertDialogBuilder.setCancelable(false)
        alertDialogBuilder.show()
    }

    private fun requestLocationPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            val alertDialogBuilder = AlertDialog.Builder(this)
            alertDialogBuilder.setTitle("Location Permission")
            alertDialogBuilder.setMessage("The app requires location access, to be able to track your activity.")
            alertDialogBuilder.setPositiveButton("Grant") { _, _ ->
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    PERMISSION_ACCESS_FINE_LOCATION_PRIORITY
                )
            }
            alertDialogBuilder.setNegativeButton("Cancel") { _, _ ->
                stopUpdates(false)
            }
            alertDialogBuilder.setCancelable(false)
            alertDialogBuilder.show()
        } else {
            val alertDialogBuilder = AlertDialog.Builder(this)
            alertDialogBuilder.setTitle("Location Permission")
            alertDialogBuilder.setMessage("I'm sorry, you chose not to be asked again, but the app can't function without location access.\nYou can enable it in settings.")
            alertDialogBuilder.setPositiveButton("Settings") { _, _ ->
                val settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                settingsIntent.data = Uri.parse("package:$packageName")
                startActivity(intent)
                tracking_startStopButton.isEnabled = true
            }
            alertDialogBuilder.setNegativeButton("Cancel") { _, _ ->
                stopUpdates(false)
            }
            alertDialogBuilder.setCancelable(false)
            alertDialogBuilder.show()
        }
    }
}