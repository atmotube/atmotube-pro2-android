package com.atmotube.pro2.example

import java.io.InputStream
import java.util.Date

data class AtmotubeReading(
    val deviceMac: String,
    val timestamp: Date = Date(),
    val temperature: Double,
    val humidity: Int,
    val pressure: Double,
    val vocIndex: Int,
    val vocPpb: Int,
    val noxIndex: Int,
    val co2Ppm: Int,
    val pm1: Double,
    val pm25: Double,
    val pm10: Double,
    val batteryLevel: Int
) {
    companion object {
        val offValues: Set<Double> = setOf(0xFFFF.toDouble(), 0xFFFF.toDouble() / 10.0, 0x7FFF.toDouble())
        val heatingValues: Set<Double> = setOf(0xFFFE.toDouble(), 0xFFFE.toDouble() / 10.0, 0x7FFE.toDouble())
        val tInvalidValues: Set<Double> = setOf(0x7FFF.toDouble() / 100.0, 0x7FFE.toDouble() / 100.0)
        val hInvalidValues: Set<Double> = setOf(-1.0)
        val pInvalidValues: Set<Double> = setOf(0xFFFFFFFFL.toDouble() / 10.0)

        private const val PM_ENCODING_FLAG = 0x8000
        private const val PM_ENCODING_VALUE_MASK = 0x7FFF

        fun decodePmValue(raw: Int): Double {
            return if ((raw and PM_ENCODING_FLAG) != 0) {
                // Bit 15 set → integer format
                (raw and PM_ENCODING_VALUE_MASK).toDouble()
            } else {
                // Bit 15 clear → 0.1-precision format
                raw.toDouble() / 10.0
            }
        }

        fun formatSensorValue(value: Number?, type: String = "generic"): String {
            if (value == null) return ""
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
            val temperature = if (temperatureRaw == 0xFFFF) 65535.0 else temperatureRaw.toShort() / 100.0

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
                batteryLevel = batteryLevel
            )
        }

        fun parsePm(data: ByteArray): Triple<Double, Double, Double> {
            if (data.size < 6) return Triple(0.0, 0.0, 0.0)

            val pm1 = decodePmValue((data[1].toInt() and 0xFF) shl 8 or (data[0].toInt() and 0xFF))
            val pm25 = decodePmValue((data[3].toInt() and 0xFF) shl 8 or (data[2].toInt() and 0xFF))
            val pm10 = decodePmValue((data[5].toInt() and 0xFF) shl 8 or (data[4].toInt() and 0xFF))

            return Triple(pm1, pm25, pm10)
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

        fun parseStream(input: InputStream): List<HistoryMeasurement> {
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

                val temp = if ((tempRaw.toInt() and 0xFFFF) == 0xFFFF) 65535.0 else tempRaw / 100.0
                val hum = if (humidityU8 == 0xFF) -1 else humidityU8.toInt()
                val pressure = pressure10 / 10.0

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
                    // Firmware 3.0.17+ rules
                    pm1 = AtmotubeReading.decodePmValue(reader.readLeU16() ?: 0)
                    pm25 = AtmotubeReading.decodePmValue(reader.readLeU16() ?: 0)
                    pm10 = AtmotubeReading.decodePmValue(reader.readLeU16() ?: 0)
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
                        typicalParticleSize = tpsRaw / 1000.0
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

                reader.readCrcByte() // crc

                val flags = parseFlags(status)

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

        private fun parseFlags(status: Int): List<String> {
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

    private class CrcReader(private val input: InputStream) {
        fun readU8(): Int? {
            val v = input.read()
            return if (v == -1) null else v
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

        fun readCrcByte(): Int? = readU8()
    }
}

