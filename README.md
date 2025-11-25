# Atmotube PRO 2 Android Sample App

This sample application demonstrates how to interact with Atmotube PRO 2 devices using Android BLE. It provides a reference implementation for scanning, connecting, reading real-time data, and downloading historical data.

## Features

* [Atmotube PRO 2 API](https://support.atmotube.com/en/articles/12714501-atmotube-pro-2-api-documentation) 
* **Device Scanning**: Scan for nearby Atmotube PRO 2 devices using BLE.
*   **Connection Management**: Connect and disconnect from devices.
*   **Real-time Data**: View live readings for:
    *   TVOC
    *   Temperature
    *   Humidity
    *   Pressure
    *   PM
*   **History Download**: Download historical data from the device's flash memory.
*   **CSV Export**: Export downloaded history to a CSV file, which can be shared via email or other apps.
*   **Shell Commands**: Send low-level shell commands to the device (e.g., `version app`, `pm status`).

## Requirements

*   Android Studio Ladybug or newer (recommended).
*   `minSdk` is set to 29 (Android 10).

## Setup & Running

1.  Open the project `atmotube-pro2-android` in Android Studio.
2.  Sync Gradle project.
3.  Run the app on a physical Android device (BLE is typically not supported on emulators).
4.  Grant the required permissions (Location, Bluetooth) when prompted.

## Key Components

*   **`MainActivity.kt`**: The main entry point. Sets up the UI (Jetpack Compose), handles permissions, and manages the high-level app flow.
*   **`BleManager.kt`**: Manages BLE operations using Nordic Semiconductor's Android BLE Library. Handles connection, service discovery, and characteristic notifications.
*   **`DataParser.kt`**: Contains logic for parsing raw byte arrays from BLE characteristics into structured data (`AtmotubeReading`, `HistoryMeasurement`). Implements specific parsing rules for PM data and sensor status flags.
*   **`HistoryManager.kt`**: Manages the process of listing, downloading, and parsing history files from the device. Handles CSV export formatting.
*   **`Screens.kt`**: Defines the Jetpack Compose UI screens (`ScanScreen`, `DeviceScreen`).