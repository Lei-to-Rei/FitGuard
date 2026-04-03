package com.example.fitguard.features.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import com.example.fitguard.databinding.ItemMetricTileBinding
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.example.fitguard.data.health.HealthConnectManager
import com.example.fitguard.data.health.SamsungHealthSnapshot
import com.example.fitguard.databinding.ActivitySamsungHealthBinding
import kotlinx.coroutines.launch

class SamsungHealthActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySamsungHealthBinding
    private lateinit var hcm: HealthConnectManager

    private val prefs by lazy { getSharedPreferences("hc_prefs", Context.MODE_PRIVATE) }
    private var denyCount: Int
        get() = prefs.getInt("sh_deny_count", 0)
        set(v) = prefs.edit().putInt("sh_deny_count", v).apply()

    companion object {
        private const val TAG = "SamsungHealthActivity"
    }

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        Log.d(TAG, "Permissions result: $granted")
        if (granted.containsAll(HealthConnectManager.ALL_PERMISSIONS)) {
            denyCount = 0
            lifecycleScope.launch { fetchAndDisplay() }
        } else {
            denyCount++
            val missing = HealthConnectManager.ALL_PERMISSIONS - granted
            Log.w(TAG, "Missing: $missing")
            if (denyCount >= 2) showPermanentlyDeniedCard()
            else showSetupCard(
                "FitGuard needs permission to read your Samsung Health data.\nMissing: ${missing.size} permission(s).",
                showGrant = true
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySamsungHealthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hcm = HealthConnectManager(this)

        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        binding.btnSync.setOnClickListener {
            Toast.makeText(this, "Syncing…", Toast.LENGTH_SHORT).show()
            checkAndLoad()
        }
        binding.btnGrantPermissions.setOnClickListener {
            requestPermissions.launch(HealthConnectManager.ALL_PERMISSIONS)
        }
        binding.btnManageAccess.setOnClickListener { openHCSettings() }
    }

    override fun onResume() {
        super.onResume()
        checkAndLoad()
    }

    private fun checkAndLoad() {
        val status = try { HealthConnectClient.getSdkStatus(this) }
        catch (e: Exception) {
            showSetupCard("Health Connect check failed:\n${e.localizedMessage}", showGrant = false)
            return
        }
        Log.d(TAG, "SDK status=$status")
        when (status) {
            HealthConnectClient.SDK_AVAILABLE -> lifecycleScope.launch {
                try {
                    if (hcm.checkPermissions(HealthConnectManager.ALL_PERMISSIONS)) {
                        fetchAndDisplay()
                    } else if (denyCount >= 2) {
                        showPermanentlyDeniedCard()
                    } else {
                        showSetupCard(
                            "Connect FitGuard to Samsung Health via Health Connect to view all your health metrics.",
                            showGrant = true
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "checkPermissions threw", e)
                    showSetupCard("Health Connect error:\n${e.localizedMessage}", showGrant = false)
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                showSetupCard("Health Connect needs to be updated.", showGrant = false)
                binding.btnGrantPermissions.text = "Update Health Connect"
                binding.btnGrantPermissions.visibility = View.VISIBLE
                binding.btnGrantPermissions.setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                }
            }
            else -> showSetupCard("Health Connect is not available on this device.", showGrant = false)
        }
    }

    private suspend fun fetchAndDisplay() {
        hideSetupCard()
        showStatus("Syncing from Samsung Health…")
        try {
            val data = hcm.readAllMetrics()
            Log.d(TAG, "Snapshot fetched: steps=${data.steps}, hr=${data.heartRateBpm}")
            populateTiles(data)
            binding.scrollMetrics.visibility = View.VISIBLE
            hideStatus()
        } catch (e: Exception) {
            Log.e(TAG, "fetchAndDisplay error", e)
            showStatus("Failed to load: ${e.localizedMessage ?: e.javaClass.simpleName}")
        }
    }

    // ─── Tile population ────────────────────────────────────────────────────

    private fun populateTiles(d: SamsungHealthSnapshot) {
        // Activity
        tile(binding.tileSteps,          "Steps",          d.steps?.toString(),                        "steps today")
        tile(binding.tileDistance,       "Distance",       d.distanceMeters?.let { "%.2f".format(it / 1000.0) }, "km today")
        tile(binding.tileCalories,       "Active Cal.",    d.activeCaloriesKcal?.let { "%.0f".format(it) }, "kcal today")
        tile(binding.tileFloors,         "Floors",         d.floorsClimbed?.let { "%.0f".format(it) }, "floors today")
        // Vitals
        tile(binding.tileHeartRate,      "Heart Rate",     d.heartRateBpm?.toString(),                 "bpm")
        tile(binding.tileRestingHR,      "Resting HR",     d.restingHeartRateBpm?.toString(),          "bpm")
        tile(binding.tileSpO2,           "Blood Oxygen",   d.spO2Percent?.let { "%.1f".format(it) },  "%")
        tile(binding.tileRespiratoryRate,"Resp. Rate",     d.respiratoryRate?.let { "%.1f".format(it) }, "br/min")
        tile(binding.tileBodyTemp,       "Body Temp",      d.bodyTempCelsius?.let { "%.1f".format(it) }, "°C")
        tile(binding.tileBloodPressure,  "Blood Pressure", d.bloodPressure?.let { "${it.systolic}/${it.diastolic}" }, "mmHg")
        // Blood
        tile(binding.tileBloodGlucose,   "Blood Glucose",  d.bloodGlucoseMmolPerL?.let { "%.1f".format(it) }, "mmol/L")
        // Body
        tile(binding.tileWeight,         "Weight",         d.weightKg?.let { "%.1f".format(it) },     "kg")
        tile(binding.tileHeight,         "Height",         d.heightCm?.let { "%.1f".format(it) },     "cm")
        tile(binding.tileBmi,            "BMI",            d.bmi?.let { "%.1f".format(it) },          bmiLabel(d.bmi))
        tile(binding.tileBodyFat,        "Body Fat",       d.bodyFatPercent?.let { "%.1f".format(it) }, "%")
        tile(binding.tileLeanMass,       "Lean Mass",      d.leanBodyMassKg?.let { "%.1f".format(it) }, "kg")
        tile(binding.tileBmr,            "BMR",            d.basalMetabolicRateKcal?.let { "%.0f".format(it) }, "kcal/day")
        tile(binding.tileVo2Max,         "VO₂ Max",        d.vo2MaxMlPerMinPerKg?.let { "%.1f".format(it) }, "mL/kg/min")
    }

    private fun tile(t: ItemMetricTileBinding, label: String, value: String?, unit: String) {
        t.tvTileLabel.text = label
        t.tvTileValue.text = value ?: "--"
        t.tvTileUnit.text = if (value != null) unit else ""
    }

    private fun bmiLabel(bmi: Double?) = when {
        bmi == null -> ""
        bmi < 18.5 -> "Underweight"
        bmi < 25.0 -> "Normal"
        bmi < 30.0 -> "Overweight"
        else -> "Obese"
    }

    // ─── Setup card helpers ──────────────────────────────────────────────────

    private fun showSetupCard(message: String, showGrant: Boolean) {
        binding.cardSetup.visibility = View.VISIBLE
        binding.scrollMetrics.visibility = View.GONE
        binding.tvSetupTitle.text = "Samsung Health"
        binding.tvSetupMessage.text = message
        binding.btnGrantPermissions.text = "Connect Samsung Health"
        binding.btnGrantPermissions.visibility = if (showGrant) View.VISIBLE else View.GONE
        hideStatus()
    }

    private fun showPermanentlyDeniedCard() {
        binding.cardSetup.visibility = View.VISIBLE
        binding.scrollMetrics.visibility = View.GONE
        binding.tvSetupTitle.text = "Permissions Required"
        binding.tvSetupMessage.text =
            "You've declined permissions twice.\nPlease open Health Connect Settings to grant FitGuard access."
        binding.btnGrantPermissions.visibility = View.GONE
        binding.btnManageAccess.visibility = View.VISIBLE
        hideStatus()
    }

    private fun hideSetupCard() {
        binding.cardSetup.visibility = View.GONE
    }

    private fun showStatus(msg: String) {
        binding.tvStatus.text = msg
        binding.tvStatus.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        binding.tvStatus.visibility = View.GONE
    }

    private fun openHCSettings() {
        try {
            startActivity(Intent("androidx.health.ACTION_MANAGE_HEALTH_PERMISSIONS").apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
            })
        } catch (_: Exception) {
            startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS))
        }
    }
}
