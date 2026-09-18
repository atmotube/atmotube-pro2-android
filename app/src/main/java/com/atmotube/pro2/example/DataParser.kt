package com.atmotube.pro2.example

import java.io.InputStream
import java.util.Date

/**
 * Firmware 3.0.17+ encodes PM values with bit 15 as a format flag (integer µg/m³ when set,
 * legacy 0.1-precision fixed point when clear). Older firmware always uses the legacy format.
 * Callers must pass the actual `isNewPmFormat` flag (see [AtmotubeBleManager.isNewPmFormat]) -
 * assuming "always new" silently corrupts PM readings from devices on older firmware.
 */
fun checkFwNew(demandedMajor: Int, demandedMinor: Int, demandedPatch: Int, fw: String?): Boolean {
    if (fw == null) return false
    val components = fw.substringBefore("-").split(".")
    val major = components.getOrNull(0)?.toIntOrNull() ?: 0
    val minor = components.getOrNull(1)?.toIntOrNull() ?: 0
    val patch = components.getOrNull(2)?.toIntOrNull() ?: 0

    return when {
        major != demandedMajor -> major > demandedMajor
        minor != demandedMinor -> minor > demandedMinor
        else -> patch >= demandedPatch
    }
}

data class AtmotubeReading(
    val deviceMac: String,
    val timestamp: Date = Date(),
    val temperature: Double?,
    val humidity: Int,
    val pressure: Double,
    val vocIndex: Int,
    val vocPpb: Int,
    val noxIndex: Int,
    val co2Ppm: Int,
    val pm1: Double,
    val pm25: Double,
    val pm10: Double,
    val batteryLevel: Int,
    val errorFlags: Int
) {
    val errorDescriptions: List<String>
        get() = HistoryParser.parseFlags(errorFlags)


    companion object {
        val offValues: Set<Double> = setOf(0xFFFF.toDouble(), 0xFFFF.toDouble() / 10.0, 0x7FFF.toDouble())
        val heatingValues: Set<Double> = setOf(0xFFFE.toDouble(), 0xFFFE.toDouble() / 10.0, 0x7FFE.toDouble())
        val tInvalidValues: Set<Double> = setOf(0x7FFF.toDouble() / 100.0, 0x7FFE.toDouble() / 100.0)
        val hInvalidValues: Set<Double> = setOf(-1.0)
        val pInvalidValues: Set<Double> = setOf(0xFFFFFFFFL.toDouble() / 10.0)

        private const val PM_ENCODING_FLAG = 0x8000
        private const val PM_ENCODING_VALUE_MASK = 0x7FFF

        // The device's own invalid-temperature sentinel is 0x7FFF (not 0xFFFF - that value is
        // never sent on the wire for temperature, only reused here as a display sentinel).
        private const val TEMPERATURE_INVALID_RAW = 0x7FFF

        fun decodePmValue(raw: Int, isNewPmFormat: Boolean): Double {
            if (!isNewPmFormat) return raw.toDouble() / 10.0
            return if ((raw and PM_ENCODING_FLAG) != 0) {
                // Bit 15 set → integer format
                (raw and PM_ENCODING_VALUE_MASK).toDouble()
            } else {
                // Bit 15 clear → 0.1-precision format
                raw.toDouble() / 10.0
            }
        }

        fun formatSensorValue(value: Number?, type: String = "generic"): String {
            if (value == null) {
                // For temp/hum/press, null means the device sent its invalid-reading sentinel (or
                // the record was taken while charging); for everything else it means the packet
                // simply didn't include that optional block.
                return if (type == "temp" || type == "hum" || type == "press") "Off" else ""
            }
            val v = value.toDouble()

            if (v in offValues) return "Off"
            if (v in heatingValues) return "Heating"

            if (type == "temp" && v in tInvalidValues) return "Off"
            if (type == "hum" && v in hInvalidValues) return "Off"
            if (type == "press" && v in pInvalidValues) return "Off"

            return value.toString()
        }

        fun fromBytes(data: ByteArray, deviceMac: String): AtmotubeReading {
            val temperatureRaw = ((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF)
            val temperature = if (temperatureRaw == TEMPERATURE_INVALID_RAW) null else temperatureRaw.toShort() / 100.0

            val humidityRaw = data[2].toInt() and 0xFF
            val humidity = if (humidityRaw == 0xFF) -1 else humidityRaw

            val pressureRaw = (data[6].toLong() and 0xFFL shl 24) or
                    (data[5].toLong() and 0xFFL shl 16) or
                    (data[4].toLong() and 0xFFL shl 8) or
                    (data[3].toLong() and 0xFFL)
            val pressure = pressureRaw / 10.0

            fun readUShort(offset: Int): Int =
                ((data[offset + 1].toInt() and 0xFF) shl 8 or (data[offset].toInt() and 0xFF))

            val vocIndex = readUShort(7)
            val vocPpb = readUShort(9)
            val noxIndex = readUShort(11)
            val co2Ppm = readUShort(13)
            val batteryLevel = data[15].toInt() and 0xFF
            // Bytes 16..17 (error/status flags) are only present on firmware that sends the full
            // 18-byte packet; older packets are still handled by defaulting to "no errors known".
            val errorFlags = if (data.size >= 18) readUShort(16) else 0

            return AtmotubeReading(
                deviceMac = deviceMac,
                temperature = temperature,
                humidity = humidity,
                pressure = pressure,
                vocIndex = vocIndex,
                vocPpb = vocPpb,
                noxIndex = noxIndex,
                co2Ppm = co2Ppm,
                pm1 = 0.0,
                pm25 = 0.0,
                pm10 = 0.0,
                batteryLevel = batteryLevel,
                errorFlags = errorFlags
            )
        }

        /**
         * Parses the PM live-notification characteristic. The device sends 16 bytes: PM1/2.5/10
         * mass concentration (µg/m³), followed by PM0.5/1/2.5/10 particle counts (particles/cm³)
         * and the typical particle size (µm) - all of which are dropped if you only read the
         * first 6 bytes.
         */
        fun parsePm(data: ByteArray, isNewPmFormat: Boolean): AtmotubePmReading {
            if (data.size < 16) {
                return AtmotubePmReading(0.0, 0.0, 0.0, 0, 0, 0, 0, 0.0)
            }

            fun readUShort(offset: Int): Int =
                ((data[offset + 1].toInt() and 0xFF) shl 8 or (data[offset].toInt() and 0xFF))

            val pm1 = decodePmValue(readUShort(0), isNewPmFormat)
            val pm25 = decodePmValue(readUShort(2), isNewPmFormat)
            val pm10 = decodePmValue(readUShort(4), isNewPmFormat)

            val pm05Particles = readUShort(6)
            val pm1Particles = readUShort(8)
            val pm25Particles = readUShort(10)
            val pm10Particles = readUShort(12)
            val typicalParticleSize = readUShort(14) / 10.0

            return AtmotubePmReading(
                pm1 = pm1,
                pm25 = pm25,
                pm10 = pm10,
                pm05Particles = pm05Particles,
                pm1Particles = pm1Particles,
                pm25Particles = pm25Particles,
                pm10Particles = pm10Particles,
                typicalParticleSize = typicalParticleSize
            )
        }
    }
}

data class AtmotubePmReading(
    // µg/m³
    val pm1: Double,
    val pm25: Double,
    val pm10: Double,
    // particles/cm³
    val pm05Particles: Int,
    val pm1Particles: Int,
    val pm25Particles: Int,
    val pm10Particles: Int,
    // µm
    val typicalParticleSize: Double
)

/** Parses the GPS live-notification characteristic (19 bytes). */
data class AtmotubeGpsReading(
    val latitude: Double,
    val longitude: Double,
    val altitude: Short,
    val satellitesFixed: Int,
    val satellitesInView: Int,
    val accuracy: Int,
    val isOn: Boolean
) {
    companion object {
        fun fromBytes(data: ByteArray): AtmotubeGpsReading? {
            if (data.size < 19) return null

            fun readInt32(offset: Int): Int =
                ((data[offset + 3].toInt() and 0xFF) shl 24) or
                        ((data[offset + 2].toInt() and 0xFF) shl 16) or
                        ((data[offset + 1].toInt() and 0xFF) shl 8) or
                        (data[offset].toInt() and 0xFF)

            fun readUShort(offset: Int): Int =
                ((data[offset + 1].toInt() and 0xFF) shl 8) or (data[offset].toInt() and 0xFF)

            val latitude = readInt32(0) / 1e6
            val longitude = readInt32(4) / 1e6
            // bytes 8..11 are GNSS SNR buckets, not used in this example
            val altitude = readUShort(12).toShort()
            val satellitesFixed = data[14].toInt() and 0xFF
            val satellitesInView = data[15].toInt() and 0xFF
            val accuracy = readUShort(16)
            val isOn = (data[18].toInt() and 0xFF) != 0

            return AtmotubeGpsReading(
                latitude = latitude,
                longitude = longitude,
                altitude = altitude,
                satellitesFixed = satellitesFixed,
                satellitesInView = satellitesInView,
                accuracy = accuracy,
                isOn = isOn
            )
        }
    }
}

// Full Measurement for History
data class HistoryMeasurement(
    val timestamp: Long,
    val temperature: Double?,
    val humidity: Int?,
    val pressure: Double?,
    val batteryLevel: Int?,
    val statusFlags: Int?,
    val vocIndex: Int?,
    val vocPpb: Int?,
    val noxIndex: Int?,
    val co2Ppm: Int?,
    val pm1: Double?,
    val pm25: Double?,
    val pm10: Double?,
    val latitude: Double?,
    val longitude: Double?,
    val pm05Particles: Int?,
    val pm1Particles: Int?,
    val pm25Particles: Int?,
    val pm10Particles: Int?,
    val typicalParticleSize: Double?,
    val altitude: Double?,
    val satellitesFixed: Int?,
    val satellitesView: Int?,
    val accuracy: Double?,
    val flags: List<String> = emptyList()
)

class HistoryParser {
    companion object {
        private const val VOC_BIT = 0b00000001
        private const val CO2_BIT = 0b00000010
        private const val PM_BIT = 0b00000100
        private const val PM_EXT_BIT = 0b00001000
        private const val GPS_BIT = 0b00010000
        private const val GPS_EXT_BIT = 0b00100000

        private const val CHARGING_BIT = 1 shl 14
        private const val RECENTLY_CHARGED_BIT = 1 shl 15

        fun parseStream(input: InputStream, isNewPmFormat: Boolean): List<HistoryMeasurement> {
            val list = mutableListOf<HistoryMeasurement>()
            val reader = CrcReader(input)

            while (true) {
                // Header
                val historyType = reader.readU8() ?: break
                val packetType = reader.readU8() ?: break

                // Core
                val tsSeconds = reader.readLeU32() ?: break
                val tempRaw = reader.readLeI16() ?: break
                val humidityU8 = reader.readU8() ?: break
                val pressure10 = reader.readLeU32() ?: break
                val batteryU8 = reader.readU8() ?: break
                val status = reader.readLeU16() ?: break

                var temp: Double? = if ((tempRaw.toInt() and 0xFFFF) == 0x7FFF) null else tempRaw / 100.0
                var hum: Int? = if (humidityU8 == 0xFF) null else humidityU8
                val pressure = if (pressure10 == 0xFFFFFFFFL) null else pressure10 / 10.0

                var vocIndex: Int? = null
                var vocPpb: Int? = null
                var noxIndex: Int? = null
                if ((packetType and VOC_BIT) != 0) {
                    vocIndex = reader.readLeU16()
                    vocPpb = reader.readLeU16()
                    noxIndex = reader.readLeU16()
                }

                var co2Ppm: Int? = null
                if ((packetType and CO2_BIT) != 0) {
                    co2Ppm = reader.readLeU16()
                }

                var pm1: Double? = null
                var pm25: Double? = null
                var pm10: Double? = null
                if ((packetType and PM_BIT) != 0) {
                    pm1 = AtmotubeReading.decodePmValue(reader.readLeU16() ?: 0, isNewPmFormat)
                    pm25 = AtmotubeReading.decodePmValue(reader.readLeU16() ?: 0, isNewPmFormat)
                    pm10 = AtmotubeReading.decodePmValue(reader.readLeU16() ?: 0, isNewPmFormat)
                }

                var latitude: Double? = null
                var longitude: Double? = null
                if ((packetType and GPS_BIT) != 0) {
                    val latRaw = reader.readLeI32()
                    val lonRaw = reader.readLeI32()
                    if (latRaw != null && lonRaw != null) {
                        latitude = latRaw / 1000000.0
                        longitude = lonRaw / 1000000.0
                    }
                }

                var pm05Particles: Int? = null
                var pm1Particles: Int? = null
                var pm25Particles: Int? = null
                var pm10Particles: Int? = null
                var typicalParticleSize: Double? = null
                if ((packetType and PM_EXT_BIT) != 0) {
                    pm05Particles = reader.readLeU16()
                    pm1Particles = reader.readLeU16()
                    pm25Particles = reader.readLeU16()
                    pm10Particles = reader.readLeU16()
                    val tpsRaw = reader.readLeU16()
                    if (tpsRaw != null) {
                        typicalParticleSize = tpsRaw / 10.0
                    }
                }

                var altitude: Double? = null
                var satellitesFixed: Int? = null
                var satellitesView: Int? = null
                var accuracy: Double? = null
                if ((packetType and GPS_EXT_BIT) != 0) {
                    repeat(4) { reader.readU8() } // snrs
                    val altRaw = reader.readLeI16()
                    if (altRaw != null) altitude = altRaw.toDouble()
                    satellitesFixed = reader.readU8()
                    satellitesView = reader.readU8()
                    val accRaw = reader.readLeI16()
                    if (accRaw != null) accuracy = accRaw / 100.0
                }

                val crcExpected = reader.readCrcByte() // final CRC byte, not fed into the running CRC
                val crcValid = crcExpected != null && reader.crc() == crcExpected

                // While charging (or shortly after), the temperature/humidity sensor readings are
                // unreliable due to self-heating - the device flags this in the status bits rather
                // than omitting the fields, so consumers must null them out themselves.
                val charging = (status and CHARGING_BIT) != 0
                val recentlyCharged = (status and RECENTLY_CHARGED_BIT) != 0
                if (charging || recentlyCharged) {
                    temp = null
                    hum = null
                }

                val flags = parseFlags(status) + if (!crcValid) listOf("CRC mismatch") else emptyList()

                list.add(HistoryMeasurement(
                    timestamp = tsSeconds,
                    temperature = temp,
                    humidity = hum,
                    pressure = pressure,
                    batteryLevel = batteryU8,
                    statusFlags = status,
                    vocIndex = vocIndex,
                    vocPpb = vocPpb,
                    noxIndex = noxIndex,
                    co2Ppm = co2Ppm,
                    pm1 = pm1,
                    pm25 = pm25,
                    pm10 = pm10,
                    latitude = latitude,
                    longitude = longitude,
                    pm05Particles = pm05Particles,
                    pm1Particles = pm1Particles,
                    pm25Particles = pm25Particles,
                    pm10Particles = pm10Particles,
                    typicalParticleSize = typicalParticleSize,
                    altitude = altitude,
                    satellitesFixed = satellitesFixed,
                    satellitesView = satellitesView,
                    accuracy = accuracy,
                    flags = flags
                ))
            }
            return list
        }

        fun parseFlags(status: Int): List<String> {
            val descriptions = mapOf(
                0 to "PM sensor error",
                1 to "PM laser error",
                2 to "PM fan error",
                3 to "CO2 error",
                4 to "VOC/NOx error",
                5 to "Pressure error",
                6 to "Accelerometer error",
                7 to "Charger error",
                8 to "Flash error",
                9 to "GPS error",
                10 to "External module error",
                12 to "Motion",
                13 to "PM enabled",
                14 to "Charging",
                15 to "Recently charged"
            )
            return descriptions.mapNotNull { (bit, desc) ->
                if ((status and (1 shl bit)) != 0) desc else null
            }
        }
    }

    /**
     * Reads bytes from the history stream while feeding them into a running CRC-8 (poly 0x31,
     * matching the device's firmware), so a caller can compare it against the trailing CRC byte
     * (which is itself excluded from the running CRC).
     */
    private class CrcReader(private val input: InputStream) {
        private var crc: Int = 0x00

        fun crc(): Int = crc and 0xFF

        private fun feed(b: Int) {
            var c = crc xor (b and 0xFF)
            repeat(8) {
                c = if ((c and 0x80) != 0) ((c shl 1) xor 0x31) and 0xFF else (c shl 1) and 0xFF
            }
            crc = c
        }

        fun readU8(): Int? {
            val v = input.read()
            if (v == -1) return null
            feed(v)
            return v
        }

        fun readLeU16(): Int? {
            val b0 = readU8() ?: return null
            val b1 = readU8() ?: return null
            return b0 or (b1 shl 8)
        }

        fun readLeI16(): Short? {
            val b0 = readU8() ?: return null
            val b1 = readU8() ?: return null
            return ((b1 shl 8) or b0).toShort()
        }

        fun readLeU32(): Long? {
            val b0 = readU8() ?: return null
            val b1 = readU8() ?: return null
            val b2 = readU8() ?: return null
            val b3 = readU8() ?: return null
            return (b0.toLong()) or
                    (b1.toLong() shl 8) or
                    (b2.toLong() shl 16) or
                    (b3.toLong() shl 24)
        }

        fun readLeI32(): Int? {
            val b0 = readU8() ?: return null
            val b1 = readU8() ?: return null
            val b2 = readU8() ?: return null
            val b3 = readU8() ?: return null
            return (b0) or
                    (b1 shl 8) or
                    (b2 shl 16) or
                    (b3 shl 24)
        }

        fun readCrcByte(): Int? {
            val v = input.read()
            return if (v == -1) null else (v and 0xFF)
        }
    }
}
