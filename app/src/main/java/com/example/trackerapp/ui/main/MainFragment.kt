package com.example.trackerapp.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.trackerapp.R
import com.example.trackerapp.application.TrackerApp
import com.example.trackerapp.databinding.FragmentMainBinding
import com.example.trackerapp.service.LocationForegroundService
import org.osmdroid.api.IMapController
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.compass.CompassOverlay
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

class MainFragment : Fragment() {

    companion object {
        fun newInstance() = MainFragment()
    }

    private val viewModel: MainViewModel by viewModels { TrackerApp.instance().vmFactory }
    private lateinit var binding: FragmentMainBinding
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private lateinit var mapController: IMapController
    private lateinit var locationOverlay: MyLocationNewOverlay
    private lateinit var compassOverlay: CompassOverlay
    private lateinit var scaleBarOverlay: ScaleBarOverlay
    private lateinit var polylineOverlay: Polyline

    private val delay: Long = 5_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config()
        check()
    }

    private fun config() {
        Configuration.getInstance().userAgentValue = TrackerApp.instance().packageName
    }

    private fun check() {
        // Location & Notification Permissions
        val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val status = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false)
            viewModel.onRequestResult(status)
        }
        permissionLauncher.launch(permissions)

        // Location Enabled
        if (isLocationEnabled()) { viewModel.onLocationEnabled() } else { showLocationRequest() }
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun isLocationEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // API >= 28 / Android 9+
            val locationManager = TrackerApp.instance().getSystemService(Context.LOCATION_SERVICE) as LocationManager
            locationManager.isLocationEnabled
        } else {
            // API < 28
            val mode: Int = Settings.Secure.getInt(TrackerApp.instance().contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF)
            (mode != Settings.Secure.LOCATION_MODE_OFF)
        }

    private fun showLocationRequest() {
        AlertDialog.Builder(this.requireContext())
            .setTitle(R.string.gps_not_found_title)
            .setMessage(R.string.gps_not_found_message)
            .setPositiveButton(R.string.gps_yes) { _, _ -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            .setNegativeButton(R.string.gps_no, null)
            .show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
    }

    private fun init() {
        mapController = binding.map.controller
        binding.btnStart.isActivated = false
        binding.btnStart.setOnClickListener {
            viewModel.onTrackingStart()
        }
        binding.btnStop.setOnClickListener {
            viewModel.onTrackingStop()
        }
        drawMap()
        drawOverlays()
        start()
    }

    private fun drawMap() {
        binding.map.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.ALWAYS)
            setMultiTouchControls(true)
        }
        mapController.apply {
            setZoom(18.0)
            setCenter(viewModel.lastLocation.value)
        }

        viewModel.onMapReady()
    }

    private fun drawOverlays() {
        locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(TrackerApp.instance()), binding.map).apply {
            setPersonIcon(null)
        }
        compassOverlay = CompassOverlay(TrackerApp.instance(), InternalCompassOrientationProvider(TrackerApp.instance()), binding.map)
        scaleBarOverlay = ScaleBarOverlay(binding.map).apply {
            setCentred(true)
            setScaleBarOffset(TrackerApp.instance().resources.displayMetrics.widthPixels / 2, 10)
        }
        polylineOverlay = Polyline(binding.map).apply {
            outlinePaint.color = ContextCompat.getColor(TrackerApp.instance(), R.color.red)
        }
        binding.map.overlays.addAll(listOf(polylineOverlay, locationOverlay, compassOverlay, scaleBarOverlay))
        binding.map.invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun start() {
        viewModel.mapReady.observe(viewLifecycleOwner) { isMapReady ->
            when(isMapReady) {
                false -> binding.map.visibility = INVISIBLE
                true -> binding.map.visibility = VISIBLE
            }
        }
        viewModel.permissionsGranted.observe(viewLifecycleOwner) { isPermissionsGranted ->
            when (isPermissionsGranted) {
                false -> {}
                true -> { viewModel.locationEnabled.observe(viewLifecycleOwner) { isLocationEnabled ->
                    when (isLocationEnabled) {
                        false -> {}
                        true -> {
                            viewModel.onTrackingReady()
                        }
                    }
                }
                }
            }
        }
    }

    private fun enableTracking() {
        viewModel.trackingReady.observe(viewLifecycleOwner) { isReady ->
            when(isReady) {
                false -> {}
                true -> {
                    viewModel.lastLocation.observe(viewLifecycleOwner) {
                        Handler(Looper.getMainLooper())
                            .apply { removeCallbacksAndMessages(null) }
                            .postDelayed({
                                locationOverlay.enableMyLocation()
                                locationOverlay.enableFollowLocation()
                            }, delay)
                    }
                    binding.btnStart.isActivated = true

                    val intent = Intent(requireContext(), LocationForegroundService::class.java)
                    viewModel.trackingStart.observe(viewLifecycleOwner) { isStarted ->
                        when(isStarted) {
                            false -> {
                                requireContext().stopService(intent)
                            }
                            true -> {
                                ContextCompat.startForegroundService(requireContext(), intent)

                                viewModel.points.observe(viewLifecycleOwner) { points ->
                                    polylineOverlay.setPoints(points)
                                }
                                viewModel.onTrackingResult()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun disableTracking() {
        locationOverlay.disableMyLocation()
        locationOverlay.disableFollowLocation()
    }

    override fun onResume() {
        super.onResume()
        compassOverlay.enableCompass()
        scaleBarOverlay.enableScaleBar()
        enableTracking()
        polylineOverlay.isEnabled = true
        binding.map.onResume()
    }

    override fun onPause() {
        super.onPause()
        compassOverlay.disableCompass()
        scaleBarOverlay.disableScaleBar()
        disableTracking()
        polylineOverlay.isEnabled = false
        binding.map.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        permissionLauncher.unregister()
    }
}