package com.health.openscale.core.bluetooth.scales

import com.health.openscale.R
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.bluetooth.libs.EufyT9140Calculator
import com.health.openscale.core.bluetooth.libs.EufyT9140Protocol
import com.health.openscale.core.data.GenderType
import com.health.openscale.core.data.Kcal
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.data.Ohm
import com.health.openscale.core.data.Percent
import com.health.openscale.core.service.ScannedDeviceInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * T9140 V2, captured F6 service variant only; not C1/P1/P2 or F7/FFB variants.
 * Notification-only startup: captured time, unit and undocumented writes are not
 * replayed. No authentication, profile upload, history read or history clearing.
 * Only completed weigh-ins are published (CE settling frames are session markers).
 * Hardware notification/startup timing still requires a real-device trial.
 */
class EufyT9140Handler(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) : ScaleDeviceHandler() {
    private val service = UUID.fromString("4143f6b0-5300-4900-4700-414943415245")
    private val notify = UUID.fromString("4143f6b2-5300-4900-4700-414943415245")
    private val protocol = EufyT9140Protocol()
    private val lock = Any()
    private var timeoutJob: Job? = null
    private var connectedUser: ScaleUser? = null
    private var connectionGeneration = 0L

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val name = device.name.lowercase(Locale.ROOT)
        if (name != "eufy t9140" && name != "eufy t9140 v2") return null
        val caps = setOf(DeviceCapability.BODY_COMPOSITION)
        return DeviceSupport(
            displayName = "Eufy T9140 V2 (live)",
            capabilities = caps,
            implemented = caps,
            tuningProfile = TuningProfile.Conservative,
            linkMode = LinkMode.CONNECT_GATT,
        )
    }

    override fun onConnected(user: ScaleUser) {
        synchronized(lock) {
            val generation = ++connectionGeneration
            timeoutJob?.cancel()
            protocol.reset()
            connectedUser = null
            if (!hasCharacteristic(service, notify)) {
                logW("T9140: captured F6 notification characteristic not found")
                return
            }
            // Pin identity/profile for this connection instead of mixing users mid-weigh-in.
            connectedUser = user.copy(birthday = Date(user.birthday.time))
            setNotifyOn(service, notify)
            // Enter finally now, even if the adapter scope is cancelled immediately.
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
                        // An old cancelled job must not flush a newer connection.
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
            if (connectedUser != null) publishReadings(protocol.feed(data, nowMs()))
        }
    }

    override fun onDisconnected() {
        synchronized(lock) {
            connectionGeneration++
            timeoutJob?.cancel()
            timeoutJob = null
            publishReadings(protocol.finishPending())
            protocol.reset()
            connectedUser = null
        }
    }

    override fun takePendingMeasurements(): List<ScaleMeasurement> = synchronized(lock) {
        val pending = buildMeasurements(protocol.finishPending())
        // Consume first, then invalidate the timer; disconnect/finally cannot publish twice.
        onDisconnected()
        pending
    }

    private fun publishReadings(readings: List<EufyT9140Protocol.Reading>) {
        buildMeasurements(readings).forEach { publish(it) }
    }

    private fun buildMeasurements(readings: List<EufyT9140Protocol.Reading>): List<ScaleMeasurement> {
        val user = connectedUser ?: return emptyList()
        return readings.map { reading ->
            val m = ScaleMeasurement(
                userId = user.id.takeIf { it > 0 } ?: 0xff,
                dateTime = Date(),
            )
            m[MeasurementType.WEIGHT] = Kg(reading.weightKg)
            val raw = reading.rawImpedance
            if (raw != null) {
                // Accepted working assumption: one transmitted count = one ohm.
                // The calculator's raw/1000 is separate algorithm input scaling.
                m[MeasurementType.IMPEDANCE] = Ohm(raw.toFloat())
                val gender = when (user.gender) {
                    GenderType.MALE -> EufyT9140Calculator.Gender.MALE
                    GenderType.FEMALE -> EufyT9140Calculator.Gender.FEMALE
                }
                val composition = if (user.id > 0) EufyT9140Calculator.calculate(
                    reading.weightKg, user.bodyHeight, user.age, gender, raw,
                ) else null
                if (composition != null) {
                    m.compositionProfileUserId = user.id
                    m[MeasurementType.BODY_FAT] = Percent(composition.fatPercent)
                    m[MeasurementType.WATER] = Percent(composition.waterPercent)
                    m[MeasurementType.MUSCLE] = Percent(composition.musclePercent)
                    m[MeasurementType.BONE] = Kg(composition.boneKg)
                    m[MeasurementType.LBM] = Kg(composition.leanKg)
                    m[MeasurementType.BMR] = Kcal(composition.bmrKcal)
                    m[MeasurementType.VISCERAL_FAT] = composition.visceralFat
                }
            }
            m
        }
    }
}
