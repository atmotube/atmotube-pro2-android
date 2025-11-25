package com.atmotube.pro2.example

import android.content.Context
import android.os.Environment
import android.util.Log
import com.atmotube.pro2.example.AtmotubeBleManager
import io.runtime.mcumgr.managers.FsManager
import io.runtime.mcumgr.transfer.StreamDownloadCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class HistoryManager(
    private val context: Context,
    private val bleManager: AtmotubeBleManager
) {

    suspend fun downloadHistory(): File? = withContext(Dispatchers.IO) {
        val transport = bleManager.getTransport() ?: return@withContext null
        val fsManager = FsManager(transport)

        // 1. Get list of files
        val fileList = listFiles()
        if (fileList.isEmpty()) return@withContext null

        val allMeasurements = mutableListOf<HistoryMeasurement>()

        // 2. Download each file
        for (fileName in fileList) {
            try {
                val file = downloadFile(fsManager, fileName)
                // 3. Parse file
                val measurements = file.inputStream().use { HistoryParser.parseStream(it) }
                allMeasurements.addAll(measurements)

                // 4. Send sync confirmation (optional but good practice)
                // bleManager.sendShellCommand("history sync $fileName")

                file.delete() // Clean up temp file
            } catch (e: Exception) {
                Log.e("HistoryManager", "Failed to download/parse $fileName", e)
            }
        }

        // 5. Export to CSV
        if (allMeasurements.isNotEmpty()) {
            return@withContext exportToCsv(allMeasurements)
        }
        return@withContext null
    }

    private suspend fun listFiles(): List<String> = withContext(Dispatchers.IO) {
        val transport = bleManager.getTransport() ?: return@withContext emptyList()
        val shell = io.runtime.mcumgr.managers.ShellManager(transport)
        try {
            val response = shell.exec("history", arrayOf("get"))
            if (response.ret == 0) {
                val clean = response.o.replace("history get ", "").trim()
                clean.split(";")
                    .map { it.substringBefore(",") }
                    .filter { it.isNotEmpty() && it.contains("h_active") }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun downloadFile(fsManager: FsManager, remotePath: String): File =
        suspendCoroutine { cont ->
            val tempFile = File(context.cacheDir, "temp_history.bin")
            val fos = FileOutputStream(tempFile)

            fsManager.fileDownload(remotePath, fos, object : StreamDownloadCallback {
                override fun onDownloadProgressChanged(current: Int, total: Int, timestamp: Long) {}

                override fun onDownloadFailed(error: io.runtime.mcumgr.exception.McuMgrException) {
                    try {
                        fos.close()
                    } catch (e: Exception) {
                    }
                    cont.resumeWithException(error)
                }

                override fun onDownloadCanceled() {
                    try {
                        fos.close()
                    } catch (e: Exception) {
                    }
                    cont.resumeWithException(Exception("Canceled"))
                }

                override fun onDownloadCompleted() {
                    try {
                        fos.close()
                    } catch (e: Exception) {
                    }
                    cont.resume(tempFile)
                }
            })
        }

    private fun exportToCsv(measurements: List<HistoryMeasurement>): File {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "atmotube_history_${System.currentTimeMillis()}.csv")
        file.bufferedWriter().use { writer ->
            writer.append("Timestamp,Date,Temperature,Humidity,Pressure,Battery,Status,Flags,VOC Index,VOC ppb,NOx Index,CO2 ppm,PM1,PM2.5,PM10,Lat,Lon,PM0.5 (#),PM1 (#),PM2.5 (#),PM10 (#),Typical Particle (µm),Alt,Sat Fixed,Sat View,Accuracy\n")
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

            for (m in measurements) {
                val date = sdf.format(Date(m.timestamp * 1000))
                val flagsStr = m.flags.joinToString("|")

                val vocIndexStr = AtmotubeReading.formatSensorValue(m.vocIndex)
                val vocPpbStr = AtmotubeReading.formatSensorValue(m.vocPpb)
                val noxIndexStr = AtmotubeReading.formatSensorValue(m.noxIndex)
                val co2PpmStr = AtmotubeReading.formatSensorValue(m.co2Ppm)

                val tempStr = AtmotubeReading.formatSensorValue(m.temperature, "temp")
                val humStr = AtmotubeReading.formatSensorValue(m.humidity, "hum")
                val pressStr = AtmotubeReading.formatSensorValue(m.pressure, "press")

                writer.append("${m.timestamp},$date,$tempStr,$humStr,$pressStr,${m.batteryLevel ?: ""},${m.statusFlags ?: ""},$flagsStr,$vocIndexStr,$vocPpbStr,$noxIndexStr,$co2PpmStr,${m.pm1 ?: ""},${m.pm25 ?: ""},${m.pm10 ?: ""},${m.latitude ?: ""},${m.longitude ?: ""},${m.pm05Particles ?: ""},${m.pm1Particles ?: ""},${m.pm25Particles ?: ""},${m.pm10Particles ?: ""},${m.typicalParticleSize ?: ""},${m.altitude ?: ""},${m.satellitesFixed ?: ""},${m.satellitesView ?: ""},${m.accuracy ?: ""}\n")
            }
        }
        return file
    }
}
