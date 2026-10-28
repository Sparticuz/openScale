package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.EufyT9140Protocol
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Captured T9140 V2 F6 variant, live weight/impedance only. No derived metrics,
 * profile upload, history or application characteristic writes. Passive startup
 * and radio timing still require a hardware trial.
 */
class EufyT9140Handler(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : ScaleDeviceHandler() {
    private val service = UUID.fromString("4143f6b0-5300-4900-4700-414943415245")
    private val notify = UUID.fromString("4143f6b2-5300-4900-4700-414943415245")
    private val protocol = EufyT9140Protocol()
    private val lock = Any()
    private var timeoutJob: Job? = null
    private var active = false
    private var connectionGeneration = 0L

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase(Locale.ROOT)
        if (name != "eufy t9140" && name != "eufy t9140 v2") return null
        return DeviceSupport(
            displayName = "Eufy T9140 V2 (weight + impedance)",
            capabilities = setOf(DeviceCapability.BODY_COMPOSITION),
            // Collecting impedance does not implement the scale's composition estimates.
            implemented = emptySet(),
            tuningProfile = TuningProfile.Conservative,
            linkMode = LinkMode.CONNECT_GATT,
        )
    }

    override fun onConnected(user: ScaleUser) {
        synchronized(lock) {
            val generation = ++connectionGeneration
            timeoutJob?.cancel()
            protocol.reset()
            active = false
            if (!hasCharacteristic(service, notify)) {
                logW("T9140: captured F6 notification characteristic not found")
                return
            }
            active = true
            setNotifyOn(service, notify)
            timeoutJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    while (isActive) {
                        delay(250)
                        synchronized(lock) {
                            if (generation == connectionGeneration) publishReadings(protocol.flush(nowMs()))
                        }
                    }
                } finally {
                    synchronized(lock) {
                        if (generation == connectionGeneration) onDisconnected()
                    }
                }
            }
            userInfo(R.string.bt_info_step_on_scale)
        }
    }

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) {
        if (characteristic != notify) return
        synchronized(lock) {
            if (active) publishReadings(protocol.feed(data, nowMs()))
        }
    }

    override fun onDisconnected() {
        synchronized(lock) {
            connectionGeneration++
            timeoutJob?.cancel()
            timeoutJob = null
            // Best effort only: the baseline connector can cancel its event collector first.
            // Neither this callback nor scope-finally guarantees database persistence.
            publishReadings(protocol.finishPending())
            protocol.reset()
            active = false
        }
    }

    private fun publishReadings(readings: List<EufyT9140Protocol.Reading>) {
        for (reading in readings) {
            val measurement = ScaleMeasurement(dateTime = Date())
            measurement[MeasurementType.WEIGHT] = Kg(reading.weightKg)
            reading.rawImpedance?.let {
                // User-accepted inference, not an electrical calibration.
                measurement[MeasurementType.IMPEDANCE] = Ohm(it.toFloat())
            }
            publish(measurement)
        }
    }
}
